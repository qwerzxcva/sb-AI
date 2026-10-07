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
            // 订阅禁用（订阅卡片 on/off）→ 该订阅下所有节点退出配置（真实生效，非 UI 摆设）
            .filter { node ->
                val subId = node.subscriptionId ?: return@filter true
                state.subscriptions.firstOrNull { it.id == subId }?.enabled ?: true
            }
            .filter { it.enabled && it.outboundJson.isNotBlank() }
            .distinctBy { nodeTag(it) }   // 同 tag 去重，避免 outbound tag 冲突
        // sing-box 1.12+：wireguard 等是 endpoint（config.endpoints[]），不是 outbound。
        // 分离二者：endpoint 节点单独进 endpoints 数组，其余进 outbounds 数组。
        // 但 nodeTags 保留全部（proxy group 引用 endpoint tag 与 outbound tag 同等）。
        val endpointNodes = enabledNodes.filter { isEndpointNode(it) }
        val outboundNodes = enabledNodes.filter { !isEndpointNode(it) }
        val nodeTags = enabledNodes.map { nodeTag(it) }
        val lb = state.loadBalance

        val finalProxyTag = when {
            lb.enabled && lb.mode == LoadBalanceMode.MANUAL -> "lb-selector"
            lb.enabled -> "lb"
            nodeTags.size > 1 -> "proxy"
            nodeTags.size == 1 -> nodeTags[0]
            else -> "direct"
        }
        // 自动模式与负载均衡解耦：auto（urltest 优选）可在关掉负载均衡后单独使用。
        // 但 auto 只有在「有多个出口候选可测速」时才有意义：
        //  - lb（urltest 组）/ proxy（多节点 selector）→ 套 auto 再优选，合理；
        //  - lb-selector（手动）→ 套 urltest 会覆盖用户手动选择，不套；
        //  - 单节点 / direct → 无可测速对象，不套。
        val autoWorthWrapping = lb.autoEnabled && (finalProxyTag == "lb" || finalProxyTag == "proxy")
        val entryTag = if (autoWorthWrapping) "auto" else finalProxyTag

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

            // 自动模式独立于负载均衡：与 entryTag 同条件创建（仅为多候选出口套 urltest 优选）
            if (autoWorthWrapping) {
                add(buildJsonObject {
                    put("type", "urltest")
                    put("tag", "auto")
                    putJsonArray("outbounds") { add(finalProxyTag) }
                    put("url", lb.checkUrl)
                    put("interval", lb.interval)
                })
            }

            outboundNodes.forEach { node ->
                runCatching { json.parseToJsonElement(node.outboundJson).jsonObject }
                    .getOrNull()?.let { applyTlsFragment(it, state) }?.let(::add)
            }

            add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
            // sing-box 1.13 已移除特殊出站 block/dns（1.11 起废弃），声明即被内核拒绝；
            // 拦截统一使用路由动作 {"action":"reject"}。
        }

        // sing-box 1.12+：wireguard 等 endpoint 节点单独进 endpoints 数组。
        // 仅当存在 endpoint 节点时才生成，避免给内核喂空数组。
        val endpoints = if (endpointNodes.isEmpty()) null else buildJsonArray {
            endpointNodes.forEach { node ->
                runCatching { json.parseToJsonElement(node.outboundJson).jsonObject }
                    .getOrNull()?.let(::add)
            }
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
                                    // .srs 是 sing-box 二进制规则集；误标为 source 会导致下载后解析失败。
                                    put("format", remoteRuleSetFormat(rs.url))
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
                    // 资源注入：China IP 列表
                    state.settings.resources
                        .filter { it.enabled && it.resType == com.sbai.data.ResourceType.CHINA_IP && it.content.isNotEmpty() }
                        .forEach { res ->
                            add(buildJsonObject {
                                put("tag", "res-${res.id.take(8)}")
                                put("type", "inline")
                                // content 格式：每行一个 IP/CIDR（如 36.33.64.0/20）
                                putJsonArray("rules") {
                                    res.content.lines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { cidr ->
                                        add(buildJsonObject {
                                            putJsonArray("ip_cidr") { add(cidr.trim()) }
                                            put("outbound", "direct")
                                        })
                                    }
                                }
                            })
                        }
                    // Karing 风格：路由规则里直接写 URL 的远程规则集（自动创建，去重）
                    inlineUrlRuleSets(state).forEach { (tag, url) ->
                        add(buildJsonObject {
                            put("tag", tag)
                            put("type", "remote")
                            put("format", remoteRuleSetFormat(url))
                            put("url", url)
                        })
                    }
                }
                // 1.13 已无 block/dns 特殊出站：旧配置里 final=block/dns 会引用不存在的 tag 导致启动失败，回退到入口。
                val finalTag = state.settings.finalOutbound.trim()
                put("final", if (finalTag.isEmpty() || finalTag == "block" || finalTag == "dns") entryTag else finalTag)
                // 1.12 起 dial 字段缺少 domain_resolver 已废弃（后续版本移除）：为域名形式的节点 server
                // 指定一个不走代理的解析器，避免「代理节点域名需要经代理解析」的循环。
                dnsServers.firstOrNull { srv ->
                    srv["detour"] == null &&
                        srv["type"]?.jsonPrimitive?.content !in setOf("fakeip", "hosts")
                }?.get("tag")?.jsonPrimitive?.content?.let { put("default_domain_resolver", it) }
                put("auto_detect_interface", state.settings.autoDetectInterface)
            }

            // sing-box 1.12+：endpoint 节点（wireguard）在 endpoints 数组，outbound 在 outbounds 数组。
            // 顺序：endpoints 先于 outbounds（内核要求 endpoints 先声明）。
            if (endpoints != null) put("endpoints", endpoints)

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
                    // TUN 网络栈：system / gvisor / mixed
                    put("stack", state.settings.tunStack.ifBlank { "mixed" })
                    // endpoint_independent：允许不同源端口的连接复用同一连接（提升 UDP 性能）
                    // sing-box 1.12+ 推荐在 TUN 上启用
                    put("endpoint_independent", true)
                    // sing-box 1.13 已移除 inbound 上的 legacy 字段（sniff / sniff_override_destination 等），
                    // 带上会被内核拒绝："legacy inbound fields are deprecated ... removed in sing-box 1.13.0"。
                    // 真机复现即为 VPN 无法启动的根因。嗅探改由 route.rules 中的 {"action":"sniff"} 承担（见下方路由规则）。

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
                    DnsServerType.HOSTS -> {
                        // sing-box hosts 类型 DNS server 需要 predefined 映射表；
                        // 映射从全局 customHosts 读取（单独编辑），不在服务器创建里填写
                        put("type", "hosts")
                        putJsonObject("predefined") {
                            state.customHosts.filter { it.host.isNotBlank() && it.ips.isNotEmpty() }
                                .forEach { entry -> putJsonArray(entry.host) { entry.ips.forEach(::add) } }
                        }
                    }
                    DnsServerType.FAKEIP -> {
                        put("type", "fakeip")
                        // 自定义段优先，空则用内核默认
                        put("inet4_range", s.inet4Range.ifBlank { "10.0.0.0/8" })
                        put("inet6_range", s.inet6Range.ifBlank { "fc00::/18" })
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
                // 1.12+ 新格式 DNS server 不再接受 address_resolver（legacy 字段），对应 dial 字段为 domain_resolver。
                s.addressResolver?.takeIf { it.isNotBlank() }?.let { put("domain_resolver", it) }
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

    /** 远程 .srs 使用 binary，其余 URL 使用 source；忽略查询串和 fragment。 */
    internal fun remoteRuleSetFormat(url: String): String =
        if (url.substringBefore('#').substringBefore('?').endsWith(".srs", ignoreCase = true)) "binary" else "source"

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

    private fun resolveRuleSetTag(entry: String): String {
        val t = entry.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) urlRuleSetTag(t) else t
    }

    /** 配置里实际声明的 rule_set tag 集合（显式规则集 + 资源注入 + 路由规则内联 URL）。 */
    internal fun definedRuleSetTags(state: AppState): Set<String> = buildSet {
        state.routeRuleSets.filter { it.enabled && it.tag.isNotBlank() }.forEach { add(it.tag) }
        state.settings.resources
            .filter { it.enabled && it.resType == com.sbai.data.ResourceType.CHINA_IP && it.content.isNotEmpty() }
            .forEach { add("res-${it.id.take(8)}") }
        addAll(inlineUrlRuleSets(state).keys)
    }

    private val ACTION_ONLY_KEYS = setOf("outbound", "action", "reject_method", "invert")

    private fun buildRouteRules(state: AppState, entryTag: String): List<JsonObject> {
        val rules = mutableListOf<JsonObject>()
        rules.add(buildJsonObject { put("action", "sniff") })
        // fakeIP 联动：存在启用的 fakeip DNS server 时，自动生成 fakeIP 段路由规则（放最前，优先级最高）
        state.dnsServers.firstOrNull { it.enabled && it.type == DnsServerType.FAKEIP && it.tag.isNotBlank() }
            ?.let { fake ->
                rules.add(buildJsonObject {
                    putJsonArray("ip_cidr") {
                        add(fake.inet4Range.ifBlank { "10.0.0.0/8" })
                        add(fake.inet6Range.ifBlank { "fc00::/18" })
                    }
                    put("outbound", entryTag)
                })
            }
        // 拆分隧道：已移除（用户反馈：写规则不好么？）
        // if (state.settings.splitTunnel.enabled && state.settings.splitTunnel.domains.isNotEmpty()) {
        //     rules.add(0, buildJsonObject {
        //         putJsonArray("domain_keyword") {
        //             state.settings.splitTunnel.domains.filter { it.isNotEmpty() }.forEach { add(it.trim()) }
        //         }
        //         put("outbound", "direct")
        //     })
        // }
        // 资源注入：China IP 列表 → geoip 规则集（直连）
        val chinaIpResource = state.settings.resources.firstOrNull { r -> r.enabled && r.resType == com.sbai.data.ResourceType.CHINA_IP && r.content.isNotEmpty() }
        if (chinaIpResource != null) {
            // 规则已在 rule_set 块中注入（inline format），此处无需重复；保留标记以便未来扩展
        }
        // 引用未声明的 rule_set 会让 sing-box 直接拒绝启动（rule-set not found）。
        // AND 规则含不存在的规则集永远无法命中 → 整条跳过；OR 规则仅去掉该引用。
        val definedTags = definedRuleSetTags(state)
        state.routeRules.filter { it.enabled }.forEach { rule ->
            val missing = resolveRuleSetTags(rule).filterNot { it in definedTags }
            val effective = when {
                missing.isEmpty() -> rule
                rule.logic == RuleLogic.OR -> rule.copy(
                    ruleSetTags = rule.ruleSetTags.filter { e -> resolveRuleSetTag(e) in definedTags },
                )
                else -> return@forEach
            }
            val built = buildRouteRule(effective, entryTag)
            // 去掉引用后若已无任何匹配条件，不能退化成「匹配全部流量」的规则
            if (missing.isEmpty() || built.keys.any { it !in ACTION_ONLY_KEYS }) rules.add(built)
        }
        return rules
    }

    private fun buildRouteRule(rule: RouteRule, entryTag: String): JsonObject {
        // 按字段类别拆分为子条件（OR 模式用；AND 模式平铺）
        val domainCond = buildJsonObject { putConditions(rule, Cat.DOMAIN) }
        val ipCond = buildJsonObject { putConditions(rule, Cat.IP) }
        val sourceCond = buildJsonObject { putConditions(rule, Cat.SOURCE) }
        val transportCond = buildJsonObject { putConditions(rule, Cat.TRANSPORT) }
        val appCond = buildJsonObject { putConditions(rule, Cat.APP) }
        val netEnvCond = buildJsonObject { putConditions(rule, Cat.NETENV) }
        val allConds = listOf(domainCond, ipCond, sourceCond, transportCond, appCond, netEnvCond)

        return buildJsonObject {
            if (rule.logic == RuleLogic.OR) {
                val children = buildJsonArray { allConds.filter { it.isNotEmpty() }.forEach(::add) }
                if (children.size == 0) {
                    putActionOrOutbound(rule, entryTag)
                } else if (children.size == 1) {
                    children[0].jsonObject.forEach { (k, v) -> put(k, v) }
                    if (rule.invert) put("invert", true)
                    putActionOrOutbound(rule, entryTag)
                } else {
                    put("type", "logical")
                    put("mode", "or")
                    put("rules", children)
                    if (rule.invert) put("invert", true)
                    putActionOrOutbound(rule, entryTag)
                }
            } else {
                // AND：所有字段平铺在一个 rule 对象里（sing-box 默认即 AND 语义）
                putConditions(rule, Cat.DOMAIN, Cat.IP, Cat.SOURCE, Cat.TRANSPORT, Cat.APP, Cat.NETENV)
                if (rule.invert) put("invert", true)
                putActionOrOutbound(rule, entryTag)
            }
        }
    }

    /** 输出 action 或 outbound（sing-box 完整动作集） */
    private fun JB.putActionOrOutbound(rule: RouteRule, entryTag: String) {
        when (rule.action) {
            RuleAction.ROUTE_PROXY -> put("outbound", entryTag)
            RuleAction.ROUTE_DIRECT -> put("outbound", "direct")
            RuleAction.REJECT -> {
                put("action", "reject")
                if (rule.rejectMethod == "drop") put("reject_method", "drop")
            }
            RuleAction.SNIFF -> put("action", "sniff")
            RuleAction.RESOLVE -> put("action", "resolve")
            RuleAction.HIJACK_DNS -> put("action", "hijack-dns")
            RuleAction.ROUTE_OPTIONS -> {
                put("action", "route-options")
                // route-options 至少需要一个 override 字段；当前用 override_address_override 兜底
                // 用户在编辑器里填的 override 字段（如有）会单独输出
            }
        }
    }

    private enum class Cat { DOMAIN, IP, SOURCE, TRANSPORT, APP, NETENV }

    private fun JB.putConditions(r: RouteRule, vararg cats: Cat) {
        cats.forEach { cat ->
            when (cat) {
                Cat.DOMAIN -> {
                    if (r.domains.isNotEmpty()) putJsonArray("domain") { r.domains.forEach(::add) }
                    if (r.domainSuffixes.isNotEmpty()) putJsonArray("domain_suffix") { r.domainSuffixes.forEach(::add) }
                    if (r.domainKeywords.isNotEmpty()) putJsonArray("domain_keyword") { r.domainKeywords.forEach(::add) }
                    if (r.domainRegexes.isNotEmpty()) putJsonArray("domain_regex") { r.domainRegexes.forEach(::add) }
                }
                Cat.IP -> {
                    if (r.ipCidrs.isNotEmpty()) putJsonArray("ip_cidr") { r.ipCidrs.forEach(::add) }
                    val tags = resolveRuleSetTags(r)
                    if (tags.isNotEmpty()) putJsonArray("rule_set") { tags.forEach(::add) }
                    if (r.ipIsPrivate) put("ip_is_private", true)
                }
                Cat.SOURCE -> {
                    if (r.sourceIpCidrs.isNotEmpty()) putJsonArray("source_ip_cidr") { r.sourceIpCidrs.forEach(::add) }
                    if (r.sourcePorts.isNotEmpty()) putJsonArray("source_port") { r.sourcePorts.forEach(::add) }
                    if (r.sourcePortRanges.isNotEmpty()) putJsonArray("source_port_range") { r.sourcePortRanges.forEach(::add) }
                    if (r.sourceIpIsPrivate) put("source_ip_is_private", true)
                }
                Cat.TRANSPORT -> {
                    if (r.networks.isNotEmpty()) putJsonArray("network") { r.networks.forEach(::add) }
                    if (r.protocols.isNotEmpty()) putJsonArray("protocol") { r.protocols.forEach(::add) }
                    if (r.ports.isNotEmpty()) putJsonArray("port") { r.ports.forEach(::add) }
                    if (r.portRanges.isNotEmpty()) putJsonArray("port_range") { r.portRanges.forEach(::add) }
                }
                Cat.APP -> {
                    if (r.packageNames.isNotEmpty()) putJsonArray("package_name") { r.packageNames.forEach(::add) }
                    if (r.processNames.isNotEmpty()) putJsonArray("process_name") { r.processNames.forEach(::add) }
                    if (r.processPaths.isNotEmpty()) putJsonArray("process_path") { r.processPaths.forEach(::add) }
                    if (r.users.isNotEmpty()) putJsonArray("user") { r.users.forEach(::add) }
                    if (r.userIds.isNotEmpty()) putJsonArray("user_id") { r.userIds.forEach(::add) }
                }
                Cat.NETENV -> {
                    if (r.networkTypes.isNotEmpty()) putJsonArray("network_type") { r.networkTypes.forEach(::add) }
                    if (r.wifiSsids.isNotEmpty()) putJsonArray("wifi_ssid") { r.wifiSsids.forEach(::add) }
                    if (r.wifiBssids.isNotEmpty()) putJsonArray("wifi_bssid") { r.wifiBssids.forEach(::add) }
                    if (r.inbounds.isNotEmpty()) putJsonArray("inbound") { r.inbounds.forEach(::add) }
                    if (r.clashMode.isNotBlank()) put("clash_mode", r.clashMode)
                    if (r.networkIsExpensive) put("network_is_expensive", true)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // DNS rules：手动规则在前（用户可控优先级），自动推导在后
    // ------------------------------------------------------------------

    /** 供 UI 展示：把自动推导的 DNS 规则也算出来（只读） */
    /** 只依赖 DNS 推导所需字段的重载，便于调用方做字段级缓存（避免订阅整个 AppState） */
    fun autoDnsRulesFromRouteRules(
        dnsServers: List<com.sbai.data.DnsServer>,
        dnsGroups: List<com.sbai.data.DnsGroup>,
        routeRules: List<com.sbai.data.RouteRule>,
    ): List<com.sbai.data.DnsRule> = autoDnsRules(
        AppState(
            dnsServers = dnsServers,
            dnsGroups = dnsGroups,
            routeRules = routeRules,
        )
    )

    fun autoDnsRules(state: AppState): List<DnsRule> {
        val defaultDnsTag = state.dnsServers.firstOrNull { it.enabled && it.tag.isNotBlank() }?.tag
            ?: "dns-default"
        val groupOfTag = state.dnsGroups.associate { it.name to it.serverTags }

        val result = mutableListOf<DnsRule>()

        // fakeIP 联动：fakeip DNS server 存在时，DNS 规则页显示自动 fakeIP 规则
        state.dnsServers.firstOrNull { it.enabled && it.type == DnsServerType.FAKEIP && it.tag.isNotBlank() }
            ?.let { fake ->
                result.add(
                    DnsRule(
                        id = "auto-fakeip",
                        enabled = true,
                        autoFromRouteRuleId = "fakeip",
                        name = "自动 · fakeIP",
                        queryTypes = listOf("A", "AAAA"),
                        server = fake.tag,
                    ),
                )
            }

        state.routeRules.filter { it.enabled }.forEach { rule ->
            if (rule.action == RuleAction.REJECT) return@forEach
            if (!rule.hasDomainContent) return@forEach
            val dnsServer = resolveDnsTag(rule.dnsTag, groupOfTag, state)
            val strategy = ipStrategy(rule.ipv4, rule.ipv6)
            if (dnsServer == null && strategy == null) return@forEach
            result.add(
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
                ),
            )
        }
        return result
    }

    /** 供 UI 展示：fakeIP 联动自动生成的路由规则（只读） */
    fun autoRouteRules(state: AppState): List<RouteRule> {
        val fake = state.dnsServers.firstOrNull {
            it.enabled && it.type == DnsServerType.FAKEIP && it.tag.isNotBlank()
        } ?: return emptyList()
        return listOf(
            RouteRule(
                id = "auto-fakeip",
                name = "自动 · fakeIP 段",
                enabled = true,
                action = RuleAction.ROUTE_PROXY,
                ipCidrs = listOf(
                    fake.inet4Range.ifBlank { "10.0.0.0/8" },
                    fake.inet6Range.ifBlank { "fc00::/18" },
                ),
            ),
        )
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

        // 1) 用户手动创建的 DNS 规则（按列表顺序 = 优先级，必须最先匹配）
        val dnsDefinedTags = definedRuleSetTags(state)
        state.dnsRules.filter { it.enabled }.forEach { r ->
            val server = resolveDnsTag(r.server, groupOfTag, state)
            // route 动作必须有有效 server，否则整条跳过（避免悬空引用 / 残缺规则）
            val needsServer = r.action.isBlank() || r.action == "route"
            if (needsServer && server == null) return@forEach
            // DNS 规则为 AND 语义：引用未声明的规则集既无法命中又会导致内核拒绝配置 → 跳过
            if (r.ruleSetTags.any { resolveRuleSetTag(it) !in dnsDefinedTags }) return@forEach
            result.add(buildJsonObject {
                if (r.domains.isNotEmpty()) putJsonArray("domain") { r.domains.forEach(::add) }
                if (r.domainSuffixes.isNotEmpty()) putJsonArray("domain_suffix") { r.domainSuffixes.forEach(::add) }
                if (r.domainKeywords.isNotEmpty()) putJsonArray("domain_keyword") { r.domainKeywords.forEach(::add) }
                if (r.domainRegexes.isNotEmpty()) putJsonArray("domain_regex") { r.domainRegexes.forEach(::add) }
                if (r.ruleSetTags.isNotEmpty()) putJsonArray("rule_set") { r.ruleSetTags.map(::resolveRuleSetTag).distinct().forEach(::add) }
                if (r.ipCidrs.isNotEmpty()) putJsonArray("ip_cidr") { r.ipCidrs.forEach(::add) }
                if (r.networks.isNotEmpty()) putJsonArray("network") { r.networks.forEach(::add) }
                if (r.ports.isNotEmpty()) putJsonArray("port") { r.ports.forEach(::add) }
                if (r.queryTypes.isNotEmpty()) putJsonArray("query_type") { r.queryTypes.forEach(::add) }
                if (r.ipStrategy.isNotBlank()) put("ip_strategy", r.ipStrategy)
                if (r.disableCache) put("disable_cache", true)
                r.rewriteTtl?.let { put("rewrite_ttl", it) }
                r.clientSubnet?.takeIf { it.isNotBlank() }?.let { put("client_subnet", it) }
                if (r.timeout.isNotBlank()) put("timeout", r.timeout)

                // 动作
                when (r.action) {
                    "reject" -> {
                        put("action", "reject")
                        if (r.rcode.isNotBlank() && r.rcode != "success") put("rcode", r.rcode)
                    }
                    "route-options" -> {
                        put("action", "route-options")
                        if (r.rcode.isNotBlank() && r.rcode != "success") put("rcode", r.rcode)
                        if (r.answers.isNotEmpty()) putJsonArray("answer") { r.answers.forEach(::add) }
                        if (r.ns.isNotEmpty()) putJsonArray("ns") { r.ns.forEach(::add) }
                        if (r.extra.isNotEmpty()) putJsonArray("extra") { r.extra.forEach(::add) }
                    }
                    "pre-defined" -> put("action", "pre-defined")
                    else -> {
                        // route：server 已在上面校验非空
                        put("server", server!!)
                    }
                }
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
            if (rule.action == RuleAction.REJECT) return@forEach
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

        // 4) fakeIP 联动：fakeip DNS server 存在时，query_type A/AAAA 兜底走 fakeip。
        // 放最后（兜底），否则会吞掉上面的手动规则（query_type 规则无条件匹配所有域名）。
        state.dnsServers.firstOrNull { it.enabled && it.type == DnsServerType.FAKEIP && it.tag.isNotBlank() }
            ?.let { fake ->
                result.add(buildJsonObject {
                    putJsonArray("query_type") { add("A"); add("AAAA") }
                    put("server", fake.tag)
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

    /**
     * 判断节点是否为 sing-box endpoint 类型（1.12+：wireguard 等是 endpoint 非 outbound）。
     * endpoint 节点进 config.endpoints[]，其余进 outbounds[]。
     */
    private fun isEndpointNode(node: com.sbai.data.ProxyNode): Boolean =
        runCatching {
            json.parseToJsonElement(node.outboundJson).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull() in ENDPOINT_TYPES

    /** sing-box 1.12+ 中作为 endpoint（config.endpoints[]）的协议类型 */
    private val ENDPOINT_TYPES = setOf("wireguard", "wg", "tailscale")

    /**
     * 注入 DPI 硬化 TLS 分片（参考 LxBox 016）：对带 tls 块的 outbound 写入
     * tls.fragment / tls.record_fragment / tls.fragment_fallback_delay。
     *
     * 仅第一跳（无 detour 的 outbound）注入；有 detour 的节点分片由内核决定，不注入。
     * 无 tls 块的协议（shadowsocks、hysteria2 等）不注入，避免污染配置。
     *
     * @return 注入后的 JsonObject（无变化时返回原对象）
     */
    private fun applyTlsFragment(obj: JsonObject, state: AppState): JsonObject {
        val s = state.settings
        if (!s.tlsFragment && !s.tlsRecordFragment) return obj
        val tls = obj["tls"]?.jsonObject ?: return obj
        // detour 节点：分片交由内核决定（detour 下内核自行开启 record_fragment）
        if (obj["detour"] != null) return obj
        // system TLS 引擎下 fragment 与 uTLS/REALITY 冲突，内核会拒绝；这里仅在有 utls/reality 时跳过 fragment
        val hasUtls = tls["utls"] != null
        val hasReality = tls["reality"] != null
        val hasEch = tls["ech"] != null

        // fragment 与 uTLS/REALITY/ECH 互斥：这些技术本身已改造 ClientHello，再分片会被内核拒绝或无效
        val wantFragment = s.tlsFragment && !hasUtls && !hasReality && !hasEch
        if (!s.tlsRecordFragment && !wantFragment) return obj

        val delay = s.tlsFragmentFallbackDelay.ifBlank { "500ms" }
        val newTls = buildJsonObject {
            tls.forEach { (k, v) -> put(k, v) }
            if (s.tlsRecordFragment) put("record_fragment", true)
            if (wantFragment) {
                put("fragment", true)
                put("fragment_fallback_delay", delay)
            }
        }
        return buildJsonObject {
            obj.forEach { (k, v) -> put(k, v) }
            put("tls", newTls)
        }
    }

    /** 配置校验：调用 libbox.checkConfig；失败时返回可读错误（5 秒超时） */
    fun validate(configJson: String): String? {
        return runCatching {
            // checkConfig 是 JNI 同步调用，必须在 IO 线程执行，加超时保护
            var result: String? = null
            val lock = Any()
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "config-validator").apply { isDaemon = true }
            }.submit {
                try {
                    io.nekohasekai.libbox.Libbox.checkConfig(configJson)
                    synchronized(lock) { result = null }
                } catch (e: Exception) {
                    synchronized(lock) { result = e.message ?: "unknown error" }
                }
            }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            result
        }.getOrElse { e ->
            android.util.Log.e("SingBoxConfig", "checkConfig timeout/failed", e)
            if (e is java.util.concurrent.TimeoutException) "配置校验超时（可能是内核不兼容）" else e.message ?: "未知错误"
        }
    }
}
