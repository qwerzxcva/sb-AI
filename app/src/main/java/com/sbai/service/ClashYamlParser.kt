package com.sbai.service

import com.sbai.data.DnsRule
import com.sbai.data.RouteRule
import com.sbai.data.RuleAction
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.yaml.snakeyaml.Yaml

/**
 * Clash 系列配置解析（YAML）。
 *
 * 很多机场只提供 Clash 格式订阅。本解析器把 Clash 的
 * `proxies` / `proxy-groups` / `rules` 转换为 sb-AI 的节点、负载均衡组与路由规则，
 * 语义对齐 LxBox「自动识别配置里的节点、规则」的行为。
 *
 * 支持的 Clash 协议：ss / vmess / vless / trojan / hysteria / hysteria2 /
 * http / socks5 / wireguard / tuic。
 */
object ClashYamlParser {

    data class Parsed(
        val nodes: List<ShareLinkParser.ParsedNode>,
        /** Clash proxy-groups → 建议的负载均衡组名与其成员 */
        val groups: List<Group>,
        /** Clash rules → 路由规则 */
        val routeRules: List<RouteRule>,
    )

    data class Group(
        val name: String,
        val type: String,      // select / url-test / fallback / load-balance
        val members: List<String>,
        val url: String?,
        val intervalSeconds: Int?,
        val tolerance: Int?,
    )

    fun parse(yamlText: String): Parsed? = runCatching {
        val root = Yaml().load<Any>(yamlText) as? Map<*, *> ?: return null

        val nodes = parseProxies(root["proxies"])
        val groups = parseGroups(root["proxy-groups"], nodes.map { it.name }.toSet())
        val rules = parseRules(root["rules"])
        if (nodes.isEmpty() && rules.isEmpty()) null else Parsed(nodes, groups, rules)
    }.getOrNull()

