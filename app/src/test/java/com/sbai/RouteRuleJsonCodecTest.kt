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
}
