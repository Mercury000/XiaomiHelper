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

import android.telephony.SubscriptionManager
import com.highcapable.kavaref.KavaRef.Companion.resolve
import dev.lackluster.mihelper.data.preference.Preferences
import dev.lackluster.mihelper.hook.base.StaticHooker
import dev.lackluster.mihelper.hook.rules.systemui.compat.CommonClassUtils.clzCoroutineScope
import dev.lackluster.mihelper.hook.rules.systemui.compat.CommonClassUtils.readonlyStateFlowFalse
import dev.lackluster.mihelper.hook.rules.systemui.compat.FlowCompat.cancelJob
import dev.lackluster.mihelper.hook.rules.systemui.compat.FlowCompat.combineFlows
import dev.lackluster.mihelper.hook.rules.systemui.compat.MutableStateFlowCompat
import dev.lackluster.mihelper.hook.rules.systemui.compat.ReadonlyStateFlowCompat
import dev.lackluster.mihelper.hook.rules.systemui.compat.PairCompat
import dev.lackluster.mihelper.hook.rules.systemui.compat.TripleCompat
import dev.lackluster.mihelper.hook.utils.HostExecutor
import dev.lackluster.mihelper.hook.utils.RemotePreferences.get
import dev.lackluster.mihelper.hook.utils.RemotePreferences.lazyGet
import dev.lackluster.mihelper.hook.utils.d
import dev.lackluster.mihelper.hook.utils.e
import dev.lackluster.mihelper.hook.utils.extraOf
import dev.lackluster.mihelper.hook.utils.toTyped
import java.util.concurrent.ConcurrentHashMap

object HideCellularIcon : StaticHooker() {
    private var Any.defDataSubIdFlow by extraOf<Any>("KEY_DEF_DATA_CONFIG_FLOW")

    private val enableStackedMobile by Preferences.SystemUI.StatusBar.StackedMobile.ENABLED.lazyGet()
    private val hideSimAuto by lazy {
        Preferences.SystemUI.StatusBar.IconDetail.HIDE_SIM_AUTO.get() && !enableStackedMobile
    }
    private val hideSimOne by lazy {
        Preferences.SystemUI.StatusBar.IconDetail.HIDE_SIM_ONE.get() || enableStackedMobile
    }
    private val hideSimTwo by lazy {
        Preferences.SystemUI.StatusBar.IconDetail.HIDE_SIM_TWO.get() || enableStackedMobile
    }

    private val hideSimJobMap = ConcurrentHashMap<Int, List<Any?>>()

    override fun onInit() {
        updateSelfState(hideSimAuto || hideSimOne || hideSimTwo)
    }

