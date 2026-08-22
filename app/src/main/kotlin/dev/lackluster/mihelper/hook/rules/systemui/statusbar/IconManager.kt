/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of XiaomiHelper project
 * Copyright (C) 2023 HowieHChen, howie.dev@outlook.com

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

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.makeAccessible
import dev.lackluster.hyperx.ui.preference.core.PreferenceKey
import dev.lackluster.mihelper.data.Constants
import dev.lackluster.mihelper.data.Constants.COMPOUND_ICON_REAL_SLOTS
import dev.lackluster.mihelper.data.Constants.IconSlots
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import dev.lackluster.mihelper.hook.utils.RemotePreferences.lazyGet
import dev.lackluster.mihelper.hook.utils.TypedField
import dev.lackluster.mihelper.hook.utils.toTyped
import kotlin.collections.listOf

object IconManager : StaticHooker() {
    private val iconPositionMode by Preferences.SystemUI.StatusBar.IconTuner.ICON_POSITION.lazyGet()
    private val iconPositionAutoReorder by Preferences.SystemUI.StatusBar.IconTuner.ICON_POSITION_REORDER.lazyGet()
    private val addStackedMobile by Preferences.SystemUI.StatusBar.StackedMobile.ENABLED.lazyGet()
    private val addCustomWifiSignal by Preferences.SystemUI.StatusBar.CustomWifi.ENABLED.lazyGet()
    private val addCompoundIcon by lazy {
        Preferences.SystemUI.StatusBar.IconTuner.COMPOUND_ICON.get() in 1..3
    }
    private val leftContainer by lazy {
        Preferences.SystemUI.StatusBar.IconTuner.LEFT_CONTAINER.get() != 0
    }
    private val leftCompoundIcon by Preferences.SystemUI.StatusBar.IconTuner.LEFT_COMPOUND_ICON.lazyGet()
    private val leftLocation by Preferences.SystemUI.StatusBar.IconTuner.LEFT_LOCATION.lazyGet()
    private val leftAlarmClock by Preferences.SystemUI.StatusBar.IconTuner.LEFT_ALARM_CLOCK.lazyGet()
    private val leftZen by Preferences.SystemUI.StatusBar.IconTuner.LEFT_ZEN.lazyGet()
    private val leftVolume by Preferences.SystemUI.StatusBar.IconTuner.LEFT_VOLUME.lazyGet()
    private val leftExtraBlockedSlots by Preferences.SystemUI.StatusBar.IconTuner.LEFT_EXT_BLOCK_LIST.lazyGet()

    private val slotsCustom by lazy {
        Preferences.SystemUI.StatusBar.IconTuner.ICON_POSITION_VAL.get().mapNotNull { str ->
            str.split(":").takeIf { it.size == 2 }
        }.sortedBy {
            it[0].toInt()
        }.map {
            it[1]
        }.takeIf {
            it.isNotEmpty()
        } ?: Constants.STATUS_BAR_ICONS_DEFAULT
    }
    private val finalSlots by lazy {
        when (iconPositionMode) {
            1 -> Constants.STATUS_BAR_ICONS_SWAP
            2 -> slotsCustom
            else -> Constants.STATUS_BAR_ICONS_DEFAULT
        }.let { array ->
            val slotsList = array.toMutableList()
            if (addCompoundIcon && !slotsList.contains(IconSlots.COMPOUND_ICON_STUB)) {
                slotsList.add(
                    slotsList.indexOf(IconSlots.ZEN),
                    IconSlots.COMPOUND_ICON_STUB
                )
            }
            if (addStackedMobile) {
                if (!slotsList.contains(IconSlots.STACKED_MOBILE_ICON)) {
                    slotsList.addAll(
                        slotsList.indexOf("mobile"),
                        listOf(
                            IconSlots.STACKED_MOBILE_TYPE,
                            IconSlots.STACKED_MOBILE_ICON,
                            IconSlots.SINGLE_MOBILE_SIM1,
                            IconSlots.SINGLE_MOBILE_SIM2,
                        )
                    )
                }
            }
            if (addCustomWifiSignal && !slotsList.contains(IconSlots.CUSTOM_WIFI_SIGNAL)) {
                val wifiIndex = slotsList.indexOf("wifi")
                if (wifiIndex >= 0) {
                    slotsList.add(wifiIndex + 1, IconSlots.CUSTOM_WIFI_SIGNAL)
                }
            }
            if (leftContainer) {
                slotsList.sortByDescending { it in leftSlots }
            }
            if (addCompoundIcon) {
                slotsList.addAll(
                    slotsList.indexOf(IconSlots.COMPOUND_ICON_STUB),
                    COMPOUND_ICON_REAL_SLOTS
                )
                slotsList.remove(IconSlots.COMPOUND_ICON_STUB)
            }
            slotsList.toTypedArray()
        }
    }
    private val leftSlots by lazy {
        mutableListOf<String>().apply {
            if (leftCompoundIcon) addAll(COMPOUND_ICON_REAL_SLOTS)
            if (leftLocation) add(IconSlots.LOCATION)
            if (leftAlarmClock) add(IconSlots.ALARM_CLOCK)
            if (leftZen) add(IconSlots.ZEN)
            if (leftVolume) add(IconSlots.VOLUME)
        }.toList()
    }
    val leftBlockList by lazy {
        getLeftBlockList(finalSlots.toList())
    }