    // ------------------------------------------------------------------
    // proxies → sing-box outbound
    // ------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun parseProxies(raw: Any?): List<ShareLinkParser.ParsedNode> {
        val list = raw as? List<Any?> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            runCatching { convertProxy(m) }.getOrNull()
        }
    }

    private fun convertProxy(m: Map<String, Any?>): ShareLinkParser.ParsedNode? {
        val type = m.str("type") ?: return null
        val name = m.str("name") ?: return null
        val server = m.str("server").orEmpty()
        val port = m.int("port")

        val sbType = when (type.lowercase()) {
            "ss", "shadowsocks" -> "shadowsocks"
            "vmess" -> "vmess"
            "vless" -> "vless"
            "trojan" -> "trojan"
            "hysteria" -> "hysteria"
            "hysteria2" -> "hysteria2"
            "http" -> "http"
            "socks", "socks5" -> "socks"
            "wireguard" -> "wireguard"
            "tuic" -> "tuic"
            else -> return null   // snell 等 sing-box 无对应实现
        }

        val outbound = buildJsonObject {
            put("type", sbType)
            put("tag", name)
            if (server.isNotBlank()) put("server", server)
            if (port != null) put("server_port", port)

            when (sbType) {
                "shadowsocks" -> {
                    put("method", m.str("cipher") ?: "none")
                    put("password", m.str("password").orEmpty())
                    // Clash 插件 → sing-box plugin
                    m.str("plugin")?.takeIf { it.isNotBlank() && it != "none" }?.let { plugin ->
                        put("plugin", when (plugin) {
                            "obfs" -> "obfs-local"
                            "v2ray-plugin" -> "v2ray-plugin"
                            "shadow-tls" -> "shadow-tls"
                            else -> plugin
                        })
                        val opts = m.map("plugin-opts")
                        if (opts.isNotEmpty()) {
                            val parts = mutableListOf<String>()
                            if (plugin == "obfs") {
                                opts.str("mode")?.let { parts += "obfs=$it" }
                                opts.str("host")?.let { parts += "obfs-host=$it" }
                            } else {
                                opts.forEach { (k, v) -> if (v != null) parts += "$k=$v" }
                            }
                            if (parts.isNotEmpty()) put("plugin_opts", parts.joinToString(";"))
                        }
                    }
                }

                "vmess" -> {
                    put("uuid", m.str("uuid").orEmpty())
                    put("security", m.str("cipher") ?: "auto")
                    m.int("alterId")?.takeIf { it > 0 }?.let { put("alter_id", it) }
                    putTls(this, m)
                    putTransport(this, m)
                    putMux(this, m)
                }

                "vless" -> {
                    put("uuid", m.str("uuid").orEmpty())
                    m.str("flow")?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
                    putTls(this, m)
                    putTransport(this, m)
                    putMux(this, m)
                }

                "trojan" -> {
                    put("password", m.str("password").orEmpty())
                    putTls(this, m)
                    putTransport(this, m)
                    putMux(this, m)
                }

                "hysteria" -> {
                    put("up_mbps", m.int("up") ?: 100)
                    put("down_mbps", m.int("down") ?: 100)
                    m.str("auth-str")?.takeIf { it.isNotBlank() }?.let { put("auth_str", it) }
                        ?: m.str("password")?.takeIf { it.isNotBlank() }?.let { put("auth_str", it) }
                    putTls(this, m, forceSni = true)
                }

                "hysteria2" -> {
                    put("password", m.str("password").orEmpty())
                    m.str("obfs-password")?.takeIf { it.isNotBlank() }?.let { put("obfs_password", it) }
                    putTls(this, m, forceSni = true)
                }

                "http", "socks" -> {
                    m.str("username")?.takeIf { it.isNotBlank() }?.let { put("username", it) }
                    m.str("password")?.takeIf { it.isNotBlank() }?.let { put("password", it) }
                    if (sbType == "socks") put("version", "5")
                    // Clash http/socks 也支持 tls
                    if (m.bool("tls") == true) putTls(this, m)
                }

                "wireguard" -> {
                    put("private_key", m.str("private-key").orEmpty())
                    put("peer_public_key", m.str("public-key").orEmpty())
                    m.str("pre-shared-key")?.takeIf { it.isNotBlank() }?.let { put("pre_shared_key", it) }
                    val addrs = mutableListOf<String>()
                    m.str("ip")?.let { addrs += it }
                    m.str("ipv6")?.let { addrs += it }
                    (m["ip"] as? List<*>)?.forEach { addrs += it.toString() }
                    if (addrs.isNotEmpty()) putJsonArray("local_address") { addrs.forEach { add(it) } }
                    m.int("mtu")?.let { put("mtu", it) }
                }

                "tuic" -> {
                    put("uuid", m.str("uuid").orEmpty())
                    m.str("password")?.takeIf { it.isNotBlank() }?.let { put("password", it) }
                    m.str("congestion-controller")?.takeIf { it.isNotBlank() }
                        ?.let { put("congestion_control", it) }
                    m.str("udp-relay-mode")?.takeIf { it.isNotBlank() }?.let { put("udp_relay_mode", it) }
                    (m["alpn"] as? List<*>)?.let { alpn ->
                        putJsonArray("tls") {}  // placeholder replaced below
                    }
                    putTls(this, m, alpnFrom = m["alpn"] as? List<*>)
                }
            }
        }
        return ShareLinkParser.ParsedNode(name, outbound.toString())
    }

    // ------------------------------------------------------------------
    // TLS / transport / multiplex
    // ------------------------------------------------------------------

    private fun putTls(
        b: kotlinx.serialization.json.JsonObjectBuilder,
        m: Map<String, Any?>,
        forceSni: Boolean = false,
        alpnFrom: List<*>? = null,
    ) {
        val tlsOn = m.bool("tls") == true || forceSni ||
                m.str("sni")?.isNotBlank() == true || m.str("servername")?.isNotBlank() == true ||
                m.map("reality-opts").isNotEmpty()
        if (!tlsOn) return

        b.putJsonObject("tls") {
            put("enabled", true)
            val sni = m.str("sni") ?: m.str("servername")
            if (!sni.isNullOrBlank()) put("server_name", sni)
            if (m.bool("skip-cert-verify") == true) put("insecure", true)

            // uTLS 指纹
            m.str("client-fingerprint")?.takeIf { it.isNotBlank() }?.let { fp ->
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", fp)
                }
            }

            // ALPN
            val alpn = alpnFrom ?: (m["alpn"] as? List<*>)
            if (!alpn.isNullOrEmpty()) {
                putJsonArray("alpn") { alpn.forEach { add(it.toString()) } }
            }

            // REALITY
            val reality = m.map("reality-opts")
            if (reality.isNotEmpty()) {
                putJsonObject("reality") {
                    put("enabled", true)
                    reality.str("public-key")?.let { put("public_key", it) }
                    reality.str("short-id")?.let { put("short_id", it) }
                }
            }
        }
    }

    private fun putTransport(
        b: kotlinx.serialization.json.JsonObjectBuilder,
        m: Map<String, Any?>,
    ) {
        val network = m.str("network")?.lowercase().orEmpty()
        when (network) {
            "ws" -> {
                val opts = m.map("ws-opts")
                b.putJsonObject("transport") {
                    put("type", "ws")
                    opts.str("path")?.let { put("path", it) }
                    val headers = opts.map("headers")
                    headers.str("Host")?.let {
                        putJsonObject("headers") { put("Host", it) }
                    }
                    opts.int("max-early-data")?.let { put("max_early_data", it) }
                    opts.str("early-data-header-name")?.let { put("early_data_header_name", it) }
                }
            }
            "grpc" -> {
                val opts = m.map("grpc-opts")
                b.putJsonObject("transport") {
                    put("type", "grpc")
                    (opts.str("grpc-service-name") ?: m.str("grpc-opts"))?.let { put("service_name", it) }
                }
            }
            "h2" -> {
                val opts = m.map("h2-opts")
                b.putJsonObject("transport") {
                    put("type", "http")
                    opts.str("path")?.let { put("path", it) }
                    (opts["host"] as? List<*>)?.let { hosts ->
                        putJsonArray("host") { hosts.forEach { add(it.toString()) } }
                    }
                }
            }
            // tcp / 空 → 不需要 transport
        }
    }

    private fun putMux(b: kotlinx.serialization.json.JsonObjectBuilder, m: Map<String, Any?>) {
        val smux = m.map("smux")
        if (smux.isEmpty() || smux.bool("enabled") != true) return
        b.putJsonObject("multiplex") {
            put("enabled", true)
            smux.str("protocol")?.let { put("protocol", it) }
            smux.int("max-connections")?.let { put("max_connections", it) }
            smux.bool("padding")?.let { put("padding", it) }
            val brutal = smux.map("brutal-opts")
            if (brutal.isNotEmpty() && smux.bool("brutal") != false) {
                putJsonObject("brutal") {
                    put("enabled", true)
                    brutal.int("up-mbps")?.let { put("up_mbps", it) }
                    brutal.int("down-mbps")?.let { put("down_mbps", it) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // proxy-groups
    // ------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun parseGroups(raw: Any?, nodeNames: Set<String>): List<Group> {
        val list = raw as? List<Any?> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            val name = m.str("name") ?: return@mapNotNull null
            val type = m.str("type") ?: "select"
            val members = (m["proxies"] as? List<*>)
                ?.mapNotNull { it?.toString() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            Group(
                name = name,
                type = type,
                members = members,
                url = m.str("url"),
                intervalSeconds = m.int("interval"),
                tolerance = m.int("tolerance"),
            )
        }
    }

    // ------------------------------------------------------------------
    // rules → RouteRule
    // ------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun parseRules(raw: Any?): List<RouteRule> {
        val list = raw as? List<Any?> ?: return emptyList()
        return list.mapNotNull { it?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(::parseRuleLine)
    }

    /**
     * Clash 规则行：`TYPE,ARG,POLICY[,no-resolve]`
     * 策略名 DIRECT/REJECT 映射为固定动作，其余（分组名）映射为代理。
     */
    private fun parseRuleLine(line: String): RouteRule? {
        val parts = line.split(',').map { it.trim() }
        if (parts.size < 2) return null

        // MATCH / FINAL 是兜底规则，不作为普通路由规则导入（由 route.final 承担）
        val kind = parts[0].uppercase()
        if (kind == "MATCH" || kind == "FINAL") return null

        val policy = when (kind) {
            // 无参数类型：TYPE,POLICY
            "GEOIP", "MATCH", "FINAL" -> parts.getOrNull(1)
            else -> parts.getOrNull(2)
        } ?: return null

        val action = when (policy.uppercase()) {
            "DIRECT" -> RuleAction.ROUTE_DIRECT
            "REJECT" -> RuleAction.REJECT
            else -> RuleAction.ROUTE_PROXY
        }

        val arg = if (kind == "GEOIP") "" else parts.getOrNull(1).orEmpty()

        return when (kind) {
            "DOMAIN" -> RouteRule(name = line, action = action, domains = listOf(arg))
            "DOMAIN-SUFFIX" -> RouteRule(name = line, action = action, domainSuffixes = listOf(arg))
            "DOMAIN-KEYWORD" -> RouteRule(name = line, action = action, domainKeywords = listOf(arg))
            "DOMAIN-REGEX" -> RouteRule(name = line, action = action, domainRegexes = listOf(arg))
            "IP-CIDR", "IP-CIDR6", "IP-SUFFIX" ->
                RouteRule(name = line, action = action, ipCidrs = listOf(arg))
            "SRC-IP" -> RouteRule(name = line, action = action, sourceIpCidrs = listOf(arg))
            "SRC-PORT" -> RouteRule(
                name = line, action = action,
                sourcePorts = arg.toIntOrNull()?.let { listOf(it) } ?: emptyList(),
                sourcePortRanges = if (arg.contains(':')) listOf(arg) else emptyList(),
            )
            "DST-PORT" -> RouteRule(
                name = line, action = action,
                ports = arg.toIntOrNull()?.let { listOf(it) } ?: emptyList(),
                portRanges = if (arg.contains(':')) listOf(arg) else emptyList(),
            )
            "PROCESS-NAME" -> RouteRule(name = line, action = action, processNames = listOf(arg))
            "PROCESS-PATH" -> RouteRule(name = line, action = action, processPaths = listOf(arg))
            "NETWORK" -> RouteRule(name = line, action = action, networks = listOf(arg.lowercase()))
            "IN-PORT", "IN-TYPE" -> null   // sing-box 无直接对应，跳过
            "RULE-SET" -> RouteRule(name = line, action = action, ruleSetTags = listOf(arg))
            "GEOIP" -> null   // 需要 GeoIP 数据库，跳过（避免生成无效规则）
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Map 取值辅助（YAML 的 key 大小写/类型不定）
    // ------------------------------------------------------------------

    private fun Map<String, Any?>.str(key: String): String? =
        this[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" }

    private fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
        is Boolean -> v
        is String -> v.equals("true", true)
        is Number -> v.toInt() != 0
        else -> null
    }

    private fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
        (this[key] as? Map<String, Any?>) ?: emptyMap()
}
