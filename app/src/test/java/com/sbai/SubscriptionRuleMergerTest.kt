package com.sbai

import com.sbai.data.RouteRule
import com.sbai.data.RuleAction
import com.sbai.service.SubscriptionRuleMerger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRuleMergerTest {

    @Test
    fun `rules of the same action collapse into one rule set`() {
        val imported = listOf(
            RouteRule(action = RuleAction.ROUTE_DIRECT, domainSuffixes = listOf("cn")),
            RouteRule(action = RuleAction.ROUTE_DIRECT, domains = listOf("a.com")),
            RouteRule(action = RuleAction.ROUTE_PROXY, domainSuffixes = listOf("google.com")),
            RouteRule(action = RuleAction.REJECT, domainKeywords = listOf("ads")),
            RouteRule(action = RuleAction.ROUTE_PROXY, ports = listOf(853)),
        )
        val merged = SubscriptionRuleMerger.merge("sub-1", "机场", imported)

        assertEquals(listOf("机场直连", "机场代理", "机场拦截"), merged.ruleSets.map { it.tag })
        assertEquals(3, merged.rules.size)
        assertTrue(merged.rules.all { it.subscriptionId == "sub-1" })

        val direct = Json.parseToJsonElement(merged.ruleSets.first().localContent).jsonObject
        val rule = direct["rules"]!!.jsonArray.first().jsonObject
        assertEquals(listOf("cn"), rule["domain_suffix"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("a.com"), rule["domain"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(SubscriptionRuleMerger.isMergeable(imported.last()))
    }
}
