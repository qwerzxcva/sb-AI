package com.sbai.service

import com.sbai.data.RouteRule
import kotlinx.serialization.json.*

/** 订阅格式识别；解码后重新识别，所有格式共用基本节点校验。 */
object SubscriptionFormat {
    data class Result(
        val nodes: List<ShareLinkParser.ParsedNode>,
        val routeRules: List<RouteRule> = emptyList(),
        val format: String,
        val rejectedNodeCount: Int = 0,
    )

    internal fun cleanText(text: String): String = text.trim { it.isWhitespace() || it == '\uFEFF' }

    fun parse(body: String): Result? {
        val text = cleanText(body)
        if (text.isEmpty()) return null
        val decoded = ShareLinkParser.decodeB64(text)?.toString(Charsets.UTF_8)?.let(::cleanText)
        val result = parsePlain(text) ?: decoded?.let(::parsePlain) ?: return null
        val valid = result.nodes.filter(::isValidNode)
        return result.copy(nodes = valid, rejectedNodeCount = result.nodes.size - valid.size)
    }

    private fun parsePlain(text: String): Result? {
        // JSON is valid YAML too: recognize a Clash JSON object before sing-box.
        if (text.startsWith("{") || text.startsWith("[")) {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            if (root is JsonObject && "proxies" in root) {
                ClashYamlParser.parse(text)?.let { return Result(it.nodes, it.routeRules, "clash_yaml") }
            }
            JsonConfigParser.parse(text)?.let { return Result(it.nodes, it.routeRules, "json_config") }
        } else if (Regex("""(?m)^\s*(?:proxies|proxy-groups|rules)\s*:""").containsMatchIn(text)) {
            ClashYamlParser.parse(text)?.let { return Result(it.nodes, it.routeRules, "clash_yaml") }
        }
        val nodes = text.lineSequence().mapNotNull { ShareLinkParser.parse(cleanText(it)) }.toList()
        return nodes.takeIf { it.isNotEmpty() }?.let { Result(it, format = "share_links") }
    }

    internal fun protocolOf(outboundJson: String): String? = runCatching {
        (Json.parseToJsonElement(outboundJson) as? JsonObject)?.get("type")
            ?.let { it as? JsonPrimitive }?.contentOrNull
    }.getOrNull()

    internal fun matchesProtocol(outboundJson: String, filter: String): Boolean {
        fun canonical(value: String) = when (value.lowercase()) {
            "ss" -> "shadowsocks"
            "hy2" -> "hysteria2"
            "socks5", "socks4" -> "socks"
            "wg" -> "wireguard"
            else -> value.lowercase()
        }
        val type = protocolOf(outboundJson) ?: return false
        return filter.split(Regex("""[\s,;]+""")).filter { it.isNotBlank() }
            .any { canonical(it) == canonical(type) }
    }

    /** 基本结构与必填字段校验；不等同于内核 schema 校验或连通性测试。 */
    internal fun isValidNode(node: ShareLinkParser.ParsedNode): Boolean = runCatching {
        val o = Json.parseToJsonElement(node.outboundJson) as? JsonObject ?: return false
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun JsonObject.port(key: String) = (this[key] as? JsonPrimitive)?.intOrNull?.let { it in 1..65535 } == true
        fun host(value: String) = value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() } && !value.contains("://")
        val type = o.text("type")
        if (node.name.isBlank()) return false
        if (type == "wireguard" && o["peers"] is JsonArray) {
            val peers = o["peers"] as JsonArray
            return o.text("private_key").isNotBlank() && (o["address"] as? JsonArray)?.isNotEmpty() == true &&
                peers.isNotEmpty() && peers.all { el ->
                    val p = el as? JsonObject ?: return@all false
                    host(p.text("address")) && p.port("port") && p.text("public_key").isNotBlank()
                }
        }
        if (!host(o.text("server")) || !o.port("server_port")) return false
        when (type) {
            "vless", "vmess" -> o.text("uuid").isNotBlank()
            "trojan", "hysteria2" -> o.text("password").isNotBlank()
            "shadowsocks" -> o.text("method").isNotBlank() &&
                (o.text("method") == "none" || o.text("password").isNotBlank())
            "tuic" -> o.text("uuid").isNotBlank() && o.text("password").isNotBlank()
            "wireguard" -> o.text("private_key").isNotBlank() && o.text("peer_public_key").isNotBlank()
            "http", "socks", "hysteria", "ssh", "shadowtls", "anytls" -> true
            else -> false
        }
    }.getOrDefault(false)
}
