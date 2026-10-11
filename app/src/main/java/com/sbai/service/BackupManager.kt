package com.sbai.service

import com.sbai.data.AppState

/**
 * 备份/恢复的合并语义（参考 LxBox 017-BACKUP_AND_STORAGE 的 merge/replace）。
 *
 * replace：用导入的整体替换现有（现有 RuleStore.replaceAll 已实现）。
 * merge：把导入的各类列表「追加」到现有状态，按 id 去重（导入的不覆盖现有同 id 项，
 *   除非显式声明）；settings 按字段合并（导入的非默认值覆盖现有）。
 *
 * 语义要点：
 *  - merge 不删除任何现有项（LxBox 017-P6「Merge deletes nothing」）。
 *  - 列表按 id 去重：导入中 id 已存在 → 保留现有（现有优先）；不存在 → 追加。
 *  - settings 合并：仅导入中「非默认值」的字段覆盖现有（避免用空 AppSettings 抹掉现有设置）。
 */
object BackupManager {

    /**
     * 把 [incoming] merge 进 [current]。
     * 列表按 id 追加去重；settings 字段级合并（非默认值才覆盖）。
     */
    fun merge(current: AppState, incoming: AppState): AppState {
        return AppState(
            routeRules = mergeById(current.routeRules, incoming.routeRules) { it.id },
            routeRuleSets = mergeById(current.routeRuleSets, incoming.routeRuleSets) { it.id },
            dnsServers = mergeById(current.dnsServers, incoming.dnsServers) { it.id },
            dnsGroups = mergeById(current.dnsGroups, incoming.dnsGroups) { it.id },
            dnsRules = mergeById(current.dnsRules, incoming.dnsRules) { it.id },
            loadBalance = if (incoming.loadBalance == com.sbai.data.LoadBalanceConfig()) current.loadBalance else incoming.loadBalance,
            proxyNodes = mergeById(current.proxyNodes, incoming.proxyNodes) { it.id },
            subscriptions = mergeById(current.subscriptions, incoming.subscriptions) { it.id },
            customHosts = mergeById(current.customHosts, incoming.customHosts) { it.id },
            settings = mergeSettings(current.settings, incoming.settings),
            profiles = mergeById(current.profiles, incoming.profiles) { it.id },
            activeProfileId = if (incoming.activeProfileId.isNotEmpty()) incoming.activeProfileId else current.activeProfileId,
        )
    }

    /** 按 id 去重合并：现有优先，导入中不存在的 id 追加到末尾 */
    private fun <T : Any> mergeById(current: List<T>, incoming: List<T>, idOf: (T) -> String): List<T> {
        val existingIds = current.map(idOf).toSet()
        val appended = incoming.filter { idOf(it) !in existingIds }
        return current + appended
    }

    /**
     * settings 字段级合并：只覆盖「导入中显式设置（非默认值）的字段」。
     * 用「与默认值比较」判断字段是否显式设置。
     */
    private fun mergeSettings(current: com.sbai.data.AppSettings, incoming: com.sbai.data.AppSettings): com.sbai.data.AppSettings {
        val default = com.sbai.data.AppSettings()
        return com.sbai.data.AppSettings(
            logLevel = if (incoming.logLevel != default.logLevel) incoming.logLevel else current.logLevel,
            mtu = if (incoming.mtu != default.mtu) incoming.mtu else current.mtu,
            ipv6Route = if (incoming.ipv6Route != default.ipv6Route) incoming.ipv6Route else current.ipv6Route,
            autoDetectInterface = if (incoming.autoDetectInterface != default.autoDetectInterface) incoming.autoDetectInterface else current.autoDetectInterface,
            strictRoute = if (incoming.strictRoute != default.strictRoute) incoming.strictRoute else current.strictRoute,
            finalOutbound = if (incoming.finalOutbound.isNotBlank()) incoming.finalOutbound else current.finalOutbound,
            dnsStrategy = if (incoming.dnsStrategy != default.dnsStrategy) incoming.dnsStrategy else current.dnsStrategy,
            perAppProxy = if (incoming.perAppProxy != default.perAppProxy) incoming.perAppProxy else current.perAppProxy,
            configOverride = if (incoming.configOverride != default.configOverride) incoming.configOverride else current.configOverride,
            autoStartOnBoot = if (incoming.autoStartOnBoot != default.autoStartOnBoot) incoming.autoStartOnBoot else current.autoStartOnBoot,
            themeMode = if (incoming.themeMode != default.themeMode) incoming.themeMode else current.themeMode,
            dynamicColor = if (incoming.dynamicColor != default.dynamicColor) incoming.dynamicColor else current.dynamicColor,
            tunAddress = if (incoming.tunAddress != default.tunAddress) incoming.tunAddress else current.tunAddress,
            tunAddress6 = if (incoming.tunAddress6 != default.tunAddress6) incoming.tunAddress6 else current.tunAddress6,
            subscriptionUserAgent = if (incoming.subscriptionUserAgent.isNotBlank()) incoming.subscriptionUserAgent else current.subscriptionUserAgent,
            subscriptionSendHwid = if (incoming.subscriptionSendHwid != default.subscriptionSendHwid) incoming.subscriptionSendHwid else current.subscriptionSendHwid,
            subscriptionHwid = if (incoming.subscriptionHwid.isNotBlank()) incoming.subscriptionHwid else current.subscriptionHwid,
            subscriptionDeviceOs = if (incoming.subscriptionDeviceOs.isNotBlank()) incoming.subscriptionDeviceOs else current.subscriptionDeviceOs,
            subscriptionVerOs = if (incoming.subscriptionVerOs.isNotBlank()) incoming.subscriptionVerOs else current.subscriptionVerOs,
            subscriptionDeviceModel = if (incoming.subscriptionDeviceModel.isNotBlank()) incoming.subscriptionDeviceModel else current.subscriptionDeviceModel,
            tunStack = if (incoming.tunStack != default.tunStack) incoming.tunStack else current.tunStack,
            resources = mergeById(current.resources, incoming.resources) { it.id },
        )
    }
}
