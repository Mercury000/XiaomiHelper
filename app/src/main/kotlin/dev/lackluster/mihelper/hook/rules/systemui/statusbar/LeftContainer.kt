package dev.lackluster.mihelper.hook.rules.systemui.statusbar

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import androidx.core.view.isGone
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.makeAccessible
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.systemui.ResourcesUtils.notification_icon_area
import dev.lackluster.mihelper.hook.rules.systemui.ResourcesUtils.status_bar_view_state_tag
import dev.lackluster.mihelper.hook.rules.systemui.compat.CommonClassUtils.clzMiuiKeyguardStatusBarView
import dev.lackluster.mihelper.hook.rules.systemui.statusbar.IconManager.getLeftBlockList
import dev.lackluster.mihelper.hook.rules.systemui.statusbar.IconManager.leftBlockList
import dev.lackluster.mihelper.hook.utils.RemotePreferences.lazyGet
import dev.lackluster.mihelper.hook.utils.extraOf
import dev.lackluster.mihelper.hook.utils.toTyped
import dev.lackluster.mihelper.utils.factory.dp
import io.github.libxposed.api.XposedInterface
import com.highcapable.kavaref.extension.isSubclassOf
import com.highcapable.kavaref.extension.classOf

object LeftContainer : StaticHooker() {
    private var Any.leftStatusIconContainer by extraOf<ViewGroup>("KEY_LEFT_STATUS_ICON_CONTAINER")
    private var Any.leftStatusIconManager by extraOf<Any>("KEY_LEFT_STATUS_ICON_MANAGER")
    private var Any.leftStatusIconHost by extraOf<ViewGroup>("KEY_LEFT_STATUS_ICON_HOST")
    private var ViewGroup.islandRect by extraOf<Rect>("KEY_ISLAND_RECT")
    private var ViewGroup.islandShowing by extraOf("KEY_ISLAND_SHOWING", false)

    private val leftContainerMode by Preferences.SystemUI.StatusBar.IconTuner.LEFT_CONTAINER.lazyGet()

    private val clzMiuiStatusIconContainer by "com.android.systemui.statusbar.views.MiuiStatusIconContainer".lazyClassOrNull()
    private val ctorMiuiStatusIconContainer by lazy {
        clzMiuiStatusIconContainer?.resolve()?.firstConstructorOrNull {
            parameters(Context::class)
            parameterCount = 1
        }?.toTyped()
    }
    private val leftContainers = mutableListOf<ViewGroup>()

    // OS4 no longer exposes these through the status bar owner, so grab the singletons as they
    // are constructed and keep them for the icon-group registration below.
    @Volatile
    private var iconManagerFactory: Any? = null
    @Volatile
    private var statusBarIconControllerRef: Any? = null
    @Volatile
    private var darkIconDispatcherRef: Any? = null

    // Reuse the provider that built the host's own DarkIconManager; its constructor is inlined by
    // R8 so it cannot be instantiated directly.
    private fun createDarkIconManager(
        clzDarkIconManager: Class<*>?,
        group: Any?,
        location: Any?,
        dispatcher: Any?
    ): Any? {
        if (group == null || location == null) return null
        val provider = iconManagerFactory ?: return null
        return runCatching {
            provider.asResolver().optional(true).firstMethodOrNull {
                name = "create"
                parameterCount = 3
            }?.invoke(group, location, dispatcher ?: darkIconDispatcherRef)
        }.getOrNull()
    }

    override fun onInit() {
        updateSelfState(leftContainerMode != 0)
    }

