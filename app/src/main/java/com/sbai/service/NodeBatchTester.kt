package com.sbai.service

import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Trigger the real group URLTest once, then match freshly timestamped group-cache entries.
 * This API cannot isolate a single outbound: the core controls group membership/concurrency.
 * Disconnected, disabled, missing, stale and timed-out entries remain unmeasured (0), never -1.
 */
object NodeBatchTester {
    // Retained for source compatibility; concurrency is now controlled by the core group URLTest.
    const val MAX_CONCURRENCY = 6
    const val DEFAULT_TIMEOUT_MS = 5000
    private val testMutex = Mutex()

    data class NodeTestResult(val node: ProxyNode, val delay: Int)

    suspend fun testNodes(
        nodes: List<ProxyNode>,
        groupTag: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<NodeTestResult> = testMutex.withLock {
        val targets = nodes.filter { it.enabled && it.outboundJson.isNotBlank() }
        if (targets.isEmpty()) {
            onProgress(0, 0)
            return@withLock emptyList()
        }
        val measured = mutableMapOf<String, Int>()
        fun results() = targets.map { NodeTestResult(it, measured[it.id] ?: 0) }
        val group = SbCommandClient.groups.value.firstOrNull { it.tag == groupTag }
        // Guard against a stale running config still containing explicitly disabled nodes.
        // Group URLTest has no per-node exclusion API, so decline the entire unsafe request.
        val disabledTags = nodes.filterNot { it.enabled }
            .map { SingBoxConfigGenerator.nodeTagOf(it) }.toSet()
        if (timeoutMs <= 0 || !SbCommandClient.connectedToService.value ||
            group == null || group.type !in setOf("urltest", "selector") ||
            group.items.any { it.tag in disabledTags }
        ) {
            onProgress(targets.size, targets.size)
            return@withLock results()
        }
        val baseline = group.items.associate { item ->
            item.tag to NodeLatencyResults.Sample(item.delay, item.testTime)
        }
        // Match the same tag generator used by config export, including its name/id fallback.
        val tags = targets.associate { it.id to SingBoxConfigGenerator.nodeTagOf(it) }
        val eligible = targets.filter { tags[it.id] in baseline }
        if (eligible.isEmpty()) {
            onProgress(targets.size, targets.size)
            return@withLock results()
        }
        val requestedAt = System.currentTimeMillis()
        onProgress(0, targets.size)
        withTimeoutOrNull(timeoutMs.toLong()) {
            withContext(Dispatchers.IO) { SbCommandClient.urlTest(groupTag) }
            while (SbCommandClient.connectedToService.value) {
                val current = SbCommandClient.groups.value.firstOrNull { it.tag == groupTag }
                    ?.items?.associateBy { it.tag } ?: break
                eligible.forEach { node ->
                    if (node.id !in measured) {
                        val tag = tags.getValue(node.id)
                        val item = current[tag]
                        val fresh = NodeLatencyResults.freshDelay(
                            baseline[tag],
                            item?.let { NodeLatencyResults.Sample(it.delay, it.testTime) },
                            requestedAt,
                        )
                        if (fresh != null) measured[node.id] = fresh
                    }
                }
                onProgress(measured.size, targets.size)
                if (measured.size == eligible.size) break
                delay(200L)
            }
        }
        onProgress(targets.size, targets.size)
        results()
    }

    /** Only fresh successful samples are persisted; never auto-disable or clear prior latency. */
    fun applyResults(store: RuleStore, results: List<NodeTestResult>) {
        val byId = results.filter { it.delay > 0 && it.node.enabled }.associateBy { it.node.id }
        if (byId.isEmpty()) return
        val appliedAt = System.currentTimeMillis()
        store.updateCommitted { state ->
            state.copy(proxyNodes = state.proxyNodes.map { node ->
                val result = byId[node.id]
                // The user may edit/disable a node while a request is in flight.
                if (result != null && node.enabled && node.outboundJson == result.node.outboundJson &&
                    SingBoxConfigGenerator.nodeTagOf(node) == SingBoxConfigGenerator.nodeTagOf(result.node)
                ) {
                    node.copy(urlTestDelay = result.delay, urlTestTime = appliedAt)
                } else node
            })
        }
    }
}
