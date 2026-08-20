/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of XiaomiHelper project
 * Copyright (C) 2025 HowieHChen, howie.dev@outlook.com

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.lackluster.mihelper.hook.rules.systemui.statusbar

import android.widget.TextView
import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import dev.lackluster.mihelper.hook.utils.d
import dev.lackluster.mihelper.hook.utils.toTyped
import kotlin.math.ceil

/**
 * The dynamic island already sizes itself around the status bar clock: DynamicIslandController
 * computes `min(screenW, screenH) - max(batteryWidth, clockWidth) * 2` and ships it to the plugin
 * as `extra_island_max_width`.
 *
 * The catch is how clockWidth is produced. HomeStatusBarViewBinderInjector's clock space provider
 * ends its sum with `ceil(clockPaint.measureText("00:00"))` — a hardcoded literal. Once the clock
 * shows a date, seconds or AM/PM it is far wider than "00:00", yet the island still budgets for
 * five glyphs and happily overlaps the clock.
 *
 * Rather than reimplement the sizing, report the truth: add back whatever the real clock text
 * exceeds "00:00" by. The delta is clamped at zero so this can only ever shrink the island, never
 * grant it more room than stock would have.
 */
object IslandClockSpace : StaticHooker() {
    private const val ASSUMED_TEXT = "00:00"

    override fun onInit() {
        updateSelfState(
            Preferences.SystemUI.StatusBar.Clock.ENABLE_GEEK_MODE.get() ||
                Preferences.SystemUI.StatusBar.Clock.EASY_SHOW_SECONDS.get() ||
                Preferences.SystemUI.StatusBar.Clock.EASY_SHOW_AMPM.get()
        )
    }

    override fun onHook() {
        "com.android.systemui.statusbar.StatusBarIslandControllerImpl".toClassOrNull()?.apply {
            val fldClockSpaceProvider = resolve().optional(true).firstFieldOrNull {
                name = "clockSpaceProvider"
            }?.toTyped<Any>()
            val fldProviderOuter = "com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderInjector\$clockLeftProvider\$1"
                .toClassOrNull()?.resolve()?.optional(true)?.firstFieldOrNull {
                    name = "this\$0"
                }?.toTyped<Any>()
            val fldClockView = "com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderInjector"
                .toClassOrNull()?.resolve()?.optional(true)?.firstFieldOrNull {
                    name = "mClockView"
                }?.toTyped<TextView>()

            resolve().optional(true).firstMethodOrNull {
                name = "getClockWidth"
                emptyParameters()
            }?.hook {
                val ori = proceed() as? Int ?: return@hook result(proceed())
                val provider = fldClockSpaceProvider?.get(thisObject) ?: return@hook result(ori)
                val injector = fldProviderOuter?.get(provider) ?: return@hook result(ori)
                val clockView = fldClockView?.get(injector) ?: return@hook result(ori)
                val paint = clockView.paint ?: return@hook result(ori)
                val text = clockView.text?.toString()
                if (text.isNullOrEmpty()) return@hook result(ori)

                val realWidth = ceil(paint.measureText(text)).toInt()
                val assumedWidth = ceil(paint.measureText(ASSUMED_TEXT)).toInt()
                val delta = realWidth - assumedWidth
                if (delta <= 0) return@hook result(ori)

                d { "getClockWidth: ori=$ori text=\"$text\" real=$realWidth assumed=$assumedWidth -> ${ori + delta}" }
                result(ori + delta)
            }
        }
    }
}