    override fun onInit() {
        updateSelfState(true)
        if (leftContainer) {
            attach(LeftContainer)
        }
    }

    override fun onHook() {
        val clzMiuiIconManagerUtils = "com.android.systemui.statusbar.phone.MiuiIconManagerUtils".toClassOrNull()
        val fldRightBlockList = clzMiuiIconManagerUtils?.resolve()?.firstFieldOrNull {
            name = "RIGHT_BLOCK_LIST"
            modifiers(Modifiers.STATIC)
        }?.toTyped<List<String>>()
        val fldControlCenterBlockList = clzMiuiIconManagerUtils?.resolve()?.firstFieldOrNull {
            name = "CONTROL_CENTER_BLOCK_LIST"
            modifiers(Modifiers.STATIC)
        }?.toTyped<List<String>>()
        val statusBarBlockList = fldRightBlockList?.get(null)?.toMutableList() ?: return
        val controlCenterBlockList = fldControlCenterBlockList?.get(null)?.toMutableList() ?: return
        mapOf(
//            "mobile" to IconTuner.MOBILE,
//            "demo_mobile" to IconTuner.MOBILE,
            "no_sim" to Preferences.SystemUI.StatusBar.IconTuner.NO_SIM,
            "airplane" to Preferences.SystemUI.StatusBar.IconTuner.AIRPLANE,
            "wifi" to Preferences.SystemUI.StatusBar.IconTuner.WIFI,
            "demo_wifi" to Preferences.SystemUI.StatusBar.IconTuner.WIFI,
            "hotspot" to Preferences.SystemUI.StatusBar.IconTuner.HOTSPOT,
            "vpn" to Preferences.SystemUI.StatusBar.IconTuner.VPN,
            "network_speed" to Preferences.SystemUI.StatusBar.IconTuner.NET_SPEED,
            "bluetooth" to Preferences.SystemUI.StatusBar.IconTuner.BLUETOOTH,
            "bluetooth_handsfree_battery" to Preferences.SystemUI.StatusBar.IconTuner.BLUETOOTH_BATTERY,
            "handle_battery" to Preferences.SystemUI.StatusBar.IconTuner.HANDLE_BATTERY,
            "nfc" to Preferences.SystemUI.StatusBar.IconTuner.NFC,
            "gps" to Preferences.SystemUI.StatusBar.IconTuner.LOCATION,
            IconSlots.LOCATION to Preferences.SystemUI.StatusBar.IconTuner.LOCATION,
            "wireless_headset" to Preferences.SystemUI.StatusBar.IconTuner.WIRELESS_HEADSET,
            "phone" to Preferences.SystemUI.StatusBar.IconTuner.PHONE,
            "pad" to Preferences.SystemUI.StatusBar.IconTuner.PAD,
            "pc" to Preferences.SystemUI.StatusBar.IconTuner.PC,
            "sound_box_group" to Preferences.SystemUI.StatusBar.IconTuner.SOUND_BOX_GROUP,
            "stereo" to Preferences.SystemUI.StatusBar.IconTuner.STEREO,
            "sound_box_screen" to Preferences.SystemUI.StatusBar.IconTuner.SOUND_BOX_SCREEN,
            "sound_box" to Preferences.SystemUI.StatusBar.IconTuner.SOUND_BOX,
            "tv" to Preferences.SystemUI.StatusBar.IconTuner.TV,
            "glasses" to Preferences.SystemUI.StatusBar.IconTuner.GLASSES,
            "car" to Preferences.SystemUI.StatusBar.IconTuner.CAR,
            "camera" to Preferences.SystemUI.StatusBar.IconTuner.CAMERA,
            "dist_compute" to Preferences.SystemUI.StatusBar.IconTuner.DIST_COMPUTE,
            "headset" to Preferences.SystemUI.StatusBar.IconTuner.HEADSET,
            IconSlots.ALARM_CLOCK to Preferences.SystemUI.StatusBar.IconTuner.ALARM_CLOCK,
            IconSlots.ZEN to Preferences.SystemUI.StatusBar.IconTuner.ZEN,
            IconSlots.VOLUME to Preferences.SystemUI.StatusBar.IconTuner.VOLUME,
            "second_space" to Preferences.SystemUI.StatusBar.IconTuner.SECOND_SPACE,
        ).forEach { (slot, key) ->
            handleIcon(
                key,
                slot,
                statusBarBlockList,
                controlCenterBlockList
            )
        }
        if (addCompoundIcon) {
            mapOf(
                IconSlots.COMPOUND_ICON_REAL_LOCATION to Preferences.SystemUI.StatusBar.IconTuner.COMPOUND_ICON,
                IconSlots.COMPOUND_ICON_REAL_ALARM_CLOCK to Preferences.SystemUI.StatusBar.IconTuner.COMPOUND_ICON,
                IconSlots.COMPOUND_ICON_REAL_ZEN to Preferences.SystemUI.StatusBar.IconTuner.COMPOUND_ICON,
                IconSlots.COMPOUND_ICON_REAL_VIBRATE to Preferences.SystemUI.StatusBar.IconTuner.COMPOUND_ICON,
                IconSlots.COMPOUND_ICON_REAL_MUTE to Preferences.SystemUI.StatusBar.IconTuner.COMPOUND_ICON,
            ).forEach { (slot, key) ->
                handleIcon(
                    key,
                    slot,
                    statusBarBlockList,
                    controlCenterBlockList
                )
            }
        }
        if (addStackedMobile) {
            mapOf(
                IconSlots.STACKED_MOBILE_ICON to Preferences.SystemUI.StatusBar.StackedMobile.STACKED_MOBILE_ICON,
                IconSlots.STACKED_MOBILE_TYPE to Preferences.SystemUI.StatusBar.StackedMobile.STACKED_MOBILE_TYPE,
                IconSlots.SINGLE_MOBILE_SIM1 to Preferences.SystemUI.StatusBar.StackedMobile.SINGLE_MOBILE_SIM1,
                IconSlots.SINGLE_MOBILE_SIM2 to Preferences.SystemUI.StatusBar.StackedMobile.SINGLE_MOBILE_SIM2,
            ).forEach { (slot, key) ->
                handleIcon(
                    key,
                    slot,
                    statusBarBlockList,
                    controlCenterBlockList
                )
            }
        }
        if (addCustomWifiSignal) {
            handleIcon(
                Preferences.SystemUI.StatusBar.CustomWifi.CUSTOM_WIFI_ICON,
                IconSlots.CUSTOM_WIFI_SIGNAL,
                statusBarBlockList,
                controlCenterBlockList
            )
        }
        if (leftContainer) {
            leftSlots.forEach {
                if (!statusBarBlockList.contains(it)) {
                    statusBarBlockList.add(it)
                }
            }
        }
        // OS4 declares these as `static final ArrayList` and hands the very same instance to every
        // icon manager, so reassigning the field is a no-op there. Mutate the live list instead and
        // only fall back to assignment if it turns out not to be mutable.
        applyBlockList(fldRightBlockList, statusBarBlockList)
        applyBlockList(fldControlCenterBlockList, controlCenterBlockList)
        if (iconPositionMode != 0 || addCompoundIcon || addCustomWifiSignal || iconPositionAutoReorder) {
            val desiredOrder: () -> Array<String> = {
                if (iconPositionAutoReorder) {
                    val stackedMobileSlots = listOf(
                        IconSlots.STACKED_MOBILE_TYPE,
                        IconSlots.STACKED_MOBILE_ICON,
                        IconSlots.SINGLE_MOBILE_SIM1,
                        IconSlots.SINGLE_MOBILE_SIM2,
                    )
                    finalSlots.sortedBy {
                        (it !in statusBarBlockList) || (it in stackedMobileSlots)
                    }.toTypedArray()
                } else {
                    finalSlots
                }
            }
            "com.android.systemui.statusbar.phone.ui.StatusBarIconList".toClassOrNull()?.apply {
                resolve().optional(true).firstConstructorOrNull {
                    parameters(Array<String>::class)
                }?.hook {
                    val newArgs = args.toTypedArray()
                    newArgs[0] = desiredOrder()
                    result(proceed(newArgs))
                }
                // Newer ROMs inline that constructor away and build the list inside the Dagger
                // provider instead, so reorder the slots it hands back. mViewOnlySlots wraps the
                // very same ArrayList, which is why this rebuilds in place rather than replacing it.
                val fldSlots = resolve().optional(true).firstFieldOrNull {
                    name = "mSlots"
                }?.toTyped<MutableList<Any>>()
                val ctorSlot = $$"com.android.systemui.statusbar.phone.ui.StatusBarIconList$Slot"
                    .toClassOrNull()?.resolve()?.optional(true)?.firstConstructorOrNull {
                        parameters(String::class)
                    }?.self?.apply { makeAccessible() }
                if (fldSlots != null && ctorSlot != null) {
                    "com.android.systemui.statusbar.dagger.CentralSurfacesDependenciesModule_ProvideStatusBarIconListFactory"
                        .toClassOrNull()?.resolve()?.optional(true)?.firstMethodOrNull {
                            name = "provideStatusBarIconList"
                            modifiers(Modifiers.STATIC)
                        }?.hook {
                            val iconList = proceed() ?: return@hook result(null)
                            val slots = fldSlots.get(iconList) ?: return@hook result(iconList)
                            slots.clear()
                            desiredOrder().forEach { slotName ->
                                ctorSlot.newInstance(slotName)?.let { slots.add(it) }
                            }
                            result(iconList)
                        }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyBlockList(field: TypedField<Any, List<String>>?, newList: List<String>) {
        if (field == null) return
        val mutable = runCatching { field.get(null) }.getOrNull() as? MutableList<String>
        if (mutable != null) {
            val ok = runCatching {
                mutable.clear()
                mutable.addAll(newList)
            }.isSuccess
            if (ok) return
        }
        runCatching { field.set(null, newList) }
    }

    private fun handleIcon(key: PreferenceKey<Int>, name: String, statusBarList: MutableList<String>, controlList: MutableList<String>) {
        val overrideValue = when (key) {
            Preferences.SystemUI.StatusBar.StackedMobile.SINGLE_MOBILE_SIM1,
            Preferences.SystemUI.StatusBar.StackedMobile.SINGLE_MOBILE_SIM2 -> {
                val real = key.get()
                if (real == 0) 4 else real
            }

            Preferences.SystemUI.StatusBar.CustomWifi.CUSTOM_WIFI_ICON -> {
                val real = key.get()
                if (real == 0) 1 else real
            }
            else -> key.get()
        }
        when (overrideValue) {
            1 -> {
                if (statusBarList.contains(name)) statusBarList.remove(name)
                if (controlList.contains(name)) controlList.remove(name)
            }
            2 -> {
                if (statusBarList.contains(name)) statusBarList.remove(name)
                if (!controlList.contains(name)) controlList.add(name)
            }
            3 -> {
                if (!statusBarList.contains(name)) statusBarList.add(name)
                if (controlList.contains(name)) controlList.remove(name)
            }
            4 -> {
                if (!statusBarList.contains(name)) statusBarList.add(name)
                if (!controlList.contains(name)) controlList.add(name)
            }
            else -> return
        }
    }

    fun getLeftBlockList(allIcons: List<String>): List<String> {
        return allIcons.toMutableList().apply {
            removeAll(leftSlots)
            leftExtraBlockedSlots.split(',', ' ', '，').forEach {
                if (!contains(it)) {
                    add(it)
                }
            }
        }.toList()
    }
}
