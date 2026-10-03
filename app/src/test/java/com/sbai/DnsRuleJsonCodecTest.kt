package com.sbai

import com.sbai.service.DnsRuleJsonCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsRuleJsonCodecTest {

    private fun parseSuccess(text: String) = when (val r = DnsRuleJsonCodec.fromJson(text)) {
        is DnsRuleJsonCodec.ParseResult.Success -> r.rule
        is DnsRuleJsonCodec.ParseResult.Failure -> throw AssertionError("解析失败: ${r.message}")
    }

    @Test
    fun `parse route rule with all fields`() {
        val rule = parseSuccess(
            """{"domain":["a.com"],"domain_suffix":["x.io"],"ip_cidr":["1.2.3.0/24"],
              |"network":["tcp"],"port":[53],"query_type":["A"],"server":"dns-remote",
              |"ip_strategy":"ipv4_only","disable_cache":true,"rewrite_ttl":60,
              |"client_subnet":"1.0.1.0/24","timeout":"4s"}""".trimMargin(),
        )
        assertEquals(listOf("a.com"), rule.domains)
        assertEquals(listOf("x.io"), rule.domainSuffixes)
        assertEquals(listOf("1.2.3.0/24"), rule.ipCidrs)
        assertEquals(listOf("tcp"), rule.networks)
        assertEquals(listOf(53), rule.ports)
        assertEquals(listOf("A"), rule.queryTypes)
        assertEquals("dns-remote", rule.server)
        assertEquals("ipv4_only", rule.ipStrategy)
        assertEquals(true, rule.disableCache)
        assertEquals(60, rule.rewriteTtl)
        assertEquals("1.0.1.0/24", rule.clientSubnet)
        assertEquals("4s", rule.timeout)
        assertEquals("route", rule.action)
    }

    @Test
    fun `parse reject with rcode`() {
        val rule = parseSuccess("""{"domain_suffix":["ads.com"],"action":"reject","rcode":"nxdomain"}""")
        assertEquals("reject", rule.action)
        assertEquals("nxdomain", rule.rcode)
        assertEquals("", rule.server)
    }

    @Test
    fun `parse route-options with answers`() {
        val rule = parseSuccess(
            """{"domain":["example.com"],"action":"route-options","answer":["1.2.3.4","5.6.7.8"],"ns":["ns1.x.com"],"rcode":"success"}""",
        )
        assertEquals("route-options", rule.action)
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), rule.answers)
        assertEquals(listOf("ns1.x.com"), rule.ns)
    }

    @Test
    fun `invalid json returns failure`() {
        assertTrue(DnsRuleJsonCodec.fromJson("not json") is DnsRuleJsonCodec.ParseResult.Failure)
        assertTrue(DnsRuleJsonCodec.fromJson("") is DnsRuleJsonCodec.ParseResult.Failure)
    }
}