    override fun onHook() {
        val clzMobileIconInteractor = "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor".toClassOrNull()
        if (hideSimAuto) {
            "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl".toClassOrNull()?.apply {
                val defaultDataSubId = resolve().optional(true).firstFieldOrNull {
                    name = "defaultDataSubId"
                }?.toTyped<Any>()
                resolve().optional(true).firstMethodOrNull {
                    name = "getMobileConnectionInteractorForSubId"
                }?.hook {
                    val mobileIconInteractor = proceed()
                    val defaultDataSubIdFlow = defaultDataSubId?.get(thisObject)
                    mobileIconInteractor.defDataSubIdFlow = defaultDataSubIdFlow
                    result(mobileIconInteractor)
                }
            }
        }
        val clzMobileIconVM = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModel".toClassOrNull()
        val subscriptionId = clzMobileIconVM?.resolve()?.optional(true)?.firstFieldOrNull {
            name = "subscriptionId"
        }?.toTyped<Int>()
        val isVisible = clzMobileIconVM?.resolve()?.optional(true)?.firstFieldOrNull {
            name = "isVisible"
        }?.toTyped<Any>()

        // scopeOf/defDataSubIdOf stay lazy because the two entry points below source them
        // differently: the constructor receives both as arguments, while the factory has to read
        // them off the owning MobileIconsViewModel.
        fun hideFor(vm: Any, scopeOf: () -> Any?, defDataSubIdOf: () -> Any?) {
            val subId = subscriptionId?.get(vm)
            val slotIndex = subId?.let { SubscriptionManager.getSlotIndex(it) }
            d { "MobileIconViewModel: subId=$subId slotIndex=$slotIndex" }
            hideSimJobMap.remove(subId)?.forEach {
                cancelJob(it)
            }
            if (enableStackedMobile) {
                isVisible?.set(vm, readonlyStateFlowFalse)
            } else if (hideSimAuto && subId != null) {
                val coroutineScope = scopeOf() ?: return
                val defaultDataSubIdFlow = defDataSubIdOf()?.let {
                    ReadonlyStateFlowCompat<Int?>().of(it)
                } ?: return
                val oriVisibleFlow = isVisible?.get(vm)?.let {
                    ReadonlyStateFlowCompat<Boolean>().of(it)
                } ?: return
                val proxyStateFlow = MutableStateFlowCompat(false)
                val jobs = combineFlows(
                    coroutineScope,
                    oriVisibleFlow,
                    false,
                    defaultDataSubIdFlow,
                    -1,
                    proxyStateFlow
                ) { a, b ->
                    return@combineFlows a && (b == subId)
                }
                hideSimJobMap[subId] = jobs
                isVisible.set(vm, proxyStateFlow.toReadonlyStateFlow())
            } else if ((slotIndex == 0 && hideSimOne) || (slotIndex == 1 && hideSimTwo)) {
                isVisible?.set(vm, readonlyStateFlowFalse)
            } else if (slotIndex == -1 && (hideSimOne || hideSimTwo)) {
                val coroutineScope = scopeOf() ?: return
                val oriVisibleFlow = isVisible?.get(vm)?.let {
                    ReadonlyStateFlowCompat<Boolean>().of(it)
                } ?: return
                val slotIndexCheckFlow = MutableStateFlowCompat(false)
                val proxyStateFlow = MutableStateFlowCompat(false)
                val jobs = combineFlows(
                    coroutineScope,
                    oriVisibleFlow,
                    false,
                    slotIndexCheckFlow,
                    false,
                    proxyStateFlow
                ) { a, b ->
                    return@combineFlows a && b
                }
                hideSimJobMap[subId] = jobs
                isVisible.set(vm, proxyStateFlow.toReadonlyStateFlow())
                HostExecutor.execute(
                    tag = "CheckSlotIndex_${subId}",
                    backgroundTask = {
                        var slot = -1
                        var currentDelayMs = 200L
                        val maxRetries = 8

                        for (i in 0 until maxRetries) {
                            try {
                                Thread.sleep(currentDelayMs)
                            } catch (t: InterruptedException) {
                                e(t) { "Canceled!" }
                                Thread.currentThread().interrupt()
                                return@execute -1
                            }

                            slot = SubscriptionManager.getSlotIndex(subId)

                            if (slot != -1) {
                                d { "MobileIconViewModel: Slot resolved! subId=$subId, slotIndex=$slot, attempts=${i + 1}" }
                                break
                            }
                            currentDelayMs *= 2
                        }

                        return@execute slot
                    },
                    runOnMain = true,
                    onResult = { slot ->
                        val shouldHide = (slot == 0 && hideSimOne) || (slot == 1 && hideSimTwo)
                        if (!shouldHide) {
                            slotIndexCheckFlow.setValue(true)
                        }
                    }
                )
            }
        }

        clzMobileIconVM?.resolve()?.optional(true)?.firstConstructorOrNull()?.hook {
            val ori = proceed()
            hideFor(
                thisObject,
                { args.firstOrNull { clzCoroutineScope?.isInstance(it) == true } },
                {
                    args.firstOrNull { clzMobileIconInteractor?.isInstance(it) == true }
                        ?.defDataSubIdFlow
                }
            )
            result(ori)
        }
        // Newer ROMs inline that constructor away and hand the view models out of this factory
        // instead. createViewModel returns Triple(MobileIconViewModel, per-VM CoroutineScope,
        // MiuiMobileIconVMImpl); the Miui impl is the one the status bar binder actually reads,
        // and its isVisible carries a Pair(signal, type) rather than a bare Boolean.
        "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel".toClassOrNull()?.apply {
            val fldActiveDataSubId = resolve().optional(true).firstFieldOrNull {
                name = "activeMobileDataSubscriptionId"
            }?.toTyped<Any>()
            val clzMiuiMobileIconVM = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiMobileIconVMImpl".toClassOrNull()
            val fldMiuiIsVisible = clzMiuiMobileIconVM?.resolve()?.optional(true)?.firstFieldOrNull {
                name = "isVisible"
            }?.toTyped<Any>()
            val readonlyStateFlowPairFalse by lazy {
                MutableStateFlowCompat(PairCompat.create(false, false)).toReadonlyStateFlow()
            }
            resolve().optional(true).firstMethodOrNull {
                name = "createViewModel"
                parameters(Int::class)
            }?.hook {
                val ori = proceed() ?: return@hook result(null)
                val owner = thisObject
                val aospVM = TripleCompat.getFirst(ori)
                val vmScope = TripleCompat.getSecond(ori)
                val miuiVM = TripleCompat.getThird(ori)
                if (aospVM != null) {
                    hideFor(aospVM, { vmScope }, { fldActiveDataSubId?.get(owner) })
                }
                // The Miui VM has no subscriptionId of its own, so reuse the AOSP one's.
                val subId = aospVM?.let { subscriptionId?.get(it) }
                val slotIndex = subId?.let { SubscriptionManager.getSlotIndex(it) }
                val hideMiui = enableStackedMobile ||
                        (slotIndex == 0 && hideSimOne) || (slotIndex == 1 && hideSimTwo)
                d { "MiuiMobileIconVMImpl: subId=$subId slotIndex=$slotIndex hide=$hideMiui" }
                if (hideMiui && miuiVM != null) {
                    fldMiuiIsVisible?.set(miuiVM, readonlyStateFlowPairFalse)
                }
                result(ori)
            }
        }
    }
}
