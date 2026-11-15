@file:SuppressLint("DiscouragedApi")

package dev.lackluster.mihelper.hook.base

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.res.Resources
import com.highcapable.kavaref.KavaRef.Companion.resolve

abstract class ContextAwareHooker : StaticHooker() {
    abstract val targetPackage: String

    @Volatile
    private var isInitialized = false

    var hostContext: Context? = null
        private set

    private val clzApplication by lazy {
        hookParam.appInfo?.className?.toClassOrNull()
            ?: "android.app.Application".toClass()
    }

    private val metAttachBaseContext by lazy {
        clzApplication.resolve().firstMethodOrNull {
            name = "attachBaseContext"
            parameters(Context::class)
        } ?: clzApplication.resolve().firstMethod {
            name = "attachBaseContext"
            parameters(Context::class)
            superclass()
        }
    }

    private val metOnCreate by lazy {
        clzApplication.resolve().firstMethodOrNull {
            name = "onCreate"
            parameterCount = 0
        } ?: clzApplication.resolve().firstMethod {
            name = "onCreate"
            parameterCount = 0
            superclass()
        }
    }

    final override fun onHook() {
        metAttachBaseContext.hook {
            // SystemUI adds its ResourcesLoader after super.attachBaseContext().
            // Follow the concrete Application override and wait for it to finish.
            val ori = proceed()
            (thisObject as? Application)?.let { ensureReady(it) }
            result(ori)
        }
        metOnCreate.hook {
            // Also cover ROMs where the inherited attachBaseContext call is inlined.
            (thisObject as? Application)?.let { ensureReady(it) }
            result(proceed())
        }
    }

    @Synchronized
    fun ensureReady(context: Context) {
        if (isInitialized || context.packageName != targetPackage) return
        val scope = ContextScope(context, targetPackage)
        scope.onReady()
        hostContext = context
        isInitialized = true
    }

    abstract fun ContextScope.onReady()
}

class ContextScope(val context: Context, val pkgName: String) {
    val res: Resources get() = context.resources

    fun String.toId(): Int = res.getIdentifier(this, "id", pkgName)
    fun String.toArrayId(): Int = res.getIdentifier(this, "array", pkgName)
    fun String.toBoolId(): Int = res.getIdentifier(this, "bool", pkgName)
    fun String.toDimenId(): Int = res.getIdentifier(this, "dimen", pkgName)
    fun String.toDrawableId(): Int = res.getIdentifier(this, "drawable", pkgName)
    fun String.toLayoutId(): Int = res.getIdentifier(this, "layout", pkgName)
    fun String.toStringId(): Int = res.getIdentifier(this, "string", pkgName)
    fun String.toStyleId(): Int = res.getIdentifier(this, "style", pkgName)

    fun String.getString(): String = res.getString(this.toStringId())

    fun String.getDimen(): Float = res.getDimension(this.toDimenId())

    fun Int.dp2px(): Int = (this * res.displayMetrics.density + 0.5f).toInt()
}
