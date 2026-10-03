package com.sbai.service

import com.sbai.data.AppState
import com.sbai.data.DnsRule
import com.sbai.data.DnsServerType
import com.sbai.data.LoadBalanceMode
import com.sbai.data.OverridePriority
import com.sbai.data.PerAppProxyMode
import com.sbai.data.RouteRule
import com.sbai.data.RuleAction
import com.sbai.data.RuleLogic
import com.sbai.data.RuleSetType
import com.sbai.data.StickyHashKey
import com.sbai.data.UrltestMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private typealias JB = kotlinx.serialization.json.JsonObjectBuilder

/**
 * sing-box 配置生成器（目标内核：Leadaxe/sing-box-lx，含 balancer/pool 扩展）。
 *
 * 语义要点：
 *  - 路由规则：逐行条目（域名/后缀/关键词/正则、IP/CIDR、规则集 tag），network/protocol 多选。
 *  - 逻辑运算：sing-box 中同一 rule 对象内各字段是 AND、字段内数组是 OR。
 *      · AND → 平铺为单条 rule（所有字段类别都要满足）
 *      · OR  → logical{mode:or, rules:[按字段类别拆分的子规则]}（任一类别满足即可）
 *      · invert → 对整体取反
 *  - DNS 规则：手动规则（可排序）在前，路由规则自动推导的在后；
 *      自动推导只在「非拦截 + 含域名条件 + (指定了 DNS 或 取消了 IPv4/IPv6 之一)」时产生，
 *      且两个条件同时命中只生成一条合并规则。
 *  - 负载均衡：LATENCY=urltest(tolerance=0)、BALANCED=urltest(tolerance>0)、MANUAL=selector；
 *      round_robin 模式额外输出 fork 扩展 balancer{pool,pool_tolerance,sticky_hash}；
 *      autoEnabled 时在最外层再套一层 urltest「auto」。
 *  - 配置覆盖：settings.configOverride 启用时与导入 JSON 深度合并（见 ConfigMerger）。
 */
object SingBoxConfigGenerator {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** 生成最终配置字符串（已应用配置覆盖） */
    fun generate(state: AppState): String {
        val uiConfig = json.encodeToString(JsonElement.serializer(), generateObject(state))
        val override = state.settings.configOverride
        if (!override.enabled || override.json.isBlank()) return uiConfig
        return runCatching { ConfigMerger.merge(uiConfig, override.json, override.priority) }
            .getOrElse { uiConfig }   // 导入 JSON 非法时退回 UI 配置，避免服务起不来
    }

    /** 仅生成 UI 层配置（不含覆盖），供预览对比 */
    fun generateUiOnly(state: AppState): String =
        json.encodeToString(JsonElement.serializer(), generateObject(state))

