package com.sbai.service

import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 批量节点测速（参考 LxBox 009-NODE_HEALTH 的 list server test + Throne 批量测试面板）。
 *
 * 对一组节点逐个执行单节点 URLTest，并发受控，结果回填到 ProxyNode.urlTestDelay。
 *
 * 语义要点：
 *  - 单节点测速走 SbCommandClient.urlTestOutbound（内核按 tag 测一个节点，不切换 active 选择）。
 *  - 测速需内核运行中（VPN 已起），否则内核 CommandServer 未就绪，返回失败。
 *  - 并发上限 MAX_CONCURRENCY，避免一次测太多节点拖垮内核。
 *  - 结果：>0 = 延迟 ms；-1 = 失败/超时；0 = 未测（跳过 disabled 节点）。
 */
object NodeBatchTester {

    const val MAX_CONCURRENCY = 6
    const val DEFAULT_TIMEOUT_MS = 5000

    /** 单个节点的测速结果 */
    data class NodeTestResult(
        val node: ProxyNode,
        val delay: Int,   // >0 延迟ms；-1 失败/超时；0 未测
    )

    /**
     * 批量测速：对 [nodes] 逐个测速，实时回调 [onProgress]（已完成数/总数），
     * 结束后把结果回填到 store。
     *
     * @param groupTag 节点所在的 outbound 组 tag（urltest 组才能测速；lb/proxy）。
     * @return 测速结果列表（按输入顺序）。
     */
    suspend fun testNodes(
        nodes: List<ProxyNode>,
        groupTag: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<NodeTestResult> = coroutineScope {
        val targets = nodes.filter { it.enabled && it.outboundJson.isNotBlank() }
        val total = targets.size
        var done = 0

        // 并发受控：分批次，每批最多 MAX_CONCURRENCY 个
        val results = mutableListOf<NodeTestResult>()
        targets.chunked(MAX_CONCURRENCY).forEach { batch ->
            val batchResults = batch.map { node ->
                async(Dispatchers.IO) {
                    val tag = nodeTag(node)
                    val delay = if (tag == null) -1 else
                        (SbCommandClient.urlTestOutbound(groupTag, tag, timeoutMs) ?: -1)
                    NodeTestResult(node, delay)
                }
            }.awaitAll()
            results += batchResults
            done += batchResults.size
            onProgress(done, total)
        }
        results
    }

    /** 把测速结果回填到节点列表（-1 表示失败，>0 表示延迟） */
    fun applyResults(store: RuleStore, results: List<NodeTestResult>) {
        if (results.isEmpty()) return
        val byId = results.associate { it.node.id to it.delay }
        store.updateCommitted { s ->
            s.copy(
                proxyNodes = s.proxyNodes.map { node ->
                    val delay = byId[node.id]
                    if (delay != null && delay != node.urlTestDelay) {
                        node.copy(urlTestDelay = delay, urlTestTime = System.currentTimeMillis())
                    } else node
                },
            )
        }
    }

    private fun nodeTag(node: ProxyNode): String? = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(node.outboundJson)
            .let { it as kotlinx.serialization.json.JsonObject }
            .get("tag")?.let { it as kotlinx.serialization.json.JsonPrimitive }
            ?.content
    }.getOrNull()
}