    override fun onHook() {
        val metUpdateLayoutFrom = clzMiuiStatusIconContainer?.resolve()?.firstMethodOrNull {
            name = "updateLayoutFrom"
        }?.toTyped<Unit>()
        val metSetNeedLimitIcon = clzMiuiStatusIconContainer?.resolve()?.firstMethodOrNull {
            name = "setNeedLimitIcon"
        }?.toTyped<Unit>()
        val metSetIslandController = clzMiuiStatusIconContainer?.resolve()?.firstMethodOrNull {
            name = "setIslandController"
        }?.toTyped<Unit>()
        val metSetIgnoredSlots = clzMiuiStatusIconContainer?.resolve()?.firstMethodOrNull {
            name = "setIgnoredSlots"
        }?.toTyped<Unit>()
        val metSetAnimatable = clzMiuiStatusIconContainer?.resolve()?.firstMethodOrNull {
            name = "setAnimatable"
        }?.toTyped<Unit>()
        val metSetAnimatorController = clzMiuiStatusIconContainer?.resolve()?.firstMethodOrNull {
            name = "setAnimatorController"
        }?.toTyped<Unit>()
        val fldAnimatable = clzMiuiStatusIconContainer?.resolve()?.firstFieldOrNull {
            name = "animatable"
        }?.toTyped<Any>()
        val fldAnimatorController = clzMiuiStatusIconContainer?.resolve()?.firstFieldOrNull {
            name = "animatorController"
        }?.toTyped<Any>()
        // The host builds the home DarkIconManager inside this synthetic lambda, which also holds
        // the Dagger provider (f$4), the dark icon dispatcher (f$5) and the icon controller (f$6).
        // Capturing all three here avoids reaching for TintedIconManager.Factory, whose
        // createMiuiIconManager() returns a MiuiLightDarkIconManager -- that variant relies on an
        // external setLight() call that never happens for the home bar, so it never inverts.
        val clzDarkIconManager = "com.android.systemui.statusbar.phone.ui.DarkIconManager".toClassOrNull()
        val clzStatusBarRootLambda = (
            "com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootKt" +
                "\$\$ExternalSyntheticLambda3"
            ).toClassOrNull()
        clzStatusBarRootLambda?.apply {
            val fldProvider = resolve().optional(true).firstFieldOrNull { name = "f\$4" }?.toTyped<Any>()
            val fldDispatcher = resolve().optional(true).firstFieldOrNull { name = "f\$5" }?.toTyped<Any>()
            val fldController = resolve().optional(true).firstFieldOrNull { name = "f\$6" }?.toTyped<Any>()
            resolve().optional(true).firstMethodOrNull {
                name = "invoke"
                parameterCount = 1
            }?.hook {
                fldProvider?.get(thisObject)?.let { iconManagerFactory = it }
                fldDispatcher?.get(thisObject)?.let { darkIconDispatcherRef = it }
                fldController?.get(thisObject)?.let { statusBarIconControllerRef = it }
                result(proceed())
            }
        }
        val clzStatusBarIconControllerImpl = "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl".toClassOrNull()
        // Fallback in case the lambda above is restructured: the controller is a singleton.
        clzStatusBarIconControllerImpl?.resolve()?.optional(true)?.firstConstructorOrNull()?.hook {
            val ori = proceed()
            if (statusBarIconControllerRef == null) statusBarIconControllerRef = thisObject
            result(ori)
        }
        val metAddIconGroup = clzStatusBarIconControllerImpl?.resolve()?.firstMethodOrNull {
            name = "addIconGroup"
            parameterCount = 1
        }?.toTyped<Unit>()
        val fldStatusBarIconList = clzStatusBarIconControllerImpl?.resolve()?.firstFieldOrNull {
            name = "mStatusBarIconList"
        }?.toTyped<Any>()
        val fldSlots = "com.android.systemui.statusbar.phone.ui.StatusBarIconList".toClassOrNull()?.let {
            it.resolve().firstFieldOrNull {
                name = "mSlots"
            }?.toTyped<List<*>>()
        }
        val fldSlotName = $$"com.android.systemui.statusbar.phone.ui.StatusBarIconList$Slot".toClassOrNull()?.let {
            it.resolve().firstFieldOrNull {
                name = "mName"
            }?.toTyped<String>()
        }
        val enumValueOf = "com.android.systemui.statusbar.phone.StatusBarLocation".toClassOrNull()?.let {
            it.resolve().firstMethodOrNull {
                name = "valueOf"
                parameters(String::class)
                modifiers(Modifiers.STATIC)
            }
        }
        val enumStatusBarLocationHome = enumValueOf?.invoke("HOME")
        val enumStatusBarLocationKeyguard = enumValueOf?.invoke("KEYGUARD")
        val enumValueOf2 =
            $$"com.android.systemui.statusbar.anim.MiuiStatusBarIconAnimatorController$StateTransition".toClassOrNull()?.let {
                it.resolve().firstMethodOrNull {
                    name = "valueOf"
                    parameters(String::class)
                    modifiers(Modifiers.STATIC)
                }
            }
        val enumStateTransitionIslandHide = enumValueOf2?.invoke("ISLAND_HIDE")
        val enumStateTransitionIslandShow = enumValueOf2?.invoke("ISLAND_SHOW")
        // 状态栏
        // OS4 removed MiuiCollapsedStatusBarFragment; the same state now lives on
        // HomeStatusBarViewBinderInjector, which HomeStatusBarViewBinderImpl.bind() populates.
        // The icon controller and the TintedIconManager factory are no longer reachable from the
        // owner, so both are captured from their constructors instead.
        val clzInjector = "com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderInjector".toClassOrNull()
            ?: "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment".toClassOrNull()
        clzInjector?.apply {
            val fldStatusBar = resolve().optional(true).firstFieldOrNull {
                name = "mStatusBar"
                superclass()
            }?.toTyped<FrameLayout>()
            val fldDarkIconDispatcher = resolve().optional(true).firstFieldOrNull {
                name = "darkIconDispatcher"
                superclass()
            }?.toTyped<Any>()
            val fldStatusContainer = resolve().optional(true).firstFieldOrNull {
                name = "mStatusContainer"
                superclass()
            }?.toTyped<Any>()
            val fldNotificationIconAreaInner = resolve().optional(true).firstFieldOrNull {
                name = "mNotificationIconAreaInner"
                superclass()
            }?.toTyped<Any>()
            val metCancelAnimate = resolve().optional(true).firstMethodOrNull {
                name = "cancelAnimate"
                parameters(View::class)
                superclass()
            }?.toTyped<Unit>()
            // animateHiddenState(int, View, boolean, boolean) -> animateHide(View, boolean)
            val metAnimateHiddenState = (resolve().optional(true).firstMethodOrNull {
                name = "animateHide"
                parameters(View::class, Boolean::class)
                superclass()
            } ?: resolve().optional(true).firstMethodOrNull {
                name = "animateHiddenState"
                parameters(Int::class, View::class, Boolean::class, Boolean::class)
                superclass()
            })?.self?.apply { makeAccessible() }
            // animateShow(View, boolean, boolean) -> animateShow(View, boolean)
            val metAnimateShow = (resolve().optional(true).firstMethodOrNull {
                name = "animateShow"
                parameters(View::class, Boolean::class)
                superclass()
            } ?: resolve().optional(true).firstMethodOrNull {
                name = "animateShow"
                parameters(View::class, Boolean::class, Boolean::class)
                superclass()
            })?.self?.apply { makeAccessible() }
            // Index of the View parameter differs between the two shapes.
            val hideViewArgIndex = if (metAnimateHiddenState?.parameterCount == 2) 0 else 1
            val setUpLeftContainer = { injector: Any ->
                val mStatusBar = fldStatusBar?.get(injector)
                val leftStatusIcons = mStatusBar?.let {
                    getOrPutStatusIconContainer(it, it.context, true)
                }
                if (mStatusBar != null && leftStatusIcons != null) {
                    if (leftStatusIcons.parent == null) {
                        mStatusBar.findViewById<ViewGroup>(notification_icon_area)?.let { notificationContainer ->
                            (notificationContainer.parent as? ViewGroup)?.apply {
                                addView(
                                    leftStatusIcons,
                                    indexOfChild(notificationContainer),
                                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                                )
                            }
                        }
                    }
                    val darkIconDispatcher = fldDarkIconDispatcher?.get(injector)
                    // Build a DarkIconManager for our container the same way the host builds the
                    // one for statusIcons: it registers every icon view with the dispatcher in
                    // onIconAdded, which is what makes them follow the background.
                    val darkIconManager = createDarkIconManager(
                        clzDarkIconManager,
                        leftStatusIcons,
                        enumStatusBarLocationHome,
                        darkIconDispatcher
                    )
                    val statusBarIconController = statusBarIconControllerRef
                    if (darkIconManager != null && statusBarIconController != null) {
                        metAddIconGroup?.invoke(statusBarIconController, darkIconManager)
                        val blockList = fldStatusBarIconList?.get(statusBarIconController)?.let { controller ->
                            fldSlots?.get(controller)?.let { slots ->
                                slots.mapNotNull { slot ->
                                    fldSlotName?.get(slot)
                                }
                            }
                        }?.let {
                            getLeftBlockList(it)
                        } ?: leftBlockList
                        metSetIgnoredSlots?.invoke(leftStatusIcons, blockList)
                        fldStatusContainer?.get(injector)?.let { container ->
                            fldAnimatable?.get(container)?.let {
                                metSetAnimatable?.invoke(leftStatusIcons, it)
                            }
                            fldAnimatorController?.get(container)?.let {
                                metSetAnimatorController?.invoke(leftStatusIcons, it)
                            }
                        }
                    }
                }
            }
            // onViewCreated -> HomeStatusBarViewBinderImpl.bind, which owns the injector instance.
            val clzBinderImpl = "com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl".toClassOrNull()
            val fldInjector = clzBinderImpl?.resolve()?.optional(true)?.firstFieldOrNull {
                name = "mInjector"
            }?.toTyped<Any>()
            if (clzBinderImpl != null && fldInjector != null) {
                clzBinderImpl.resolve().optional(true).firstMethodOrNull {
                    name = "bind"
                    parameterCount = 4
                }?.hook {
                    val ori = proceed()
                    fldInjector.get(thisObject)?.let { setUpLeftContainer(it) }
                    result(ori)
                }
            } else {
                resolve().optional(true).firstMethodOrNull {
                    name = "onViewCreated"
                }?.hook {
                    val ori = proceed()
                    setUpLeftContainer(thisObject)
                    result(ori)
                }
            }
            metAnimateShow?.hook {
                val ori = proceed()
                val mNotificationIconAreaInner = fldNotificationIconAreaInner?.get(thisObject)
                if (mNotificationIconAreaInner != null && getArg(0) as? View == mNotificationIconAreaInner) {
                    val mStatusBar = fldStatusBar?.get(thisObject)
                    val leftStatusIcons = mStatusBar?.let {
                        getOrPutStatusIconContainer(it, it.context, true)
                    }
                    val newArgs = args.toTypedArray()
                    newArgs[0] = leftStatusIcons
                    module.getInvoker(metAnimateShow).setType(XposedInterface.Invoker.Type.ORIGIN)
                        .invoke(thisObject, *newArgs)
                }
                result(ori)
            }
            metAnimateHiddenState?.hook {
                val ori = proceed()
                val mNotificationIconAreaInner = fldNotificationIconAreaInner?.get(thisObject)
                if (mNotificationIconAreaInner != null && getArg(hideViewArgIndex) as? View == mNotificationIconAreaInner) {
                    val mStatusBar = fldStatusBar?.get(thisObject)
                    val leftStatusIcons = mStatusBar?.let {
                        getOrPutStatusIconContainer(it, it.context, true)
                    }
                    val newArgs = args.toTypedArray()
                    newArgs[hideViewArgIndex] = leftStatusIcons
                    module.getInvoker(metAnimateHiddenState).setType(XposedInterface.Invoker.Type.ORIGIN)
                        .invoke(thisObject, *newArgs)
                }
                result(ori)
            }
            resolve().optional(true).firstMethodOrNull {
                name { it == "onUnbind" || it == "onDestroyView" }
            }?.hook {
                val ori = proceed()
                val mStatusBar = fldStatusBar?.get(thisObject)
                val leftStatusIcons = mStatusBar?.let {
                    getOrPutStatusIconContainer(it, it.context, true)
                }
                if (leftStatusIcons != null) {
                    metCancelAnimate?.invoke(thisObject, leftStatusIcons)
                }
                leftContainers.clear()
                result(ori)
            }
        }
        "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView".toClassOrNull()?.apply {
            resolve().firstMethodOrNull {
                name = "onAttachedToWindow"
            }?.hook {
                val ori = proceed()
                val view = thisObject as? View
                val leftStatusIcons = view?.let {
                    getOrPutStatusIconContainer(it, it.context, true)
                }
                if (leftStatusIcons != null) {
                    metUpdateLayoutFrom?.invoke(leftStatusIcons, 0)
                    metSetNeedLimitIcon?.invoke(leftStatusIcons, true)
                }
                result(ori)
            }
        }
        $$"com.android.systemui.statusbar.StatusBarIslandControllerImpl$IslandStateHandler".toClassOrNull()?.apply {
            val fldIslandRect = resolve().firstFieldOrNull {
                name = "islandRect"
            }?.toTyped<Rect>()
            val fldIslandShowing = resolve().firstFieldOrNull {
                name = "islandShowing"
            }?.toTyped<Boolean>()
            resolve().firstMethodOrNull {
                name = "islandUpdate"
            }?.hook {
                val ori = proceed()
                val islandRect = fldIslandRect?.get(thisObject)
                val islandShowing = fldIslandShowing?.get(thisObject) ?: false
                if (islandRect != null) {
                    leftContainers.forEach { viewGroup ->
                        viewGroup.islandRect = islandRect
                        viewGroup.islandShowing = islandShowing
                        viewGroup.requestLayout()
                    }
                }
                result(ori)
            }
        }
        clzMiuiStatusIconContainer?.apply {
            val fldAnimatorController = resolve().firstFieldOrNull {
                name = "animatorController"
            }?.toTyped<Any>()
            val clzStatusIconDisplayable = "com.android.systemui.statusbar.StatusIconDisplayable".toClassOrNull()
            val metIsIconVisible = clzStatusIconDisplayable?.resolve()?.firstMethodOrNull {
                name = "isIconVisible"
            }?.toTyped<Boolean>()
            val metGetRemoveFlag = clzStatusIconDisplayable?.resolve()?.firstMethodOrNull {
                name = "getRemoveFlag"
            }?.toTyped<Boolean>()
            val metSetVisibleState = clzStatusIconDisplayable?.resolve()?.firstMethodOrNull {
                name = "setVisibleState"
                parameters(Int::class, Boolean::class)
            }?.toTyped<Unit>()
            val clzNewStatusIconState = "com.android.systemui.statusbar.views.NewStatusIconState".toClassOrNull()
            val fldLayoutTranslationX = clzNewStatusIconState?.resolve()?.firstFieldOrNull {
                name = "layoutTranslationX"
            }?.toTyped<Float>()
            val fldVisibleState = clzNewStatusIconState?.resolve()?.firstFieldOrNull {
                name = "visibleState"
            }?.toTyped<Int>()
            val fldInIslandState = clzNewStatusIconState?.resolve()?.firstFieldOrNull {
                name = "inIslandState"
            }?.toTyped<Int>()
            val metAnimateTo = clzNewStatusIconState?.resolve()?.firstMethodOrNull {
                name = "animateTo"
                superclass()
            }?.toTyped<Unit>()
            val metCreateFolmeAnimation = "com.android.systemui.statusbar.anim.MiuiStatusBarIconAnimatorController".toClassOrNull()
                ?.resolve()?.firstMethodOrNull {
                    name = "createFolmeAnimation"
                    parameterCount = 3
                }?.toTyped<Any>()
            resolve().firstMethodOrNull {
                name = "onLayout"
            }?.hook {
                val ori = proceed()
                val container = thisObject as? ViewGroup
                if (container == null || container !in leftContainers) return@hook result(ori)
                val animController = fldAnimatorController?.get(container)
                val islandRect = container.islandRect ?: return@hook result(ori)
                val islandShowing = container.islandShowing ?: false
                val containerLoc = IntArray(2)
                container.getLocationOnScreen(containerLoc)
                val islandPadding = 2.dp(container.context)
                for (i in (container.childCount - 1) downTo 0) {
                    val iconView = container.getChildAt(i)
                    val viewState = iconView.getTag(status_bar_view_state_tag) ?: continue
                    val isVisible = metIsIconVisible?.invoke(iconView) ?: false
                    val removeFlag = metGetRemoveFlag?.invoke(iconView) ?: false

                    if (iconView.isGone || !isVisible || removeFlag) {
                        continue
                    }

                    val layoutTx = fldLayoutTranslationX?.get(viewState) ?: 0.0f
                    val iconLeftAbsolute = containerLoc[0] + layoutTx
                    val iconRightAbsolute = iconLeftAbsolute + iconView.width
                    val isColliding = islandShowing && !islandRect.isEmpty &&
                            iconRightAbsolute > (islandRect.left - islandPadding) &&
                            iconLeftAbsolute < (islandRect.right + islandPadding)

                    if (isColliding) {
                        val currentState = fldVisibleState?.get(viewState)
                        if (currentState != null && currentState != 2) {
                            fldVisibleState.set(viewState, 2) // 2 = 隐藏
                            fldInIslandState?.set(viewState, 10) // 10 = 在岛下方
                            val animProps = metCreateFolmeAnimation?.invoke(
                                animController,
                                enumStateTransitionIslandShow,
                                iconView,
                                viewState
                            )
                            metAnimateTo?.invoke(viewState, iconView, animProps)
                            metSetVisibleState?.invoke(iconView, 2, false)
                        }
                    } else {
                        val currentState = fldVisibleState?.get(viewState)
                        if (currentState == 2) {
                            fldVisibleState.set(viewState, 0) // 0 = 可见
                            fldInIslandState?.set(viewState, 20) // 20 = 正常
                            val animProps = metCreateFolmeAnimation?.invoke(
                                animController,
                                enumStateTransitionIslandHide,
                                iconView,
                                viewState
                            )
                            metAnimateTo?.invoke(viewState, iconView, animProps)
                            metSetVisibleState?.invoke(iconView, 0, false)
                        }
                    }
                }
                result(ori)
            }
        }
        // 锁屏
        if (leftContainerMode != 2) return
        "com.android.systemui.statusbar.phone.KeyguardStatusBarViewController".toClassOrNull()?.apply {
            val fldView = resolve().firstFieldOrNull {
                name = "mView"
                superclass()
            }?.toTyped<ViewGroup>()
            val fldCarrier = clzMiuiKeyguardStatusBarView?.resolve()?.firstFieldOrNull {
                name {
                    it.startsWith("mCarrier")
                }
                type { it isSubclassOf classOf<View>() }
            }?.toTyped<View>()
            val fldAlarmLayout = clzMiuiKeyguardStatusBarView?.resolve()?.firstFieldOrNull {
                name = "mAlarmLayout"
                type { it isSubclassOf classOf<View>() }
            }?.toTyped<View>()
            val fldLightLockScreenWallpaper = clzMiuiKeyguardStatusBarView?.resolve()?.firstFieldOrNull {
                name = "mLightLockScreenWallpaper"
            }?.toTyped<Boolean>()
            val fldDep = clzMiuiKeyguardStatusBarView?.resolve()?.firstFieldOrNull {
                name = "mDep"
            }?.toTyped<Any>()
            val clzKeyguardStatusBarViewControllerInject = "com.android.systemui.statusbar.phone.KeyguardStatusBarViewControllerInject".toClassOrNull()
            val fldIconDispatcher = clzKeyguardStatusBarViewControllerInject?.resolve()?.firstFieldOrNull {
                name = "iconDispatcher"
            }?.toTyped<Any>()
            val fldIslandController = clzKeyguardStatusBarViewControllerInject?.resolve()?.firstFieldOrNull {
                name = "islandController"
            }?.toTyped<Any>()
            val metGetLightModeIconColorSingleTone = "com.android.systemui.plugins.DarkIconDispatcher".toClassOrNull()
                ?.resolve()?.firstMethodOrNull {
                    name = "getLightModeIconColorSingleTone"
                }?.toTyped<Int>()
            val fldIconManagerFactory = clzMiuiKeyguardStatusBarView?.resolve()?.firstFieldOrNull {
                name = "mIconManagerFactory"
            }?.toTyped<Any>()
            val metCreateMiuiIconManager = $$"com.android.systemui.statusbar.phone.ui.TintedIconManager$Factory".toClassOrNull()
                ?.resolve()?.firstMethodOrNull {
                    name = "createMiuiIconManager"
                    parameterCount = 4
                }?.toTyped<Any>()
            val fldIconController = clzMiuiKeyguardStatusBarView?.resolve()?.firstFieldOrNull {
                name = "mIconController"
            }?.toTyped<Any>()
            resolve().firstMethodOrNull {
                name = "onViewAttached"
            }?.hook {
                val ori = proceed()
                val miuiKeyguardStatusBarView = fldView?.get(thisObject) ?: return@hook result(ori)
                val leftStatusIcons = getOrPutStatusIconContainer(miuiKeyguardStatusBarView, miuiKeyguardStatusBarView.context, false) ?: return@hook result(ori)
                val carrier = fldCarrier?.get(miuiKeyguardStatusBarView)
                val alarmLayout = fldAlarmLayout?.get(miuiKeyguardStatusBarView)
                val parent = carrier?.parent as? ViewGroup
                if (parent != null) {
                    val alarmInSameParent = alarmLayout?.parent == parent
                    val leftStatusIconHost = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_INHERIT
                        val params = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        layoutParams = params
                    }
                    val position = if (alarmInSameParent) {
                        minOf(parent.indexOfChild(carrier), parent.indexOfChild(alarmLayout))
                    } else {
                        parent.indexOfChild(carrier)
                    }
                    parent.apply {
                        removeView(carrier)
                        if (alarmInSameParent) removeView(alarmLayout)
                        leftStatusIconHost.addView(carrier)
                        if (alarmInSameParent) leftStatusIconHost.addView(alarmLayout)
                        leftStatusIconHost.addView(leftStatusIcons)
                        addView(leftStatusIconHost, position)
                    }
                    miuiKeyguardStatusBarView.leftStatusIconHost = leftStatusIconHost
                    updateKeyguardLeftStatusIconHostVisibility(
                        miuiKeyguardStatusBarView,
                        carrier,
                        alarmLayout
                    )
                }
                val mLightLockScreenWallpaper = fldLightLockScreenWallpaper?.get(miuiKeyguardStatusBarView) ?: false
                val mDep = fldDep?.get(miuiKeyguardStatusBarView)
                val lightModeIconColorSingleTone = fldIconDispatcher?.get(mDep)?.let { iconDispatcher ->
                    metGetLightModeIconColorSingleTone?.invoke(iconDispatcher)
                }
                val createMiuiIconManager = fldIconManagerFactory?.get(miuiKeyguardStatusBarView)?.let { iconFactory ->
                    metCreateMiuiIconManager?.invoke(
                        iconFactory,
                        leftStatusIcons,
                        enumStatusBarLocationKeyguard,
                        mLightLockScreenWallpaper,
                        lightModeIconColorSingleTone
                    )
                }
                miuiKeyguardStatusBarView.leftStatusIconManager = createMiuiIconManager
                val statusBarIconController = fldIconController?.get(miuiKeyguardStatusBarView) ?: return@hook result(ori)
                metAddIconGroup?.invoke(statusBarIconController, createMiuiIconManager)
                val blockList = fldStatusBarIconList?.get(statusBarIconController)?.let { controller ->
                    fldSlots?.get(controller)?.let { slots ->
                        slots.mapNotNull { slot ->
                            fldSlotName?.get(slot)
                        }
                    }
                }?.let {
                    getLeftBlockList(it)
                } ?: leftBlockList
                metSetIgnoredSlots?.invoke(leftStatusIcons, blockList)
                metUpdateLayoutFrom?.invoke(leftStatusIcons, 1)
                val islandController = mDep?.let { fldIslandController?.get(it) }
                metSetIslandController?.invoke(
                    leftStatusIcons,
                    islandController,
                    1
                )
                result(ori)
            }
        }
        clzMiuiKeyguardStatusBarView?.apply {
            val fldInit = resolve().optional(true).firstFieldOrNull {
                name = "mInit"
            }?.toTyped<Boolean>()
            val fldStatusIconContainer = resolve().optional(true).firstFieldOrNull {
                name = "mStatusIconContainer"
                superclass()
            }?.toTyped<Any>()
            val fldCarrier = clzMiuiKeyguardStatusBarView?.resolve()?.firstFieldOrNull {
                name {
                    it.startsWith("mCarrier")
                }
                type { it isSubclassOf classOf<View>() }
            }?.toTyped<View>()
            val fldAlarmLayout = resolve().optional(true).firstFieldOrNull {
                name = "mAlarmLayout"
                type { it isSubclassOf classOf<View>() }
            }?.toTyped<View>()
            var hookInit = false
            resolve().firstMethodOrNull {
                name = "initCallback"
            }?.hook {
                val ori = proceed()
                val view = thisObject as? View
                if (!hookInit && view != null && fldInit?.get(thisObject) == true) {
                    val leftStatusIconContainer = getOrPutStatusIconContainer(view, view.context, false) ?: return@hook result(ori)
                    metSetNeedLimitIcon?.invoke(leftStatusIconContainer, true)
                    fldStatusIconContainer?.get(thisObject)?.let { container ->
                        fldAnimatable?.get(container)?.let {
                            metSetAnimatable?.invoke(leftStatusIconContainer, it)
                        }
                        fldAnimatorController?.get(container)?.let {
                            metSetAnimatorController?.invoke(leftStatusIconContainer, it)
                        }
                    }
                    hookInit = true
                }
                result(ori)
            }
            resolve().firstMethodOrNull {
                name {
                    it.startsWith("updateCarrierVisibility")
                }
            }?.hook {
                val ori = proceed()
                val view = thisObject as? View
                if (view != null) {
                    updateKeyguardLeftStatusIconHostVisibility(
                        view,
                        fldCarrier?.get(thisObject),
                        fldAlarmLayout?.get(thisObject)
                    )
                }
                result(ori)
            }
            resolve().firstMethodOrNull {
                name = "showNextAlarm"
            }?.hook {
                val ori = proceed()
                val view = thisObject as? View
                if (view != null) {
                    updateKeyguardLeftStatusIconHostVisibility(
                        view,
                        fldCarrier?.get(thisObject),
                        fldAlarmLayout?.get(thisObject)
                    )
                }
                result(ori)
            }
            val fldTintedIconManager = resolve().firstFieldOrNull {
                name = "mTintedIconManager"
                superclass()
            }?.toTyped<Any>()
            val clzMiuiLightDarkIconManager = "com.android.systemui.statusbar.phone.MiuiLightDarkIconManager".toClassOrNull()
            val metSetLight = clzMiuiLightDarkIconManager?.resolve()?.firstMethodOrNull {
                name = "setLight"
                parameters(Int::class, Boolean::class, Boolean::class)
            }?.toTyped<Unit>()
            val fldColor = clzMiuiLightDarkIconManager?.resolve()?.firstFieldOrNull {
                name = "mColor"
                type(Int::class)
            }?.toTyped<Int>()
            val fldLight = clzMiuiLightDarkIconManager?.resolve()?.firstFieldOrNull {
                name = "mLight"
                type(Boolean::class)
            }?.toTyped<Boolean>()
            resolve().firstMethodOrNull {
                name = "updateIconsAndTextColors"
            }?.hook {
                val ori = proceed()
                val view = thisObject as? View
                val leftStatusIconManager = view?.leftStatusIconManager
                val mTintedIconManager = fldTintedIconManager?.get(thisObject)
                val mColor = mTintedIconManager?.let { fldColor?.get(it) }
                val mLight = mTintedIconManager?.let { fldLight?.get(it) }
                if (leftStatusIconManager != null && mColor != null && mLight != null) {
                    metSetLight?.invoke(leftStatusIconManager, mColor, mLight, false)
                }
                result(ori)
            }
        }
    }

    private fun getOrPutStatusIconContainer(obj: Any, context: Context, remember: Boolean): ViewGroup? {
        obj.leftStatusIconContainer?.let {
            return it
        }
        val container = ctorMiuiStatusIconContainer?.newInstance(context) as? LinearLayout
        if (container != null) {
            obj.leftStatusIconContainer = container
            if (remember) leftContainers.add(container)
        }
        return container
    }

    private fun updateKeyguardLeftStatusIconHostVisibility(
        keyguardStatusBarView: View,
        carrier: View?,
        alarmLayout: View?
    ) {
        val host = keyguardStatusBarView.leftStatusIconHost ?: return
        val alarmInHost = alarmLayout?.takeIf { it.parent == host }
        host.visibility = when {
            carrier?.visibility == View.VISIBLE || alarmInHost?.visibility == View.VISIBLE -> View.VISIBLE
            carrier?.visibility == View.INVISIBLE || alarmInHost?.visibility == View.INVISIBLE -> View.INVISIBLE
            else -> View.GONE
        }
    }
}