    fun generateObject(state: AppState): JsonObject {
        val enabledNodes = state.proxyNodes
            .filter { it.enabled && it.outboundJson.isNotBlank() }
            .distinctBy { nodeTag(it) }   // 同 tag 去重，避免 outbound tag 冲突
        val nodeTags = enabledNodes.map { nodeTag(it) }
        val lb = state.loadBalance

        val finalProxyTag = when {
            lb.enabled && lb.mode == LoadBalanceMode.MANUAL -> "lb-selector"
            lb.enabled -> "lb"
            nodeTags.size > 1 -> "proxy"
            nodeTags.size == 1 -> nodeTags[0]
            else -> "direct"
        }
        val entryTag = if (lb.enabled && lb.autoEnabled) "auto" else finalProxyTag

        val dnsServers = buildDnsServers(state, entryTag)
        val defaultDnsTag = state.dnsServers.firstOrNull { it.enabled && it.tag.isNotBlank() }?.tag
            ?: "dns-default"
        val routeRules = buildRouteRules(state, entryTag)
        val dnsRules = buildDnsRules(state, defaultDnsTag)

        val outbounds = buildJsonArray {
            if (lb.enabled) {
                val lbOutbounds = lb.outbounds.filter { it in nodeTags }.ifEmpty { nodeTags }
                if (lb.mode == LoadBalanceMode.MANUAL) {
                    add(buildJsonObject {
                        put("type", "selector")
                        put("tag", "lb-selector")
                        putJsonArray("outbounds") { lbOutbounds.forEach(::add) }
                    })
                } else {
                    add(buildJsonObject {
                        put("type", "urltest")
                        put("tag", "lb")
                        putJsonArray("outbounds") { lbOutbounds.forEach(::add) }
                        put("url", lb.checkUrl)
                        put("interval", lb.interval)
                        put(
                            "tolerance",
                            if (lb.mode == LoadBalanceMode.LATENCY) 0 else lb.toleranceMs,
                        )
                        put("idle_timeout", lb.idleTimeout)
                        put("interrupt_exist_connections", lb.interruptExistConnections)
                        // fork 扩展：round_robin + balancer{pool,...} = 仅使用 N 个节点
                        if (lb.urltestMode == UrltestMode.ROUND_ROBIN) {
                            put("mode", UrltestMode.ROUND_ROBIN.wire)
                            putJsonObject("balancer") {
                                put("pool", lb.pool.coerceAtLeast(1))
                                put("pool_tolerance", lb.poolTolerance.coerceAtLeast(0))
                                putJsonArray("sticky_hash") {
                                    val keys = lb.stickyHash.ifEmpty { listOf(StickyHashKey.NONE) }
                                    keys.forEach { add(it.wire) }
                                }
                            }
                        }
                    })
                }
            } else if (nodeTags.size > 1) {
                add(buildJsonObject {
                    put("type", "selector")
                    put("tag", "proxy")
                    putJsonArray("outbounds") { nodeTags.forEach(::add) }
                })
            }

            if (lb.enabled && lb.autoEnabled) {
                add(buildJsonObject {
                    put("type", "urltest")
                    put("tag", "auto")
                    putJsonArray("outbounds") { add(finalProxyTag) }
                    put("url", lb.checkUrl)
                    put("interval", lb.interval)
                })
            }

            enabledNodes.forEach { node ->
                runCatching { json.parseToJsonElement(node.outboundJson).jsonObject }
                    .getOrNull()?.let(::add)
            }

            add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
            add(buildJsonObject { put("type", "block"); put("tag", "block") })
        }

        return buildJsonObject {
            putJsonObject("log") {
                put("level", state.settings.logLevel.wireName)
                put("timestamp", true)
            }

            putJsonObject("dns") {
                putJsonArray("servers") { dnsServers.forEach(::add) }
                putJsonArray("rules") { dnsRules.forEach(::add) }
                put("strategy", state.settings.dnsStrategy)
                put("independent_cache", true)
                put("final", defaultDnsTag)
            }

            putJsonObject("route") {
                putJsonArray("rules") { routeRules.forEach(::add) }
                putJsonArray("rule_set") {
                    // 显式规则集
                    state.routeRuleSets.filter { it.enabled && it.tag.isNotBlank() }.forEach { rs ->
                        add(buildJsonObject {
                            put("tag", rs.tag)
                            when (rs.type) {
                                RuleSetType.REMOTE -> {
                                    put("type", "remote")
                                    put("format", "source")
                                    put("url", rs.url)
                                    rs.downloadDetour?.takeIf { it.isNotBlank() }
                                        ?.let { put("download_detour", it) }
                                }
                                RuleSetType.LOCAL -> {
                                    put("type", "inline")
                                    val rules = runCatching {
                                        json.parseToJsonElement(rs.localContent)
                                            .jsonObject["rules"] as? JsonArray
                                    }.getOrNull()
                                    put("rules", rules ?: JsonArray(emptyList()))
                                }
                            }
                        })
                    }
                    // Karing 风格：路由规则里直接写 URL 的远程规则集（自动创建，去重）
                    inlineUrlRuleSets(state).forEach { (tag, url) ->
                        add(buildJsonObject {
                            put("tag", tag)
                            put("type", "remote")
                            put("format", "source")
                            put("url", url)
                        })
                    }
                }
                put("final", state.settings.finalOutbound.ifBlank { entryTag })
                put("auto_detect_interface", state.settings.autoDetectInterface)
            }

            put("outbounds", outbounds)

            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    putJsonArray("address") {
                        add(state.settings.tunAddress.ifBlank { "172.18.0.1/30" })
                        if (state.settings.ipv6Route) {
                            add(state.settings.tunAddress6.ifBlank { "fdfe:dcba:9876::1/126" })
                        }
                    }
                    put("mtu", state.settings.mtu)
                    put("auto_route", true)
                    put("strict_route", state.settings.strictRoute)
                    put("stack", "mixed")
                    put("sniff", true)
                    put("sniff_override_destination", false)

                    when (state.settings.perAppProxy.mode) {
                        PerAppProxyMode.INCLUDE ->
                            putJsonArray("include_package") { state.settings.perAppProxy.packages.forEach(::add) }
                        PerAppProxyMode.EXCLUDE ->
                            putJsonArray("exclude_package") { state.settings.perAppProxy.packages.forEach(::add) }
                        PerAppProxyMode.OFF -> Unit
                    }
                })
            }

            putJsonObject("experimental") {
                putJsonObject("cache_file") {
                    put("enabled", true)
                    put("path", "cache.db")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // DNS servers
    // ------------------------------------------------------------------

    private fun buildDnsServers(state: AppState, entryTag: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val servers = state.dnsServers.filter { it.enabled && it.tag.isNotBlank() }

        if (servers.isEmpty()) {
            result.add(buildJsonObject {
                put("tag", "dns-default")
                put("type", "udp")
                put("server", "223.5.5.5")
            })
            result.add(buildJsonObject {
                put("tag", "dns-remote")
                put("type", "https")
                put("server", "8.8.8.8")
                put("detour", entryTag)
            })
            return result
        }

        servers.forEach { s ->
            result.add(buildJsonObject {
                put("tag", s.tag)
                when (s.type) {
                    DnsServerType.LOCAL -> put("type", "local")
                    DnsServerType.HOSTS -> put("type", "hosts")
                    DnsServerType.FAKEIP -> {
                        put("type", "fakeip")
                        put("inet4_range", "198.18.0.0/15")
                        put("inet6_range", "fc00::/18")
                    }
                    else -> {
                        put("type", s.type.wireName)
                        if (s.type == DnsServerType.UDP || s.type == DnsServerType.TCP) {
                            put("server", s.address)
                        } else {
                            val (host, path) = splitHostAndPath(s.address)
                            put("server", host)
                            if (path != null && s.type == DnsServerType.HTTPS) put("path", path)
                        }
                    }
                }
                s.detour?.takeIf { it.isNotBlank() }?.let { put("detour", it) }
                s.addressResolver?.takeIf { it.isNotBlank() }?.let { put("address_resolver", it) }
                s.clientSubnet?.takeIf { it.isNotBlank() }?.let { put("client_subnet", it) }
                if (s.echEnabled && s.type in ECH_CAPABLE) {
                    putJsonObject("tls") {
                        put("enabled", true)
                        putJsonObject("ech") {
                            put("enabled", true)
                            s.echConfig?.takeIf { it.isNotBlank() }?.let {
                                putJsonArray("config") { add(it) }
                            }
                        }
                    }
                }
            })
        }
        return result
    }

    private val ECH_CAPABLE = setOf(
        DnsServerType.TLS, DnsServerType.HTTPS, DnsServerType.QUIC, DnsServerType.H3,
    )

    private fun splitHostAndPath(address: String): Pair<String, String?> {
        val noScheme = address.substringAfter("://")
        val slash = noScheme.indexOf('/')
        return if (slash >= 0) noScheme.substring(0, slash) to noScheme.substring(slash)
        else noScheme to null
    }

    // ------------------------------------------------------------------
    // Route rules
    // ------------------------------------------------------------------

    /**
     * Karing 风格：路由规则的 ruleSetTags 里可直接写远程规则集 URL。
     * 把所有 URL 形态的条目映射为自动生成的 tag（url- + 8 位 hash），去重后返回。
     * 返回 LinkedHashMap 保证顺序稳定。
     */
    internal fun inlineUrlRuleSets(state: AppState): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        state.routeRules.filter { it.enabled }.forEach { rule ->
            rule.ruleSetTags.forEach { entry ->
                val trimmed = entry.trim()
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    map.getOrPut(urlRuleSetTag(trimmed)) { trimmed }
                }
            }
        }
        return map
    }

    /** 由 URL 生成稳定的规则集 tag */
    private fun urlRuleSetTag(url: String): String {
        val hash = url.hashCode().toUInt().toString(16)
        return "url-$hash"
    }

    /** 把路由规则的 ruleSetTags 里的 URL 映射为自动 tag，其余原样保留；结果去重 */
    private fun resolveRuleSetTags(rule: RouteRule): List<String> =
        rule.ruleSetTags.map { entry ->
            val trimmed = entry.trim()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                urlRuleSetTag(trimmed)
            } else {
                trimmed
            }
        }.distinct()

    private fun buildRouteRules(state: AppState, entryTag: String): List<JsonObject> {
        val rules = mutableListOf<JsonObject>()
        rules.add(buildJsonObject { put("action", "sniff") })
        state.routeRules.filter { it.enabled }.forEach { rule ->
            rules.add(buildRouteRule(rule, entryTag))
        }
        return rules
    }

    private fun buildRouteRule(rule: RouteRule, entryTag: String): JsonObject {
        val outbound = when (rule.action) {
            RuleAction.BLOCK -> "block"
            RuleAction.DIRECT -> "direct"
            RuleAction.PROXY -> entryTag
        }

        // 按字段类别拆分为子条件（OR 模式用；AND 模式平铺）
        val domainCond = buildJsonObject { putConditions(rule, domain = true, ip = false, transport = false) }
        val ipCond = buildJsonObject { putConditions(rule, domain = false, ip = true, transport = false) }
        val transportCond = buildJsonObject { putConditions(rule, domain = false, ip = false, transport = true) }

        return buildJsonObject {
            if (rule.logic == RuleLogic.OR) {
                val children = buildJsonArray {
                    listOf(domainCond, ipCond, transportCond).filter { it.isNotEmpty() }.forEach(::add)
                }
                if (children.size == 0) {
                    put("outbound", outbound)
                } else if (children.size == 1) {
                    // 只有一个类别时 OR 与 AND 等价，直接平铺，避免无谓的 logical 包裹
                    children[0].jsonObject.forEach { (k, v) -> put(k, v) }
                    if (rule.invert) put("invert", true)
                    put("outbound", outbound)
                } else {
                    put("type", "logical")
                    put("mode", "or")
                    put("rules", children)
                    if (rule.invert) put("invert", true)
                    put("outbound", outbound)
                }
            } else {
                // AND：所有字段平铺在一个 rule 对象里（sing-box 默认即 AND 语义）
                putConditions(rule, domain = true, ip = true, transport = true)
                if (rule.invert) put("invert", true)
                put("outbound", outbound)
            }
        }
    }

    private fun JB.putConditions(r: RouteRule, domain: Boolean, ip: Boolean, transport: Boolean) {
        if (domain) {
            if (r.domains.isNotEmpty()) putJsonArray("domain") { r.domains.forEach(::add) }
            if (r.domainSuffixes.isNotEmpty()) putJsonArray("domain_suffix") { r.domainSuffixes.forEach(::add) }
            if (r.domainKeywords.isNotEmpty()) putJsonArray("domain_keyword") { r.domainKeywords.forEach(::add) }
            if (r.domainRegexes.isNotEmpty()) putJsonArray("domain_regex") { r.domainRegexes.forEach(::add) }
        }
        if (ip) {
            if (r.ipCidrs.isNotEmpty()) putJsonArray("ip_cidr") { r.ipCidrs.forEach(::add) }
            // Karing 风格：ruleSetTags 里的 URL 自动映射为生成的 tag
            val tags = resolveRuleSetTags(r)
            if (tags.isNotEmpty()) putJsonArray("rule_set") { tags.forEach(::add) }
        }
        if (transport) {
            if (r.networks.isNotEmpty()) putJsonArray("network") { r.networks.forEach(::add) }
            if (r.protocols.isNotEmpty()) putJsonArray("protocol") { r.protocols.forEach(::add) }
            if (r.ports.isNotEmpty()) putJsonArray("port") { r.ports.forEach(::add) }
            if (r.portRanges.isNotEmpty()) putJsonArray("port_range") { r.portRanges.forEach(::add) }
        }
    }

    // ------------------------------------------------------------------
    // DNS rules：手动规则在前（用户可控优先级），自动推导在后
    // ------------------------------------------------------------------

    /** 供 UI 展示：把自动推导的 DNS 规则也算出来（只读） */
    fun autoDnsRules(state: AppState): List<DnsRule> {
        val defaultDnsTag = state.dnsServers.firstOrNull { it.enabled && it.tag.isNotBlank() }?.tag
            ?: "dns-default"
        val groupOfTag = state.dnsGroups.associate { it.name to it.serverTags }
        return state.routeRules.filter { it.enabled }.mapNotNull { rule ->
            if (rule.action == RuleAction.BLOCK) return@mapNotNull null
            if (!rule.hasDomainContent) return@mapNotNull null
            val dnsServer = resolveDnsTag(rule.dnsTag, groupOfTag, state)
            val strategy = ipStrategy(rule.ipv4, rule.ipv6)
            if (dnsServer == null && strategy == null) return@mapNotNull null
            DnsRule(
                id = "auto-${rule.id}",
                enabled = true,
                autoFromRouteRuleId = rule.id,
                name = "自动 · ${rule.name.ifBlank { "路由规则" }}",
                domains = rule.domains,
                domainSuffixes = rule.domainSuffixes,
                domainKeywords = rule.domainKeywords,
                domainRegexes = rule.domainRegexes,
                server = dnsServer ?: defaultDnsTag,
                ipStrategy = strategy ?: "",
            )
        }
    }

    private fun resolveDnsTag(
        dnsTag: String?,
        groupOfTag: Map<String, List<String>>,
        state: AppState,
    ): String? = dnsTag?.takeIf { it.isNotBlank() }?.let { tag ->
        // group → 取首个成员；直接 server tag → 必须真实存在，否则 null（避免悬空引用）
        groupOfTag[tag]?.firstOrNull()
            ?: tag.takeIf { t -> state.dnsServers.any { it.enabled && it.tag == t } }
    }

    private fun buildDnsRules(state: AppState, defaultDnsTag: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val groupOfTag = state.dnsGroups.associate { it.name to it.serverTags }

        // 1) 用户手动创建的 DNS 规则（按列表顺序 = 优先级）
        state.dnsRules.filter { it.enabled }.forEach { r ->
            val server = resolveDnsTag(r.server, groupOfTag, state) ?: return@forEach
            result.add(buildJsonObject {
                if (r.domains.isNotEmpty()) putJsonArray("domain") { r.domains.forEach(::add) }
                if (r.domainSuffixes.isNotEmpty()) putJsonArray("domain_suffix") { r.domainSuffixes.forEach(::add) }
                if (r.domainKeywords.isNotEmpty()) putJsonArray("domain_keyword") { r.domainKeywords.forEach(::add) }
                if (r.domainRegexes.isNotEmpty()) putJsonArray("domain_regex") { r.domainRegexes.forEach(::add) }
                if (r.ruleSetTags.isNotEmpty()) putJsonArray("rule_set") { r.ruleSetTags.forEach(::add) }
                if (r.ipCidrs.isNotEmpty()) putJsonArray("ip_cidr") { r.ipCidrs.forEach(::add) }
                if (r.networks.isNotEmpty()) putJsonArray("network") { r.networks.forEach(::add) }
                if (r.ports.isNotEmpty()) putJsonArray("port") { r.ports.forEach(::add) }
                if (r.queryTypes.isNotEmpty()) putJsonArray("query_type") { r.queryTypes.forEach(::add) }
                put("server", server)
                if (r.ipStrategy.isNotBlank()) put("ip_strategy", r.ipStrategy)
                if (r.disableCache) put("disable_cache", true)
                r.rewriteTtl?.let { put("rewrite_ttl", it) }
                r.clientSubnet?.takeIf { it.isNotBlank() }?.let { put("client_subnet", it) }
            })
        }

        // 2) 规则集 IPv4/IPv6 勾选 → ip_strategy 规则
        state.routeRuleSets.filter { it.enabled && it.tag.isNotBlank() }.forEach { rs ->
            val strategy = ipStrategy(rs.ipv4, rs.ipv6) ?: return@forEach
            result.add(buildJsonObject {
                putJsonArray("rule_set") { add(rs.tag) }
                put("server", defaultDnsTag)
                put("ip_strategy", strategy)
            })
        }

        // 3) 路由规则自动推导（需求 4 + 5：同时命中只生成一条合并规则）
        state.routeRules.filter { it.enabled }.forEach { rule ->
            if (rule.action == RuleAction.BLOCK) return@forEach
            if (!rule.hasDomainContent) return@forEach
            val dnsServer = resolveDnsTag(rule.dnsTag, groupOfTag, state)
            val strategy = ipStrategy(rule.ipv4, rule.ipv6)
            if (dnsServer == null && strategy == null) return@forEach

            result.add(buildJsonObject {
                if (rule.domains.isNotEmpty()) putJsonArray("domain") { rule.domains.forEach(::add) }
                if (rule.domainSuffixes.isNotEmpty()) putJsonArray("domain_suffix") { rule.domainSuffixes.forEach(::add) }
                if (rule.domainKeywords.isNotEmpty()) putJsonArray("domain_keyword") { rule.domainKeywords.forEach(::add) }
                if (rule.domainRegexes.isNotEmpty()) putJsonArray("domain_regex") { rule.domainRegexes.forEach(::add) }
                put("server", dnsServer ?: defaultDnsTag)
                if (strategy != null) put("ip_strategy", strategy)
            })
        }

        return result
    }

    private fun ipStrategy(ipv4: Boolean, ipv6: Boolean): String? = when {
        ipv4 && !ipv6 -> "ipv4_only"
        !ipv4 && ipv6 -> "ipv6_only"
        else -> null   // 全选或全不选都不生成策略
    }

    fun nodeTagOf(node: com.sbai.data.ProxyNode): String = nodeTag(node)

    private fun nodeTag(node: com.sbai.data.ProxyNode): String =
        runCatching {
            json.parseToJsonElement(node.outboundJson).jsonObject["tag"]?.jsonPrimitive?.content
        }.getOrNull() ?: node.name.ifBlank { node.id }

    /** 交给 Libbox.checkConfig 校验；返回 null 表示通过 */
    fun validate(configJson: String): String? =
        runCatching {
            io.nekohasekai.libbox.Libbox.checkConfig(configJson)
            null
        }.getOrElse { it.message ?: "unknown error" }
}
