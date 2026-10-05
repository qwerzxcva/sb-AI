package com.sbai.service

import com.sbai.data.AppState
import com.sbai.data.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 自动禁用内核拒绝的坏节点（参考 LxBox 009-NODE_HEALTH P14~P16）。
 *
 * 问题：订阅更新后混入不可用节点，sing-box 启动时初始化该 outbound 失败，
 * 整个 VPN 起不来。本组件把「坏节点」从配置里剔除并标记原因，用剩余节点重试，
 * 使 VPN 能在好节点上正常起来。
 *
 * 语义要点：
 *  - verdict（禁用原因）绑定「节点 body」（outboundJson 原文），body 变了自动解除禁用。
 *  - 有限轮次：最多 MAX_ROUNDS 轮，每轮最多禁一个节点；避免死循环。
 *  - 只有「内核明确点名某 outbound 初始化失败」才触发禁用；超时/网络错误不触发。
 *  - 人类手动禁用的节点（enabled=false）不受影响，也不被自动恢复。
 */
object NodeAutoDisabler {

    const val MAX_ROUNDS = 10

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析 sing-box-lx 的 checkConfig / 启动错误文本，提取被拒绝的 outbound tag。
     *
     * 内核拒绝格式（core ≥ 1.14.1-lx.7）：
     *   `initialize outbound[3] vless[某节点]: reason...`
     *   `initialize endpoint[0] wireguard[...]: reason...`
     * 解析要点：`initialize ` 可能被 Go 前缀 / 模板文本包裹，所以用 contains 匹配；
     * tag 在中括号内（`type[tag]`）。
     */
    fun parseRejectedTag(error: String): String? {
        val regex = Regex("""initialize\s+(?:outbound|endpoint)\[\d+\]\s+[^\[\]]+\[([^\]]+)\]""")
        return regex.find(error)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 从错误信息中提取可读原因（`type[tag]: reason` 中 tag 后的文本）。
     * 用与 parseRejectedTag 相同的结构定位，避免误取 outbound[i] 的右括号。
     */
    fun parseReason(error: String): String {
        val regex = Regex("""initialize\s+(?:outbound|endpoint)\[\d+\]\s+[^\[\]]+\[([^\]]+)\]\s*:?\s*(.*)""")
        val m = regex.find(error) ?: return error.take(200)
        val reason = m.groupValues.getOrNull(2).orEmpty().trim()
        return reason.ifEmpty { error.take(200) }.take(200)
    }

    /** 取节点 body 的稳定指纹（忽略 tag/name，因为同名节点去重时 body 才是身份） */
    fun bodyFingerprint(node: ProxyNode): String = runCatching {
        val obj = json.parseToJsonElement(node.outboundJson).jsonObject
        obj.filterKeys { it != "tag" && it != "name" }
            .entries.sortedBy { it.key }
            .joinToString("|") { "${it.key}=${it.value}" }
    }.getOrDefault(node.outboundJson)

    /**
     * 从错误中定位坏节点 tag → 找到对应节点并禁用。
     *
     * @return 新状态（若禁用了节点则 proxyNodes 已更新）；若无法定位返回 null。
     */
    fun disableRejectedNode(state: AppState, error: String): AppState? {
        val tag = parseRejectedTag(error) ?: return null
        // 按 outboundJson 中的 tag 或 node.name 匹配（生成器用 outboundJson.tag，name 是展示名）
        val idx = state.proxyNodes.indexOfFirst { node ->
            node.enabled && (nodeTag(node) == tag || node.name == tag)
        }
        if (idx < 0) return null
        val node = state.proxyNodes[idx]
        val updated = node.copy(
            enabled = false,
            disabledReason = parseReason(error),
        )
        val newList = state.proxyNodes.toMutableList().apply { this[idx] = updated }
        return state.copy(proxyNodes = newList)
    }

    private fun nodeTag(node: ProxyNode): String = runCatching {
        json.parseToJsonElement(node.outboundJson).jsonObject["tag"]?.jsonPrimitive?.content
    }.getOrNull() ?: node.name

    /**
     * 供 UI 展示：把带 disabledReason 的节点列出（自动禁用的坏节点清单）。
     */
    fun disabledNodes(state: AppState): List<ProxyNode> =
        state.proxyNodes.filter { !it.enabled && it.disabledReason != null }
}
