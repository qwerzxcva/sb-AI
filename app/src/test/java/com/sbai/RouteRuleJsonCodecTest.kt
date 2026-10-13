package com.sbai

import com.sbai.data.RuleAction
import com.sbai.data.RuleLogic
import com.sbai.service.RouteRuleJsonCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRuleJsonCodecTest {

    private fun parseSuccess(text: String) = when (val r = RouteRuleJsonCodec.fromJson(text)) {
        is RouteRuleJsonCodec.ParseResult.Success -> r.rule
        is RouteRuleJsonCodec.ParseResult.Failure -> throw AssertionError("解析失败: ${r.message}")
    }

    @Test
    fun `parse flat rule with all fields`() {
        val rule = parseSuccess(
            """{"domain":["a.com","b.com"],"domain_suffix":["x.io"],"ip_cidr":["1.2.3.0/24"],
              |"network":["tcp","udp"],"protocol":["tls","quic"],"port":[443,8443],
              |"outbound":"proxy","invert":true}""".trimMargin(),
        )
        assertEquals(listOf("a.com", "b.com"), rule.domains)
        assertEquals(listOf("x.io"), rule.domainSuffixes)
        assertEquals(listOf("1.2.3.0/24"), rule.ipCidrs)
        assertEquals(listOf("tcp", "udp"), rule.networks.sorted())
        assertEquals(listOf("quic", "tls"), rule.protocols.sorted())
        assertEquals(listOf(443, 8443), rule.ports)
        assertEquals(RuleAction.ROUTE_PROXY, rule.action)
        assertTrue(rule.invert)
        assertEquals(RuleLogic.AND, rule.logic)
    }

    @Test
    fun `parse block and direct actions`() {
        assertEquals(RuleAction.REJECT, parseSuccess("""{"domain":["a.com"],"outbound":"block"}""").action)
        assertEquals(RuleAction.ROUTE_DIRECT, parseSuccess("""{"domain":["a.com"],"outbound":"direct"}""").action)
    }

    @Test
    fun `parse logical or rule aggregating children`() {
        val rule = parseSuccess(
            """{"type":"logical","mode":"or","rules":[
              |{"domain":["a.com"]},{"ip_cidr":["1.2.3.0/24"]}],"outbound":"direct"}""".trimMargin(),
        )
        assertEquals(RuleLogic.OR, rule.logic)
        assertEquals(listOf("a.com"), rule.domains)
        assertEquals(listOf("1.2.3.0/24"), rule.ipCidrs)
    }

    @Test
    fun `parse wrapped in rules array takes first`() {
        val rule = parseSuccess("""{"rules":[{"domain":["a.com"],"outbound":"direct"}]}""")
        assertEquals(listOf("a.com"), rule.domains)
    }

    @Test
    fun `invalid json returns failure`() {
        assertTrue(RouteRuleJsonCodec.fromJson("not json") is RouteRuleJsonCodec.ParseResult.Failure)
        assertTrue(RouteRuleJsonCodec.fromJson("") is RouteRuleJsonCodec.ParseResult.Failure)
    }

    @Test
    fun `round trip preserves semantics`() {
        val original = parseSuccess(
            """{"domain":["a.com"],"ip_cidr":["1.2.3.0/24"],"network":["tcp"],"outbound":"direct"}""",
        )
        val json = RouteRuleJsonCodec.toJson(original)
        val reparsed = parseSuccess(json)
        assertEquals(original.domains, reparsed.domains)
        assertEquals(original.ipCidrs, reparsed.ipCidrs)
        assertEquals(original.networks.sorted(), reparsed.networks.sorted())
        assertEquals(original.action, reparsed.action)
    }

    @Test
    fun `round trip or logic`() {
        val original = parseSuccess(
            """{"type":"logical","mode":"or","rules":[{"domain":["a.com"]},{"ip_cidr":["1.2.3.0/24"]}],"outbound":"direct"}""",
        )
        val json = RouteRuleJsonCodec.toJson(original)
        assertTrue(json.contains("\"type\": \"logical\""))
        assertTrue(json.contains("\"mode\": \"or\""))
        val reparsed = parseSuccess(json)
        assertEquals(RuleLogic.OR, reparsed.logic)
    }

    @Test
    fun `unknown fields are ignored not error`() {
        val rule = parseSuccess("""{"domain":["a.com"],"geo_site":["cn"],"source_ip_cidr":["10.0.0.0/8"],"outbound":"proxy"}""")
        assertEquals(listOf("a.com"), rule.domains)
        // 未知字段（geo_site/source_ip_cidr）被忽略，不导致失败
    }

    /**
     * 动作字段 round-trip：粘贴规则 JSON 时这些字段此前被静默丢弃，
     * 用户以为导入了完整规则，实际只剩匹配条件。
     */
    @Test
    fun `action fields survive json round trip`() {
        val original = parseSuccess(
            """
            {
              "domain_suffix": [".example.com"],
              "outbound": "my-node",
              "ip_version": 4,
              "reject_method": "drop",
              "udp_connect": true,
              "udp_timeout": "5m",
              "udp_disable_domain_unmapping": true
            }
            """.trimIndent(),
        )
        assertEquals("my-node", original.outboundTag)
        assertEquals(4, original.ipVersion)
        assertEquals("drop", original.rejectMethod)
        assertTrue(original.udpConnect)
        assertEquals("5m", original.udpTimeout)
        assertTrue(original.udpDisableDomainUnmapping)
    }

    /** ip_version=4 → 仅 IPv4；=6 → 仅 IPv6；缺省 → 两个都勾 */
    @Test
    fun `ip version maps to ipv4 ipv6 checkboxes`() {
        val v4 = parseSuccess("""{"domain":["a.com"],"ip_version":4}""")
        assertTrue(v4.ipv4)
        assertTrue(!v4.ipv6)

        val v6 = parseSuccess("""{"domain":["a.com"],"ip_version":6}""")
        assertTrue(!v6.ipv4)
        assertTrue(v6.ipv6)

        val any = parseSuccess("""{"domain":["a.com"]}""")
        assertTrue(any.ipv4)
        assertTrue(any.ipv6)
    }

    /** route-options 的 override / TLS 分片字段 round-trip */
    @Test
    fun `route-options fields survive json round trip`() {
        val original = parseSuccess(
            """
            {
              "action": "route-options",
              "domain": ["a.com"],
              "override_address": "1.2.3.4",
              "override_port": 8443,
              "tls_fragment": true,
              "tls_record_fragment": true,
              "tls_fragment_fallback_delay": "500ms"
            }
            """.trimIndent(),
        )
        assertEquals(RuleAction.ROUTE_OPTIONS, original.action)
        assertEquals("1.2.3.4", original.overrideAddress)
        assertEquals(8443, original.overridePort)
        assertTrue(original.tlsFragment)
        assertTrue(original.tlsRecordFragment)
        assertEquals("500ms", original.tlsFragmentFallbackDelay)
    }

    /** 内置 outbound tag（direct/block）不得被当成用户指定的 outboundTag */
    @Test
    fun `builtin outbound tags are not kept as outboundTag`() {
        val direct = parseSuccess("""{"domain":["a.com"],"outbound":"direct"}""")
        assertEquals("", direct.outboundTag)
        val block = parseSuccess("""{"domain":["a.com"],"outbound":"block"}""")
        assertEquals("", block.outboundTag)
    }

    /**
     * 内核 action 枚举必须全部正确映射。此前 route-options / sniff /
     * resolve / hijack-dns 全落进 else -> ROUTE_PROXY，粘贴 JSON 时
     * 动作被静默篡改。
     */
    @Test
    fun `every kernel action maps to the right RuleAction`() {
        assertEquals(
            RuleAction.ROUTE_OPTIONS,
            parseSuccess("""{"action":"route-options","domain":["a.com"]}""").action,
        )
        assertEquals(
            RuleAction.SNIFF,
            parseSuccess("""{"action":"sniff","domain":["a.com"]}""").action,
        )
        assertEquals(
            RuleAction.RESOLVE,
            parseSuccess("""{"action":"resolve","domain":["a.com"]}""").action,
        )
        assertEquals(
            RuleAction.HIJACK_DNS,
            parseSuccess("""{"action":"hijack-dns","domain":["a.com"]}""").action,
        )
        assertEquals(
            RuleAction.REJECT,
            parseSuccess("""{"action":"reject","domain":["a.com"]}""").action,
        )
        // route + outbound=direct 仍解析为直连
        assertEquals(
            RuleAction.ROUTE_DIRECT,
            parseSuccess("""{"action":"route","outbound":"direct","domain":["a.com"]}""").action,
        )
    }
}
