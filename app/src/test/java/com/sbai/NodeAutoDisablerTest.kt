package com.sbai

import com.sbai.data.AppState
import com.sbai.data.ProxyNode
import com.sbai.service.NodeAutoDisabler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeAutoDisablerTest {

    private fun node(name: String, tag: String, json: String = """{"type":"vless","tag":"$tag","server":"1.2.3.4","server_port":443,"uuid":"x"}""") =
        ProxyNode(name = name, outboundJson = json)

    @Test
    fun `parse rejected outbound tag from initialize error`() {
        val err = "initialize outbound[3] vless[bad-node]: dial tcp 1.2.3.4:443: connection refused"
        assertEquals("bad-node", NodeAutoDisabler.parseRejectedTag(err))
    }

    @Test
    fun `parse rejected endpoint tag from initialize error`() {
        val err = "initialize endpoint[0] wireguard[warp-node]: invalid private key"
        assertEquals("warp-node", NodeAutoDisabler.parseRejectedTag(err))
    }

    @Test
    fun `parse rejects non-initialize errors`() {
        assertNull(NodeAutoDisabler.parseRejectedTag("timeout after 5s"))
        assertNull(NodeAutoDisabler.parseRejectedTag(""))
        assertNull(NodeAutoDisabler.parseRejectedTag("some random network error"))
    }

    @Test
    fun `parse reason extracts text after bracket`() {
        val err = "initialize outbound[0] vless[n]: dial tcp 1.2.3.4:443: connection refused"
        assertEquals("dial tcp 1.2.3.4:443: connection refused", NodeAutoDisabler.parseReason(err))
    }

    @Test
    fun `disable rejected node by tag`() {
        val state = AppState(
            proxyNodes = listOf(
                node("good", "good-node"),
                node("bad", "bad-node"),
            ),
        )
        val next = NodeAutoDisabler.disableRejectedNode(
            state,
            "initialize outbound[1] vless[bad-node]: boom",
        )
        assertNotNull(next)
        val bad = next!!.proxyNodes.first { it.name == "bad" }
        assertTrue(!bad.enabled)
        assertNotNull(bad.disabledReason)
        // 好节点不受影响
        assertTrue(next.proxyNodes.first { it.name == "good" }.enabled)
    }

    @Test
    fun `disable rejected node by name when tag differs`() {
        val state = AppState(
            proxyNodes = listOf(
                node("我的节点", "gen-tag-1"),
            ),
        )
        // 错误里 tag 是 gen-tag-1，但 name 是展示名；应能匹配到
        val next = NodeAutoDisabler.disableRejectedNode(state, "initialize outbound[0] vless[gen-tag-1]: boom")
        assertNotNull(next)
        assertTrue(!next!!.proxyNodes.first().enabled)
    }

    @Test
    fun `cannot locate tag returns null`() {
        val state = AppState(proxyNodes = listOf(node("a", "tag-a")))
        assertNull(NodeAutoDisabler.disableRejectedNode(state, "initialize outbound[0] vless[missing]: boom"))
    }

    @Test
    fun `body fingerprint ignores tag and name`() {
        val a = ProxyNode(name = "x", outboundJson = """{"type":"vless","tag":"t1","server":"1.1.1.1","server_port":443}""")
        val b = ProxyNode(name = "y", outboundJson = """{"type":"vless","tag":"t2","server":"1.1.1.1","server_port":443}""")
        // 同 body（忽略 tag/name）指纹一致
        assertEquals(NodeAutoDisabler.bodyFingerprint(a), NodeAutoDisabler.bodyFingerprint(b))
        val c = ProxyNode(name = "z", outboundJson = """{"type":"vless","tag":"t3","server":"2.2.2.2","server_port":443}""")
        assertTrue(NodeAutoDisabler.bodyFingerprint(a) != NodeAutoDisabler.bodyFingerprint(c))
    }

    @Test
    fun `disabled nodes listed for UI`() {
        val good = node("good", "g")
        val bad = node("bad", "b").copy(enabled = false, disabledReason = "dial refused")
        val manualOff = node("manual", "m").copy(enabled = false)  // 无原因 = 手动禁用
        val state = AppState(proxyNodes = listOf(good, bad, manualOff))
        val list = NodeAutoDisabler.disabledNodes(state)
        assertEquals(1, list.size)  // 只有自动禁用的
        assertEquals("bad", list[0].name)
    }
}
