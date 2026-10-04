package com.sbai.service

import com.sbai.data.RouteRule

/**
 * 订阅内容格式检测与统一解析。
 *
 * 支持的格式：
 *  1. 分享链接（vless/vmess/trojan/ss/hysteria2/…，可整体 base64）
 *  2. Clash YAML（proxies / proxy-groups / rules）
 *  3. sing-box / xray 完整 JSON 配置（提取 outbounds + route.rules）
 */
object SubscriptionFormat {

    data class Result(
        val nodes: List<ShareLinkParser.ParsedNode>,
        val routeRules: List<RouteRule> = emptyList(),
        /** 识别出的格式（用于 UI 提示） */
        val format: String,
    )

    /** 识别并解析订阅内容；全部不支持返回 null */
    fun parse(body: String): Result? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        // 1) Clash YAML：含 proxies: 或 proxy-groups:
        if (looksLikeClashYaml(trimmed)) {
            ClashYamlParser.parse(trimmed)?.let { p ->
                return Result(p.nodes, p.routeRules, "clash_yaml")
            }
        }

        // 2) sing-box / xray JSON 配置
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            JsonConfigParser.parse(trimmed)?.let { p ->
                return Result(p.nodes, p.routeRules, "json_config")
            }
        }

        // 3) 分享链接（含整体 base64）
        val nodes = ShareLinkParser.parseSubscription(trimmed)
        if (nodes.isNotEmpty()) {
            return Result(nodes, emptyList(), "share_links")
        }

        return null
    }

    /** 检测 Clash YAML：有 proxies: 键（容忍缩进/注释），且内容像 YAML 而非 base64 */
    private fun looksLikeClashYaml(text: String): Boolean {
        if (text.startsWith("{") || text.startsWith("[")) return false
        // 含 proxies: 或 proxy-groups: 顶层键
        return Regex("""(?m)^proxies\s*:""").containsMatchIn(text) ||
                Regex("""(?m)^proxy-groups\s*:""").containsMatchIn(text) ||
                Regex("""(?m)^rules\s*:""").containsMatchIn(text)
    }
}
