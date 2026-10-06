package com.sbai.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 节点去重工具：把 outbound JSON 递归规范化为稳定的字符串，用于判定「配置相同」。
 *
 * 规范化规则：
 *  - 对象所有层级按 key 字典序排序（消除 key 顺序差异）
 *  - 忽略顶层 tag/name（节点名可不同但配置相同视为重复）
 *  - 数组保持顺序、原始值原样
 *
 * 供订阅内去重（SubscriptionManager）与跨订阅去重（RuleStore）共用。
 */
object NodeDedup {

    /** 归一化 outbound JSON；解析失败时回退为原始串（保证 distinctBy 仍能按内容去重）。 */
    fun normalize(outboundJson: String): String = runCatching {
        canonical(Json.parseToJsonElement(outboundJson), ignoreTag = true)
    }.getOrDefault(outboundJson)

    /** 递归规范化 JsonElement */
    private fun canonical(el: JsonElement, ignoreTag: Boolean): String = when (el) {
        is JsonObject -> el.entries
            .filter { !(ignoreTag && (it.key == "tag" || it.key == "name")) }
            .sortedBy { it.key }
            .joinToString("|", "{", "}") { (k, v) -> "\"$k\":" + canonical(v, ignoreTag = false) }
        is JsonArray -> el.joinToString(",", "[", "]") { canonical(it, ignoreTag = false) }
        else -> el.toString()
    }
}
