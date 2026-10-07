package com.sbai

import com.sbai.data.ProxyNode
import com.sbai.service.NodeDedup
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeDedupMergeTest {

    private fun node(id: String, uuid: String, subId: String? = "sub-1") = ProxyNode(
        id = id,
        name = "node-$id",
        subscriptionId = subId,
        outboundJson = """{"type":"vless","server":"1.2.3.4","server_port":443,"uuid":"$uuid"}""",
    )

    @Test fun `subscription with dedupe disabled keeps duplicate nodes`() {
        val existing = listOf(node("a", "u-1"))
        val incoming = listOf(node("b", "u-1"), node("c", "u-2"))
        val merged = NodeDedup.mergeWithSubscription(existing, incoming, "sub-1") { false }
        assertEquals(3, merged.size)
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test fun `subscription with dedupe enabled removes duplicates`() {
        val existing = listOf(node("a", "u-1"))
        val incoming = listOf(node("b", "u-1"), node("c", "u-2"))
        val merged = NodeDedup.mergeWithSubscription(existing, incoming, "sub-1") { true }
        assertEquals(listOf("a", "c"), merged.map { it.id })
    }

    @Test fun `existing manual nodes are never dropped by dedupe`() {
        val existing = listOf(node("manual", "u-1", subId = null))
        val incoming = listOf(node("b", "u-1"))
        val merged = NodeDedup.mergeWithSubscription(existing, incoming, "sub-1") { sid -> sid != null }
        assertEquals(listOf("manual", "b"), merged.map { it.id })
    }

    @Test fun `deduplication is scoped to config not name`() {
        val existing = listOf(node("a", "u-1"))
        val incoming = listOf(node("a-same-config", "u-1").copy(name = "不同名字"))
        val merged = NodeDedup.mergeWithSubscription(existing, incoming, "sub-1") { true }
        assertEquals(1, merged.size)
    }
}
