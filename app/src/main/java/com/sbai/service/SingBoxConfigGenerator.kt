package com.sbai.service

import com.sbai.data.AppSettings
import com.sbai.data.AppState
import com.sbai.data.DnsServerType
import com.sbai.data.LoadBalanceMode
import com.sbai.data.RouteRule
import com.sbai.data.RuleAction
import com.sbai.data.RuleLogic
import com.sbai.data.RuleSetType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * sing-box 配置生成器。
 *
 * 语义要点：
 *  - 路由规则：逐行条目（域名/后缀/关键词/正则、IP/CIDR、规则集 tag），
 *    network / protocol 多选，logic=and/or 聚合，invert 取反。
 *  - DNS 联动：仅「非拦截、非 IP/远程规则集、且勾选了 DNS/DNS group」的规则
 *    自动生成 DNS 规则（server=所选 DNS group 的首个 server；domain_suffix 反向
 *    从域名规则推导）。
 *  - IPv4/IPv6：只要取消勾选任一项，即生成「{tag}-ip-strategy」DNS 规则；
 *    与 DNS 联动规则命中同一路由时合并为一条（{tag}-dns）。
 *  - 负载均衡：LATENCY=urltest(tolerance=0)、BALANCED=urltest(tolerance>0)、
 *    MANUAL=selector；autoEnabled 时在最外层再套一层 urltest「auto」。
 */
private typealias JsonObjectBuilderCompat = kotlinx.serialization.json.JsonObjectBuilder

object SingBoxConfigGenerator {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun generate(state: AppState): String =
        json.encodeToString(JsonElement.serializer(), generateObject(state))

    fun generateObject(state: AppState): JsonObject {
        val enabledNodes = state.proxyNodes.filter { it.enabled && it.outboundJson.isNotBlank() }
        val nodeTags = enabledNodes.map { nodeTag(it) }.distinct()
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
        val groupOfTag: Map<String, List<String>> = state.dnsGroups
            .associate { it.name to it.serverTags }
        val dnsForRule: (RouteRule) -> String? = { rule ->
            rule.dnsTag?.takeIf { it.isNotBlank() }?.let { tag ->
                groupOfTag[tag]?.firstOrNull() ?: tag
            }
        }

        val routeRules = buildRouteRules(state, entryTag)
        val dnsRules = buildDnsRules(state, routeRules, dnsForRule)

        val outbounds = buildJsonArray {
            if (lb.enabled) {
                val lbOutbounds = lb.outbounds.ifEmpty { nodeTags }
                when (lb.mode) {
                    LoadBalanceMode.MANUAL -> {
                        add(buildJsonObject {
                            put("type", "selector")
                            put("tag", "lb-selector")
                            putJsonArray("outbounds") { lbOutbounds.forEach(::add) }
                        })
                    }
                    LoadBalanceMode.LATENCY -> {
                        add(buildJsonObject {
                            put("type", "urltest")
                            put("tag", "lb")
                            putJsonArray("outbounds") { lbOutbounds.forEach(::add) }
                            put("url", lb.checkUrl)
                            put("interval", "${lb.intervalSeconds}s")
                            put("tolerance", 0)
                            put("interrupt_exist_connections", lb.interruptExistConnections)
                        })
                    }
                    LoadBalanceMode.BALANCED -> {
                        add(buildJsonObject {
                            put("type", "urltest")
                            put("tag", "lb")
                            putJsonArray("outbounds") { lbOutbounds.forEach(::add) }
                            put("url", lb.checkUrl)
                            put("interval", "${lb.intervalSeconds}s")
                            put("tolerance", lb.toleranceMs)
                            put("idle_timeout", "${lb.idleTimeoutSeconds}s")
                            put("interrupt_exist_connections", lb.interruptExistConnections)
                        })
                    }
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
                    put("interval", "${lb.intervalSeconds}s")
                })
            }

            enabledNodes.forEach { node ->
                runCatching {
                    json.parseToJsonElement(node.outboundJson).jsonObject
                }.getOrNull()?.let(::add)
            }

            add(buildJsonObject {
                put("type", "direct")
                put("tag", "direct")
            })
            add(buildJsonObject {
                put("type", "block")
                put("tag", "block")
            })
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
                put("final", state.dnsServers.firstOrNull { it.enabled }?.tag ?: "dns-default")
            }

