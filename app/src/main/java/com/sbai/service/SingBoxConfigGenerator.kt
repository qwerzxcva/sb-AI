package com.sbai.service

import com.sbai.data.AppState
import com.sbai.data.TunStack
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

        val dnsServers = buildDnsServers(state, entryTag).toMutableList()
        val defaultDnsTag = state.dnsServers.firstOrNull { it.enabled && it.tag.isNotBlank() }?.tag
            ?: "dns-default"
        val routeRules = buildRouteRules(state, entryTag)
        val dnsRules = buildDnsRules(state, defaultDnsTag).toMutableList()
        appendDnsPolicyRules(state, dnsServers, dnsRules, defaultDnsTag)

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
            add(buildJsonObject { put("type", "block"); put("tag", "block") })
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

            // ---- TUN 空闲挂起（SPEC 020，sb-lxf 内核 Leadaxe 扩展；内核未开启 with_lx_idle_suspend 时为 no-op） ----
            val idleSuspend = idleDurationToGo(state.settings.idleSuspendDuration)
            val idleTeardown = idleDurationToGo(state.settings.idleTeardownDuration)
            if (idleSuspend.isNotEmpty() || idleTeardown.isNotEmpty()) {
                putJsonObject("lx") {
                    putJsonObject("wg") {
                        if (idleSuspend.isNotEmpty()) put("idle_suspend", idleSuspend)
                        if (idleTeardown.isNotEmpty()) put("idle_teardown", idleTeardown)
                    }
                }
            }

            putJsonObject("dns") {
                putJsonArray("servers") { dnsServers.forEach(::add) }
                putJsonArray("rules") { dnsRules.forEach(::add) }
                put("strategy", state.settings.dnsStrategy)
                put("independent_cache", true)
                put("final", defaultDnsTag)
                // DNS 缓存调优（参考 Throne：内核会直接拒绝 optimistic 与缓存开关共存的配置）
                if (state.settings.dnsCacheCapacity > 0) {
                    // 内核忽略 <1024 的值，这里只在有效时输出
                    if (state.settings.dnsCacheCapacity >= 1024) {
                        put("cache_capacity", state.settings.dnsCacheCapacity)
                    }
                }
                if (state.settings.dnsDisableCache) put("disable_cache", true)
                if (state.settings.dnsDisableExpire) put("disable_expire", true)
                // optimistic 与 disable_cache / disable_expire 互斥，冲突时不输出
                if (state.settings.dnsOptimistic &&
                    !state.settings.dnsDisableCache &&
                    !state.settings.dnsDisableExpire
                ) {
                    val optTimeout = state.settings.dnsOptimisticTimeout.trim()
                    if (optTimeout.isEmpty()) {
                        put("optimistic", true)
                    } else {
                        putJsonObject("optimistic") {
                            put("enabled", true)
                            put("timeout", optTimeout)
                        }
                    }
                }
                if (state.settings.dnsQueryTimeout.isNotBlank()) {
                    put("timeout", state.settings.dnsQueryTimeout.trim())
                }
                if (state.settings.dnsReverseMapping) put("reverse_mapping", true)
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
                            put("format", "source")
                            put("url", url)
                        })
                    }
                }
                put("final", state.settings.finalOutbound.ifBlank { entryTag })
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
                    // dns_mode=hijack（1.14+ 默认，显式写出）：内核把 TUN 派生的 DNS 地址
                    // （address 首段 +1）上的连接劫持进 DNS 模块。不写则依赖默认值，
                    // 显式写出可避免上游改默认时行为漂移。
                    put("dns_mode", "hijack")
                    // route_exclude_address：把 TUN 自身网段与回环/广播排除在路由外。
                    // 关键：TUN 的 DNS 地址 = address 首段 +1，落在 TUN 网段内；
                    // 若不排除，发往该地址的 DNS 查询会被送回隧道形成环，
                    // 表现为「VPN 显示已连接但域名全部解析失败」（参考 Throne D13）。
                    putJsonArray("route_exclude_address") {
                        add("127.0.0.0/8")
                        add("255.255.255.255/32")
                        add(state.settings.tunAddress.ifBlank { "172.18.0.1/30" })
                        if (state.settings.ipv6Route) {
                            add(state.settings.tunAddress6.ifBlank { "fdfe:dcba:9876::1/126" })
                        }
                    }
                    // TUN 网络栈：system / gvisor / mixed / go / tsbAI（go=1.15+ 新 Go 栈，tsbAI=sb-lxf 魔改含 splice/GSO/multi_queue）
                    put("stack", state.settings.tunStack.wireName)
                    // tsbAI/go 栈可选 multi_queue（Linux 多队列网络引擎）
                    if (state.settings.tunMultiQueue &&
                        (state.settings.tunStack == TunStack.TSB_AI || state.settings.tunStack == TunStack.GO)) {
                        put("multi_queue", true)
                    }
                    // sing-box 1.13 起 inbound 上不再有 sniff/sniff_override_destination
                    // （内核 option/tun.go 已无这两个字段，带上会被拒绝启动）；
                    // 嗅探改由 route.rules 的 {"action":"sniff"} 承担（见 buildRouteRules 首条）。

                    // 系统 HTTP 代理下发（Android 10+；内核 tun platform.http_proxy）
                    // 让尊重系统代理的应用走本地代理而不进隧道；老版本系统由平台层忽略。
                    if (state.settings.tunHttpProxyEnabled) {
                        putJsonObject("platform") {
                            putJsonObject("http_proxy") {
                                put("enabled", true)
                                put("server", "127.0.0.1")
                                put("server_port", state.settings.tunHttpProxyPort)
                                if (state.settings.tunHttpProxyBypassDomains.isNotEmpty()) {
                                    putJsonArray("bypass_domain") {
                                        state.settings.tunHttpProxyBypassDomains.forEach(::add)
                                    }
                                }
                            }
                        }
                    }

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

    /**
     * 把用户手写的 DNS 地址（如 "tls://8.8.8.8" / "223.5.5.5"）解析成 sing-box DNS server 对象。
     * 用于 fallback / nameserver-policy 这类「引用真实 server」的规则：先生成 server，再用其 tag。
     * 已存在同 tag 的 server 时直接复用，避免重复声明。
     */
    private fun dnsServerFromAddress(tag: String, address: String): JsonObject? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return null
        val scheme = trimmed.substringBefore("://")
        return buildJsonObject {
            put("tag", tag)
            when (scheme) {
                "tls" -> {
                    put("type", "tls")
                    put("server", splitHostAndPath(trimmed).first)
                }
                "https" -> {
                    put("type", "https")
                    val (host, path) = splitHostAndPath(trimmed)
                    put("server", host)
                    if (path != null) put("path", path)
                }
                "quic" -> {
                    put("type", "quic")
                    put("server", splitHostAndPath(trimmed).first)
                }
                "h3" -> {
                    put("type", "h3")
                    put("server", splitHostAndPath(trimmed).first)
                }
                "tcp" -> {
                    put("type", "tcp")
                    put("server", splitHostAndPath(trimmed).first)
                }
                else -> {
                    put("type", "udp")
                    put("server", trimmed)
                }
            }
        }
    }

    /**
     * 把 DNS 高级设置（fallback / fakeIP 过滤 / nameserver-policy）翻译成 sing-box 原生 dns.rules。
     * sing-box 没有 Clash 的 fallback/fallback-filter/fakeip-filter/nameserver-policy 顶层字段，
     * 直接用会让内核拒绝启动；这里全部用等价的 rule 表达。
     * 可能向 [servers] 追加新 server（裸地址场景），因此两个列表都必须是可变的。
     */
    private fun appendDnsPolicyRules(
        state: AppState,
        servers: MutableList<JsonObject>,
        rules: MutableList<JsonObject>,
        defaultDnsTag: String,
    ) {
        val settings = state.settings

        // 1) fallback：响应 IP 命中给定 CIDR 时改走 fallback server（match_response 评估响应）
        val fallbackServers = settings.dnsFallbackServers.filter { it.isNotBlank() }
        val fallbackCidrs = settings.dnsFallbackIpcidr.filter { it.isNotBlank() }
        if (fallbackServers.isNotEmpty() && fallbackCidrs.isNotEmpty()) {
            val fallbackTag = ensureDnsServerTag(servers, fallbackServers.first(), "dns-fallback")
            rules.add(buildJsonObject {
                put("action", "route")
                put("server", fallbackTag)
                put("match_response", true)
                putJsonArray("ip_cidr") { fallbackCidrs.forEach(::add) }
            })
        }

        // 2) fakeIP 过滤：这些域名不走 fakeIP，改走真实 DNS
        if (settings.dnsFakeipFilter.isNotEmpty()) {
            rules.add(buildJsonObject {
                put("action", "route")
                put("server", defaultDnsTag)
                if (settings.dnsFakeipFilterMode == "whitelist") put("invert", true)
                putJsonArray("domain") { settings.dnsFakeipFilter.forEach(::add) }
            })
        }
        if (settings.dnsFakeipFilterPatterns.isNotEmpty()) {
            rules.add(buildJsonObject {
                put("action", "route")
                put("server", defaultDnsTag)
                putJsonArray("domain_regex") { settings.dnsFakeipFilterPatterns.forEach(::add) }
            })
        }

        // 3) nameserver-policy：域名 -> 指定 DNS（tag 或裸地址）
        settings.dnsNameserverPolicy
            .filter { it.isNotBlank() && it.contains("=") }
            .forEach { entry ->
                val eq = entry.indexOf('=')
                val domain = entry.substring(0, eq).trim()
                val target = entry.substring(eq + 1).trim()
                if (domain.isEmpty() || target.isEmpty()) return@forEach
                val serverTag = resolvePolicyTag(servers, target) ?: return@forEach
                rules.add(buildJsonObject {
                    put("action", "route")
                    put("server", serverTag)
                    when {
                        domain.startsWith("+.") ->
                            putJsonArray("domain_suffix") { add(domain.removePrefix("+.")) }
                        domain.startsWith("*.") ->
                            putJsonArray("domain_suffix") { add(domain.removePrefix("*.")) }
                        else -> putJsonArray("domain") { add(domain) }
                    }
                })
            }
    }

    /**
     * 确保 [address] 对应的 DNS server 已存在于 [servers]，返回其 tag；不存在则追加后返回。
     * 供 fallback / nameserver-policy 等需要引用真实 server 的规则使用。
     */
    private fun ensureDnsServerTag(
        servers: MutableList<JsonObject>,
        address: String,
        preferredTag: String,
    ): String {
        val target = dnsServerFromAddress(preferredTag, address) ?: return preferredTag
        val existing = servers.firstOrNull { it == target || it["server"] == target["server"] && it["type"] == target["type"] }
        if (existing != null) {
            return existing["tag"]?.jsonPrimitive?.content ?: preferredTag
        }
        servers.add(target)
        return preferredTag
    }

    /**
     * 解析 nameserver-policy 的目标：既可以是已声明的 DNS server tag，也可以是裸地址。
     * 返回 null 表示无法解析（调用方应跳过该规则，避免悬空引用导致内核拒绝启动）。
     */
    private fun resolvePolicyTag(servers: MutableList<JsonObject>, target: String): String? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        val asTag = servers.firstOrNull { it["tag"]?.jsonPrimitive?.content == trimmed }
        if (asTag != null) return trimmed
        // 裸地址：作为新的 udp/tls/https server 追加
        val tag = "dns-policy-" + trimmed.hashCode().toUInt().toString(16)
        val server = dnsServerFromAddress(tag, trimmed) ?: return null
        servers.add(server)
        return tag
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
        // resolve 动作（参考 Throne）：对 TUN 入站连接按策略预解析域名，
        // 让后续按域名匹配的规则能拿到 sniff 不到的域名（如直连 IP 场景）。
        val resolveStrategy = state.settings.dnsResolveStrategy.trim()
        if (resolveStrategy.isNotEmpty()) {
            rules.add(buildJsonObject {
                putJsonArray("inbound") { add("tun-in") }
                put("action", "resolve")
                put("strategy", resolveStrategy)
            })
        }
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
        state.routeRules.filter { it.enabled }.forEach { rule ->
            rules.add(buildRouteRule(rule, entryTag))
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
            RuleAction.ROUTE_PROXY -> put("outbound", rule.outboundTag.ifBlank { entryTag })
            RuleAction.ROUTE_DIRECT -> put("outbound", rule.outboundTag.ifBlank { "direct" })
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
        state.dnsRules.filter { it.enabled }.forEach { r ->
            val server = resolveDnsTag(r.server, groupOfTag, state)
            // route 动作必须有有效 server，否则整条跳过（避免悬空引用 / 残缺规则）
            val needsServer = r.action.isBlank() || r.action == "route"
            if (needsServer && server == null) return@forEach
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
    /** 把毫秒转成 Go time.Duration 可解析的字符串（如 "5m" / "90s" / "1h30m"）；0 返回空串。 */
    private fun idleDurationToGo(ms: Long): String {
        if (ms <= 0L) return ""
        val sec = ms / 1000
        val min = sec / 60
        val hr = min / 60
        return when {
            hr > 0 -> {
                val remMin = min % 60
                if (remMin > 0) "${hr}h${remMin}m" else "${hr}h"
            }
            min > 0 -> "${min}m"
            else -> "${sec}s"
        }
    }

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
