package com.sbai

import com.sbai.data.RuleAction
import com.sbai.service.ClashYamlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Clash 规则导入映射：机场订阅的 rules 段是最主要的分流来源，
 * 映射错一条就静默少一条分流。
 */
class ClashRuleImportTest {

    private fun parseRule(line: String) = ClashYamlParser.parseRuleLine(line)

    @Test
    fun `domain suffix maps to domainSuffixes`() {
        val rule = parseRule("DOMAIN-SUFFIX,google.com,PROXY")
        assertNotNull(rule)
        assertEquals(listOf("google.com"), rule!!.domainSuffixes)
        assertEquals(RuleAction.ROUTE_PROXY, rule.action)
    }

    @Test
    fun `geosite maps to builtin geosite field`() {
        val rule = parseRule("GEOSITE,cn,DIRECT")
        assertNotNull(rule)
        assertEquals(listOf("cn"), rule!!.geosites)
        assertEquals(RuleAction.ROUTE_DIRECT, rule.action)
    }

    @Test
    fun `geoip maps to builtin geoip field`() {
        val rule = parseRule("GEOIP,LAN,DIRECT")
        assertNotNull(rule)
        assertEquals(listOf("LAN"), rule!!.geoips)
    }

    @Test
    fun `geoip no-resolve is still imported`() {
        val rule = parseRule("GEOIP,CN,DIRECT,no-resolve")
        assertNotNull(rule)
        assertEquals(listOf("CN"), rule!!.geoips)
    }

    @Test
    fun `reject maps to reject action`() {
        val rule = parseRule("DOMAIN,ad.example,REJECT")
        assertNotNull(rule)
        assertEquals(RuleAction.REJECT, rule!!.action)
        assertEquals(listOf("ad.example"), rule.domains)
    }

    @Test
    fun `rule-set maps to ruleSetTags`() {
        val rule = parseRule("RULE-SET,proxy-list,PROXY")
        assertNotNull(rule)
        assertEquals(listOf("proxy-list"), rule!!.ruleSetTags)
    }

    @Test
    fun `match and final are not imported as rules`() {
        assertNull(parseRule("MATCH,PROXY"))
        assertNull(parseRule("FINAL,DIRECT"))
    }

    @Test
    fun `unsupported kinds are skipped`() {
        assertNull(parseRule("IN-TYPE,SOCKS,DIRECT"))
        assertNull(parseRule("IN-PORT,7890,DIRECT"))
    }
}