            putJsonObject("route") {
                putJsonArray("rules") { routeRules.forEach(::add) }
                putJsonArray("rule_set") {
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
                                    put("format", "source")
                                    runCatching {
                                        put("rules", json.parseToJsonElement(rs.localContent)
                                            .jsonObject["rules"] ?: JsonArray(emptyList()))
                                    }
                                }
                            }
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
                        add("172.18.0.1/30")
                        if (state.settings.ipv6Route) add("fdfe:dcba:9876::1/126")
                    }
                    put("mtu", state.settings.mtu)
                    put("auto_route", true)
                    put("strict_route", state.settings.strictRoute)
                    put("stack", "mixed")
                    put("sniff", true)
                    put("sniff_override_destination", false)
                })
            }

            putJsonObject("experimental") {
                putJsonObject("clash_api") {
                    put("external_controller", "127.0.0.1:9090")
                }
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
                        putJsonObject("inet4_range") {}
                        putJsonObject("inet6_range") {}
                    }
                    else -> {
                        put("type", s.type.wireName)
                        if (s.type == DnsServerType.UDP || s.type == DnsServerType.TCP) {
                            put("server", s.address)
                        } else {
                            // tls/https/quic/h3：server 取 address 去掉 scheme 后的 host:port
                            put("server", stripScheme(s.address))
                        }
                    }
                }
                s.detour?.takeIf { it.isNotBlank() }?.let { put("detour", it) }
                s.addressResolver?.takeIf { it.isNotBlank() }?.let { put("address_resolver", it) }
                // ECS（EDNS Client Subnet）
                s.clientSubnet?.takeIf { it.isNotBlank() }?.let { put("client_subnet", it) }
                // ECH（Encrypted Client Hello）：仅加密类 DNS 有效
                if (s.echEnabled && s.type in setOf(DnsServerType.TLS, DnsServerType.HTTPS, DnsServerType.QUIC, DnsServerType.H3)) {
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

    private fun stripScheme(address: String): String {
        val idx = address.indexOf("://")
        val host = if (idx >= 0) address.substring(idx + 3) else address
        return host.substringBefore("/")
    }

    // ------------------------------------------------------------------
    // Route rules
    // ------------------------------------------------------------------

    private fun buildRouteRules(state: AppState, entryTag: String): List<JsonObject> {
        val rules = mutableListOf<JsonObject>()

        // 系统内置：DNS 劫持由 tun 处理，Clash API 直连
        rules.add(buildJsonObject {
            put("action", "sniff")
        })
        rules.add(buildJsonObject {
            putJsonArray("ip_cidr") { add("127.0.0.1/32"); add("::1/128") }
            putJsonArray("port") { add(9090) }
            put("outbound", "direct")
        })

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

        fun JsonObjectBuilderCompat.putConditions(r: RouteRule, includeDomain: Boolean, includeIp: Boolean) {
            if (includeDomain) {
                if (r.domains.isNotEmpty()) putJsonArray("domain") { r.domains.forEach(::add) }
                if (r.domainSuffixes.isNotEmpty()) putJsonArray("domain_suffix") { r.domainSuffixes.forEach(::add) }
                if (r.domainKeywords.isNotEmpty()) putJsonArray("domain_keyword") { r.domainKeywords.forEach(::add) }
                if (r.domainRegexes.isNotEmpty()) putJsonArray("domain_regex") { r.domainRegexes.forEach(::add) }
            }
            if (includeIp) {
                if (r.ipCidrs.isNotEmpty()) putJsonArray("ip_cidr") { r.ipCidrs.forEach(::add) }
                if (r.ruleSetTags.isNotEmpty()) putJsonArray("rule_set") { r.ruleSetTags.forEach(::add) }
            }
            if (r.networks.isNotEmpty()) putJsonArray("network") { r.networks.forEach(::add) }
            if (r.protocols.isNotEmpty()) putJsonArray("protocol") { r.protocols.forEach(::add) }
            if (r.ports.isNotEmpty()) putJsonArray("port") { r.ports.forEach(::add) }
            if (r.portRanges.isNotEmpty()) putJsonArray("port_range") { r.portRanges.forEach(::add) }
        }

        return buildJsonObject {
            when (rule.logic) {
                RuleLogic.SINGLE -> {
                    val scope = buildJsonObject {
                        putConditions(rule, includeDomain = true, includeIp = true)
                    }
                    // 复制 scope 字段到本层
                    scope.forEach { (k, v) -> put(k, v) }
                    if (rule.invert) put("invert", true)
                    put("outbound", outbound)
                }
                RuleLogic.AND, RuleLogic.OR -> {
                    // 逻辑运算：domain 条件 / ip 条件各为一个子规则，and/or 聚合
                    val domainCond = buildJsonObject {
                        putConditions(rule, includeDomain = true, includeIp = false)
                    }
                    val ipCond = buildJsonObject {
                        putConditions(rule, includeDomain = false, includeIp = true)
                    }
                    val children = buildJsonArray {
                        if (domainCond.isNotEmpty()) add(domainCond)
                        if (ipCond.isNotEmpty()) add(ipCond)
                    }
                    if (children.size == 0) {
                        // 没有任何条件：退化为全局规则
                        put("outbound", outbound)
                    } else {
                        put("type", "logical")
                        put("mode", if (rule.logic == RuleLogic.AND) "and" else "or")
                        put("rules", children)
                        if (rule.invert) put("invert", true)
                        put("outbound", outbound)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Route rules
    // ------------------------------------------------------------------

    private fun buildDnsRules(
        state: AppState,
        routeRules: List<JsonObject>,
        dnsForRule: (RouteRule) -> String?,
    ): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val enabledRules = state.routeRules.filter { it.enabled }

        // 路由规则集 IPv4/IPv6 勾选（默认全选）→ 生成 ip_strategy DNS 规则
        state.routeRuleSets.filter { it.enabled && it.tag.isNotBlank() }.forEach { rs ->
            val strategy = ipStrategy(rs.ipv4, rs.ipv6) ?: return@forEach
            result.add(buildJsonObject {
                putJsonArray("rule_set") { add(rs.tag) }
                put("server", "dns-default")
                put("ip_strategy", strategy)
            })
        }

        enabledRules.forEachIndexed { index, rule ->
            if (rule.action == RuleAction.BLOCK) return@forEachIndexed
            if (!rule.hasDomainContent) return@forEachIndexed

            val dnsServer = dnsForRule(rule)
            val strategy = ipStrategy(rule.ipv4, rule.ipv6)

            val base = rule.tagBase(index)

            when {
                // 需求 4 + 5 同时命中：只生成一条（合并 DNS 服务器与 ip_strategy）
                dnsServer != null && strategy != null -> {
                    result.add(buildJsonObject {
                        putDomainMatchers(rule)
                        put("server", dnsServer)
                        put("ip_strategy", strategy)
                    })
                }
                dnsServer != null -> {
                    result.add(buildJsonObject {
                        putDomainMatchers(rule)
                        put("server", dnsServer)
                    })
                }
                strategy != null -> {
                    result.add(buildJsonObject {
                        putDomainMatchers(rule)
                        put("server", "dns-default")
                        put("ip_strategy", strategy)
                    })
                }
            }
        }

        return result
    }

    private fun JsonObjectBuilderCompat.putDomainMatchers(rule: RouteRule) {
        if (rule.domains.isNotEmpty()) putJsonArray("domain") { rule.domains.forEach(::add) }
        if (rule.domainSuffixes.isNotEmpty()) putJsonArray("domain_suffix") { rule.domainSuffixes.forEach(::add) }
        if (rule.domainKeywords.isNotEmpty()) putJsonArray("domain_keyword") { rule.domainKeywords.forEach(::add) }
        if (rule.domainRegexes.isNotEmpty()) putJsonArray("domain_regex") { rule.domainRegexes.forEach(::add) }
    }

    private fun ipStrategy(ipv4: Boolean, ipv6: Boolean): String? = when {
        ipv4 && !ipv6 -> "ipv4_only"
        !ipv4 && ipv6 -> "ipv6_only"
        !ipv4 && !ipv6 -> "ipv4_only"
        else -> null
    }

    private fun RouteRule.tagBase(index: Int): String =
        name.ifBlank { "rule-$index" }

    private fun nodeTag(node: com.sbai.data.ProxyNode): String =
        runCatching {
            json.parseToJsonElement(node.outboundJson).jsonObject["tag"]?.jsonPrimitive?.content
        }.getOrNull() ?: node.name.ifBlank { node.id }

    /** 仅用于工具与测试：生成后交给 Libbox.checkConfig 校验。 */
    fun validate(configJson: String): String? =
        runCatching {
            io.nekohasekai.libbox.Libbox.checkConfig(configJson)
            null
        }.getOrElse { it.message ?: "unknown error" }
}
