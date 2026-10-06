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

    /**
     * 合并「已有节点」与「订阅新解析节点」，并按来源开关决定是否去重。
     *
     * 规则：
     *  - 订阅开启去重：该订阅内部先去重，再与已存在节点做跨来源去重；
     *    但已存在节点若来自「关闭去重」的来源，则不参与跨来源去重（不删它）。
     *  - 订阅关闭去重：该订阅节点全部保留，不删除任何已存在节点。
     *
     * @param allowDedupeFor 给定节点所属订阅 id（null 表示手动节点）是否允许去重
     */
    fun mergeWithSubscription(
        existing: List<com.sbai.data.ProxyNode>,
        incoming: List<com.sbai.data.ProxyNode>,
        subscriptionId: String,
        allowDedupeFor: (String?) -> Boolean,
    ): List<com.sbai.data.ProxyNode> {
        val subAllows = allowDedupeFor(subscriptionId)
        val incomingNodes = if (subAllows) {
            val seen = HashSet<String>()
            incoming.filter { seen.add(normalize(it.outboundJson)) }
        } else incoming
        if (!subAllows) return existing + incomingNodes
        val seen = HashSet<String>()
        val merged = ArrayList<com.sbai.data.ProxyNode>(existing.size + incomingNodes.size)
        for (node in existing) {
            // 已存在节点来自不允许去重的来源时，无条件保留
            if (!allowDedupeFor(node.subscriptionId)) {
                merged.add(node)
                continue
            }
            val key = normalize(node.outboundJson)
            if (seen.add(key)) merged.add(node)
        }
        for (node in incomingNodes) {
            val key = normalize(node.outboundJson)
            if (seen.add(key)) merged.add(node)
        }
        return merged
    }

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
