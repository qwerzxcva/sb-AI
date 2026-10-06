package com.sbai.service

import com.sbai.data.RouteRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 完整 sing-box JSON 配置解析。Xray 的 protocol/settings 不可直接当作 sing-box 节点。
 *
 * 支持多来源 fallback：
 *  - sing-box 完整配置（含 `outbounds[]`）→ 提取每个 outbound 为节点
 *  - sing-box outbounds 数组（`[...]`）→ 每个元素为节点
 *  - xray 完整配置 → 同上（`outbounds[]`）
 *
 * 同时提取 `route.rules[]` 为路由规则（ClashJsonConfig 无此，仅 sing-box）。
 */
object JsonConfigParser {

    private val json = Json { ignoreUnknownKeys = true }

    data class Parsed(
        val nodes: List<ShareLinkParser.ParsedNode>,
        val routeRules: List<RouteRule>,
    )

    fun parse(text: String): Parsed? = runCatching {
        val root = json.parseToJsonElement(text)

        // sing-box 完整配置 / xray 配置：取 outbounds[]
        val outbounds = when (root) {
            is JsonObject -> JsonArray(
                (root["outbounds"] as? JsonArray).orEmpty() +
                    (root["endpoints"] as? JsonArray).orEmpty(),
            )
            is JsonArray -> root
            else -> null
        } ?: return null

        val nodes = outbounds.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = (o["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: return@mapNotNull null // Xray protocol/settings is not sing-box JSON.
            // 跳过 sing-box 的系统 outbound（不是节点）
            if (type in setOf("selector", "urltest", "direct", "block", "dns")) return@mapNotNull null
            val tag = (o["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val name = tag?.takeIf { it.isNotBlank() }
                ?: (o["server"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: return@mapNotNull null
            ShareLinkParser.ParsedNode(name, o.toString())
        }

        // 提取路由规则（仅 sing-box 配置有 route.rules）
        val routeRules = (root as? JsonObject)
            ?.get("route")?.let { it as? JsonObject }
            ?.get("rules")?.let { it as? JsonArray }
            ?.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                when (val r = RouteRuleJsonCodec.fromJson(obj.toString())) {
                    is RouteRuleJsonCodec.ParseResult.Success -> r.rule
                    is RouteRuleJsonCodec.ParseResult.Failure -> null
                }
            }
            .orEmpty()

        if (nodes.isEmpty() && routeRules.isEmpty()) null else Parsed(nodes, routeRules)
    }.getOrNull()
}
