package com.sbai.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder
import java.util.Base64

/**
 * 分享链接解析器：vless:// / vmess:// / trojan:// / ss:// / hysteria2://(hy2://)
 * → sing-box outbound JSON。
 *
 * 纯 Kotlin 实现，不依赖 Android 框架（除 Base64），可直接进单元测试。
 */
object ShareLinkParser {

    private val json = Json { ignoreUnknownKeys = true }

    data class ParsedNode(val name: String, val outboundJson: String)

    /**
     * 判定「信息节点」：机场把「剩余流量 / 套餐到期 / 官网公告 / 使用建议」伪装成代理节点
     * 塞进 proxies 列表。这些节点名称含明确信息关键词，且通常复用真实节点的 server/uuid。
     *
     * 判据（保守，避免误伤真实节点）：
     *  1. 强关键词：名称含「剩余流量」「已用流量」「总流量」「流量重置」「套餐」
     *     「重置剩余」「官网」「公告」「建议」「续费」「距离下次」等 → 必是信息节点。
     *     其中「套餐」「官网」「续费」要求带量词或链接才成立，避免误伤「套餐专线」这类真实节点。
     *  2. 弱关键词「流量」「到期」+ 名称含量化信息（50GB / 27 天 / 2026-11-02 / 50%）→ 信息节点。
     */
    fun isInfoNode(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        // 1) 直接强关键词
        val strong = listOf(
            "剩余流量", "已用流量", "总流量", "流量重置", "重置剩余",
            "距离下次", "流量剩余", "过期时间", "有效期", "公告",
        )
        if (strong.any { n.contains(it) }) return true
        // 2) 需要「量词/链接」佐证的强关键词：仅含「套餐」「官网」「续费」时不能误伤
        val qualifiedStrong = mapOf(
            "套餐" to Regex("""\d"""),
            "官网" to Regex("""https?://|www\.|\d"""),
            "续费" to Regex("""https?://|www\.|\d"""),
            "建议" to Regex("""请|切换|卡|专线|推荐"""),
        )
        if (qualifiedStrong.any { (keyword, proof) ->
                n.contains(keyword) && proof.containsMatchIn(n)
            }
        ) return true
        // 3) 弱关键词「流量」「到期」必须带量化数据，避免「香港流量优化」被误杀
        val weak = listOf("流量", "到期")
        if (weak.any { n.contains(it) } && Regex(
                """(?:\d+(?:\.\d+)?\s*(?:[KMGT]i?B|%)|\d+\s*(?:天|日|小时|GB|MB|TB)|\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{4}\s*年|(?<![\w/-])\d{4}(?![\w/-]))""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(n)
        ) return true
        return false
    }

    /** 解析单行分享链接；不支持的协议返回 null */
    fun parse(line: String): ParsedNode? {
        val trimmed = SubscriptionFormat.cleanText(line)
        if (trimmed.isEmpty()) return null
        return runCatching {
            when {
                trimmed.startsWith("vless://") -> parseVless(trimmed)
                trimmed.startsWith("vmess://") -> parseVmess(trimmed)
                trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
                trimmed.startsWith("ss://") -> parseShadowsocks(trimmed)
                trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") -> parseHysteria2(trimmed)
                trimmed.startsWith("wireguard://") || trimmed.startsWith("wg://") -> parseWireguard(trimmed)
                else -> null
            }
        }.getOrNull()
    }

    /** 解析整段订阅内容（可能整体 base64，也可能逐行明文），返回全部节点 */
    fun parseSubscription(content: String): List<ParsedNode> {
        val decoded = decodeMaybeBase64(content)
        return decoded.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(::parse)
            .toList()
    }

    internal fun decodeMaybeBase64(content: String): String {
        val trimmed = SubscriptionFormat.cleanText(content)
        // 已是明文链接则直接返回
        if (trimmed.lines().any { it.contains("://") }) return trimmed
        return decodeB64(trimmed)?.let { String(it, Charsets.UTF_8) } ?: trimmed
    }

    /** 宽松 base64 解码：容忍 MIME 换行 / URL-safe / 缺 padding */
    internal fun decodeB64(s: String): ByteArray? {
        // MIME decoder silently discards '-'/'_' and even arbitrary punctuation.
        // Strip whitespace only, normalize URL-safe alphabet, then decode strictly.
        val clean = s.trim { it.isWhitespace() || it == '\uFEFF' }
            .replace(Regex("\\s+"), "").replace('-', '+').replace('_', '/')
        if (clean.isEmpty() || !Regex("[A-Za-z0-9+/]*={0,2}").matches(clean)) return null
        val candidates = listOf({ Base64.getDecoder().decode(pad(clean)) })
        for (c in candidates) {
            val r = runCatching { c() }
            if (r.isSuccess) return r.getOrNull()
        }
        return null
    }

    private fun pad(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }

    // ------------------------------------------------------------------
    // URL 分解辅助
    // ------------------------------------------------------------------

    private class UrlParts(
        val userInfo: String,     // @ 之前（已解码）
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val name: String,         // # 之后（已解码）
    )

    private fun splitUrl(url: String, scheme: String): UrlParts {
        val rest = url.removePrefix("$scheme://")
        val (beforeFrag, frag) = rest.split('#', limit = 2).let {
            it[0] to (it.getOrNull(1) ?: "")
        }
        val (beforeQuery, queryStr) = beforeFrag.split('?', limit = 2).let {
            it[0] to (it.getOrNull(1) ?: "")
        }
        val (userInfo, hostPort) = run {
            // 密码可能含 @：取最后一个 @ 作为 userInfo/host 分隔
            val idx = beforeQuery.lastIndexOf('@')
            if (idx >= 0) beforeQuery.substring(0, idx) to beforeQuery.substring(idx + 1)
            else "" to beforeQuery
        }
        val (host, port) = splitHostPort(hostPort)
        return UrlParts(
            userInfo = urlDecode(userInfo),
            host = host.trim('[', ']'),
            port = port,
            query = parseQuery(queryStr),
            name = urlDecode(frag).ifBlank { host },
        )
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int> {
        fun validPort(raw: String): Int =
            raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("invalid port: $raw")
        return if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            val host = hostPort.substring(0, end + 1)
            val port = hostPort.substringAfter("]:", "").let { if (it.isBlank()) 443 else validPort(it) }
            host to port
        } else {
            val idx = hostPort.lastIndexOf(':')
            if (idx > 0) hostPort.substring(0, idx) to validPort(hostPort.substring(idx + 1))
            else hostPort to 443
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .filter { it.isNotBlank() }
            .associate { part ->
                val idx = part.indexOf('=')
                if (idx > 0) urlDecode(part.substring(0, idx)) to urlDecode(part.substring(idx + 1))
                else urlDecode(part) to ""
            }

    internal fun urlDecode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrDefault(s)

    // ------------------------------------------------------------------
    // 协议解析
    // ------------------------------------------------------------------

    private fun parseVless(url: String): ParsedNode {
        val p = splitUrl(url, "vless")
        val security = p.query["security"].orEmpty()
        val network = p.query["type"].orEmpty().ifBlank { "tcp" }

        val outbound = buildJsonObject {
            put("type", "vless")
            put("tag", p.name)
            put("server", p.host)
            put("server_port", p.port)
            put("uuid", p.userInfo)
            p.query["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }

            if (security == "tls" || security == "reality") {
                putJsonObject("tls") {
                    put("enabled", true)
                    p.query["sni"]?.takeIf { it.isNotBlank() }?.let { put("server_name", it) }
                    p.query["fp"]?.takeIf { it.isNotBlank() }?.let {
                        putJsonObject("utls") { put("enabled", true); put("fingerprint", it) }
                    }
                    if (security == "reality") {
                        putJsonObject("reality") {
                            put("enabled", true)
                            p.query["pbk"]?.let { put("public_key", it) }
                            p.query["sid"]?.takeIf { it.isNotBlank() }?.let { put("short_id", it) }
                        }
                    }
                    if (p.query["insecure"] == "1" || p.query["allowInsecure"] == "1") {
                        put("insecure", true)
                    }
                }
            }
            putTransport(this, network, p.query)
        }
        return ParsedNode(p.name, outbound.toString())
    }

    private fun parseTrojan(url: String): ParsedNode {
        val p = splitUrl(url, "trojan")
        val outbound = buildJsonObject {
            put("type", "trojan")
            put("tag", p.name)
            put("server", p.host)
            put("server_port", p.port)
            put("password", p.userInfo)
            putJsonObject("tls") {
                put("enabled", true)
                val sni = p.query["sni"]?.takeIf { it.isNotBlank() }
                put("server_name", sni ?: p.host)
                if (p.query["allowInsecure"] == "1" || p.query["insecure"] == "1") put("insecure", true)
            }
            putTransport(this, p.query["type"].orEmpty(), p.query)
        }
        return ParsedNode(p.name, outbound.toString())
    }

    private fun parseShadowsocks(url: String): ParsedNode {
        // 两种形式：ss://base64(method:password)@host:port 或 ss://base64(method:password@host:port)
        val rest = url.removePrefix("ss://")
        val (beforeFrag, frag) = rest.split('#', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
        val (beforeQuery, queryStr) = beforeFrag.split('?', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
        val name = urlDecode(frag)

        val (method, password, host, port) = if ('@' in beforeQuery) {
            val (userB64, hostPort) = beforeQuery.split('@', limit = 2)
            val user = decodeB64(userB64)?.let { String(it, Charsets.UTF_8) } ?: urlDecode(userB64)
            val (h, pt) = splitHostPort(hostPort)
            val (m, pw) = user.split(':', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
            Quad(m, pw, h.trim('[', ']'), pt)
        } else {
            val decoded = decodeB64(beforeQuery)?.let { String(it, Charsets.UTF_8) } ?: error("invalid ss payload")
            val (user, hostPort) = decoded.split('@', limit = 2)
            val (h, pt) = splitHostPort(hostPort)
            val (m, pw) = user.split(':', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
            Quad(m, pw, h.trim('[', ']'), pt)
        }

        val outbound = buildJsonObject {
            put("type", "shadowsocks")
            put("tag", name.ifBlank { host })
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
            parseQuery(queryStr)["plugin"]?.takeIf { it.isNotBlank() }?.let { plugin ->
                // 常见插件：obfs / v2ray-plugin
                val parts = plugin.split(';')
                when (parts.firstOrNull()) {
                    "obfs", "simple-obfs" -> {
                        put("plugin", "obfs-local")
                        put("plugin_opts", parts.drop(1).joinToString(";"))
                    }
                    "v2ray-plugin" -> {
                        put("plugin", "v2ray-plugin")
                        put("plugin_opts", parts.drop(1).joinToString(";"))
                    }
                }
            }
        }
        return ParsedNode(name.ifBlank { host }, outbound.toString())
    }

    private data class Quad(val method: String, val password: String, val host: String, val port: Int)

    private fun parseVmess(url: String): ParsedNode {
        val decoded = decodeB64(url.removePrefix("vmess://").trim())?.let { String(it, Charsets.UTF_8) } ?: error("invalid vmess payload")
        val obj = json.parseToJsonElement(decoded).let { it as? JsonObject }
            ?: error("invalid vmess payload")
        fun String.field() = obj[this]?.jsonPrimitive?.content.orEmpty()

        val name = "ps".field().ifBlank { "add".field() }
        val network = "net".field().ifBlank { "tcp" }
        val outbound = buildJsonObject {
            put("type", "vmess")
            put("tag", name)
            put("server", "add".field())
            put("server_port", "port".field().toIntOrNull() ?: 443)
            put("uuid", "id".field())
            "scy".field().takeIf { it.isNotBlank() }?.let { put("security", it) }
                ?: put("security", "auto")
            "aid".field().toIntOrNull()?.takeIf { it > 0 }?.let { put("alter_id", it) }

            if ("tls".field() == "tls") {
                putJsonObject("tls") {
                    put("enabled", true)
                    val sni = "sni".field().takeIf { it.isNotBlank() }
                        ?: "host".field().takeIf { it.isNotBlank() }
                    if (sni != null) put("server_name", sni)
                }
            }
            putTransport(
                this, network,
                mapOf(
                    "path" to "path".field(),
                    "host" to "host".field(),
                    "serviceName" to "path".field(),
                ),
            )
        }
        return ParsedNode(name, outbound.toString())
    }

    private fun parseHysteria2(url: String): ParsedNode {
        val scheme = if (url.startsWith("hy2://")) "hy2" else "hysteria2"
        val p = splitUrl(url, scheme)
        val outbound = buildJsonObject {
            put("type", "hysteria2")
            put("tag", p.name)
            put("server", p.host)
            put("server_port", p.port)
            put("password", p.userInfo)
            putJsonObject("tls") {
                put("enabled", true)
                p.query["sni"]?.takeIf { it.isNotBlank() }?.let { put("server_name", it) }
                if (p.query["insecure"] == "1") put("insecure", true)
            }
        }
        return ParsedNode(p.name, outbound.toString())
    }

    /**
     * 解析 wireguard:// 分享链接 → sing-box endpoint JSON（1.12+ endpoint 格式）。
     *
     * 格式：wireguard://PRIVATE_KEY@HOST:PORT?publickey=KEY&address=IP1,IP2&
     *       allowedips=NET1,NET2&keepalive=N&mtu=N&reserved=b0,b1,b2#NAME
     *
     * - private_key = userInfo（@ 之前）
     * - peer = host:port + publickey + allowed_ips + reserved
     * - address 归一化为 CIDR（bare IP → /32 或 /128）
     * - 缺 allowed_ips → 默认 0.0.0.0/0,::/0
     */
    private fun parseWireguard(url: String): ParsedNode {
        val scheme = if (url.startsWith("wg://")) "wg" else "wireguard"
        val p = splitUrl(url, scheme)
        val privKey = p.userInfo.ifBlank { error("wireguard: missing private key") }
        val peerPub = p.query["publickey"] ?: p.query["public_key"] ?: error("wireguard: missing publickey")

        // address 归一化：裸 IP → CIDR
        val addresses = (p.query["address"] ?: "").split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeCidr(it) }
            .ifEmpty { error("wireguard: missing address") }

        val allowedIps = (p.query["allowedips"] ?: p.query["allowed_ips"] ?: "0.0.0.0/0,::/0")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeCidr(it) }

        val keepalive = p.query["keepalive"]?.toIntOrNull()?.takeIf { it > 0 }
        val mtu = p.query["mtu"]?.toIntOrNull()?.takeIf { it in 576..1500 } ?: 1408

        // reserved：client_id 三个字节（0-255），逗号分隔
        val reserved = (p.query["reserved"] ?: p.query["client_id"] ?: "")
            .split(',')
            .map { it.trim().toIntOrNull() }
            .takeIf { list -> list.size == 3 && list.all { it != null && it in 0..255 } }
            ?.mapNotNull { it }

        val outbound = buildJsonObject {
            put("type", "wireguard")
            put("tag", p.name)
            put("mtu", mtu)
            putJsonArray("address") { addresses.forEach(::add) }
            put("private_key", privKey)
            putJsonArray("peers") {
                add(buildJsonObject {
                    put("address", p.host)
                    put("port", p.port)
                    put("public_key", peerPub)
                    putJsonArray("allowed_ips") { allowedIps.forEach(::add) }
                    if (keepalive != null) put("persistent_keepalive_interval", keepalive)
                    if (reserved != null) putJsonArray("reserved") { reserved.forEach(::add) }
                })
            }
        }
        return ParsedNode(p.name, outbound.toString())
    }

    /** 裸 IP → CIDR（v4 补 /32，v6 补 /128）；已带前缀则原样返回 */
    private fun normalizeCidr(s: String): String =
        if (s.contains('/')) s else if (s.contains(':')) "$s/128" else "$s/32"

    private fun putTransport(builder: kotlinx.serialization.json.JsonObjectBuilder, network: String, query: Map<String, String>) {
        when (network) {
            "ws" -> builder.putJsonObject("transport") {
                put("type", "ws")
                query["path"]?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                query["host"]?.takeIf { it.isNotBlank() }?.let {
                    putJsonObject("headers") { put("Host", it) }
                }
            }
            "grpc" -> builder.putJsonObject("transport") {
                put("type", "grpc")
                (query["serviceName"] ?: query["path"])?.takeIf { it.isNotBlank() }
                    ?.let { put("service_name", it) }
            }
            "http", "h2" -> builder.putJsonObject("transport") {
                put("type", "http")
                query["host"]?.takeIf { it.isNotBlank() }?.let {
                    putJsonArray("host") { add(it) }
                }
                query["path"]?.takeIf { it.isNotBlank() }?.let { put("path", it) }
            }
            // tcp / 空：不需要 transport
        }
    }
}
