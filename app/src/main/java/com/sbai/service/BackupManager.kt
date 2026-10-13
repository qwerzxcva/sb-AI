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
     *
     * 注意：这里必须逐字段覆盖 AppSettings 的全部字段。早期版本只列了 21 个，
     * 之后新增的 28 个（DNS fallback / fakeIP / 缓存 / geoip、TLS 分片、
     * multi_queue、idle-suspend、http_proxy…）在导入时被静默丢弃——
     * 用户导出的配置再导入，这些设置全部丢失。新增字段时请同步补到这里。
     */
    private fun mergeSettings(current: com.sbai.data.AppSettings, incoming: com.sbai.data.AppSettings): com.sbai.data.AppSettings {
        val d = com.sbai.data.AppSettings()
        // 逐字段：导入值非默认 → 用导入；否则保留现有
        return com.sbai.data.AppSettings(
            logLevel = if (incoming.logLevel != d.logLevel) incoming.logLevel else current.logLevel,
            mtu = if (incoming.mtu != d.mtu) incoming.mtu else current.mtu,
            ipv6Route = if (incoming.ipv6Route != d.ipv6Route) incoming.ipv6Route else current.ipv6Route,
            autoDetectInterface = if (incoming.autoDetectInterface != d.autoDetectInterface) incoming.autoDetectInterface else current.autoDetectInterface,
            strictRoute = if (incoming.strictRoute != d.strictRoute) incoming.strictRoute else current.strictRoute,
            finalOutbound = if (incoming.finalOutbound.isNotBlank()) incoming.finalOutbound else current.finalOutbound,
            dnsStrategy = if (incoming.dnsStrategy != d.dnsStrategy) incoming.dnsStrategy else current.dnsStrategy,
            perAppProxy = if (incoming.perAppProxy != d.perAppProxy) incoming.perAppProxy else current.perAppProxy,
            configOverride = if (incoming.configOverride != d.configOverride) incoming.configOverride else current.configOverride,
            autoStartOnBoot = if (incoming.autoStartOnBoot != d.autoStartOnBoot) incoming.autoStartOnBoot else current.autoStartOnBoot,
            themeMode = if (incoming.themeMode != d.themeMode) incoming.themeMode else current.themeMode,
            dynamicColor = if (incoming.dynamicColor != d.dynamicColor) incoming.dynamicColor else current.dynamicColor,
            tunAddress = if (incoming.tunAddress != d.tunAddress) incoming.tunAddress else current.tunAddress,
            tunAddress6 = if (incoming.tunAddress6 != d.tunAddress6) incoming.tunAddress6 else current.tunAddress6,
            subscriptionUserAgent = if (incoming.subscriptionUserAgent.isNotBlank()) incoming.subscriptionUserAgent else current.subscriptionUserAgent,
            subscriptionSendHwid = if (incoming.subscriptionSendHwid != d.subscriptionSendHwid) incoming.subscriptionSendHwid else current.subscriptionSendHwid,
            subscriptionHwid = if (incoming.subscriptionHwid.isNotBlank()) incoming.subscriptionHwid else current.subscriptionHwid,
            subscriptionDeviceOs = if (incoming.subscriptionDeviceOs.isNotBlank()) incoming.subscriptionDeviceOs else current.subscriptionDeviceOs,
            subscriptionVerOs = if (incoming.subscriptionVerOs.isNotBlank()) incoming.subscriptionVerOs else current.subscriptionVerOs,
            subscriptionDeviceModel = if (incoming.subscriptionDeviceModel.isNotBlank()) incoming.subscriptionDeviceModel else current.subscriptionDeviceModel,
            tunStack = if (incoming.tunStack != d.tunStack) incoming.tunStack else current.tunStack,
            tunMultiQueue = if (incoming.tunMultiQueue != d.tunMultiQueue) incoming.tunMultiQueue else current.tunMultiQueue,
            resources = mergeById(current.resources, incoming.resources) { it.id },

            // ---- DNS 高级 ----
            dnsFallbackServers = if (incoming.dnsFallbackServers != d.dnsFallbackServers) incoming.dnsFallbackServers else current.dnsFallbackServers,
            dnsFallbackGeoip = if (incoming.dnsFallbackGeoip != d.dnsFallbackGeoip) incoming.dnsFallbackGeoip else current.dnsFallbackGeoip,
            dnsFallbackGeosite = if (incoming.dnsFallbackGeosite != d.dnsFallbackGeosite) incoming.dnsFallbackGeosite else current.dnsFallbackGeosite,
            dnsFallbackIpcidr = if (incoming.dnsFallbackIpcidr != d.dnsFallbackIpcidr) incoming.dnsFallbackIpcidr else current.dnsFallbackIpcidr,
            dnsFallbackDomain = if (incoming.dnsFallbackDomain != d.dnsFallbackDomain) incoming.dnsFallbackDomain else current.dnsFallbackDomain,
            dnsFakeipFilter = if (incoming.dnsFakeipFilter != d.dnsFakeipFilter) incoming.dnsFakeipFilter else current.dnsFakeipFilter,
            dnsFakeipFilterMode = if (incoming.dnsFakeipFilterMode != d.dnsFakeipFilterMode) incoming.dnsFakeipFilterMode else current.dnsFakeipFilterMode,
            dnsFakeipFilterPatterns = if (incoming.dnsFakeipFilterPatterns != d.dnsFakeipFilterPatterns) incoming.dnsFakeipFilterPatterns else current.dnsFakeipFilterPatterns,
            dnsNameserverPolicy = if (incoming.dnsNameserverPolicy != d.dnsNameserverPolicy) incoming.dnsNameserverPolicy else current.dnsNameserverPolicy,
            dnsCacheCapacity = if (incoming.dnsCacheCapacity != d.dnsCacheCapacity) incoming.dnsCacheCapacity else current.dnsCacheCapacity,
            dnsDisableCache = if (incoming.dnsDisableCache != d.dnsDisableCache) incoming.dnsDisableCache else current.dnsDisableCache,
            dnsDisableExpire = if (incoming.dnsDisableExpire != d.dnsDisableExpire) incoming.dnsDisableExpire else current.dnsDisableExpire,
            dnsOptimistic = if (incoming.dnsOptimistic != d.dnsOptimistic) incoming.dnsOptimistic else current.dnsOptimistic,
            dnsOptimisticTimeout = if (incoming.dnsOptimisticTimeout.isNotBlank()) incoming.dnsOptimisticTimeout else current.dnsOptimisticTimeout,
            dnsQueryTimeout = if (incoming.dnsQueryTimeout.isNotBlank()) incoming.dnsQueryTimeout else current.dnsQueryTimeout,
            dnsResolveStrategy = if (incoming.dnsResolveStrategy.isNotBlank()) incoming.dnsResolveStrategy else current.dnsResolveStrategy,
            dnsReverseMapping = if (incoming.dnsReverseMapping != d.dnsReverseMapping) incoming.dnsReverseMapping else current.dnsReverseMapping,

            // ---- DPI 硬化 ----
            tlsFragment = if (incoming.tlsFragment != d.tlsFragment) incoming.tlsFragment else current.tlsFragment,
            tlsRecordFragment = if (incoming.tlsRecordFragment != d.tlsRecordFragment) incoming.tlsRecordFragment else current.tlsRecordFragment,
            tlsFragmentFallbackDelay = if (incoming.tlsFragmentFallbackDelay != d.tlsFragmentFallbackDelay) incoming.tlsFragmentFallbackDelay else current.tlsFragmentFallbackDelay,

            // ---- TUN 空闲挂起 ----
            idleSuspendDuration = if (incoming.idleSuspendDuration != d.idleSuspendDuration) incoming.idleSuspendDuration else current.idleSuspendDuration,
            idleTeardownDuration = if (incoming.idleTeardownDuration != d.idleTeardownDuration) incoming.idleTeardownDuration else current.idleTeardownDuration,

            // ---- 系统 HTTP 代理下发 ----
            tunHttpProxyEnabled = if (incoming.tunHttpProxyEnabled != d.tunHttpProxyEnabled) incoming.tunHttpProxyEnabled else current.tunHttpProxyEnabled,
            tunHttpProxyPort = if (incoming.tunHttpProxyPort != d.tunHttpProxyPort) incoming.tunHttpProxyPort else current.tunHttpProxyPort,
            tunHttpProxyBypassDomains = if (incoming.tunHttpProxyBypassDomains != d.tunHttpProxyBypassDomains) incoming.tunHttpProxyBypassDomains else current.tunHttpProxyBypassDomains,
        )
    }
}
