package com.sbai

import com.sbai.data.AppState
import com.sbai.data.RouteRule
import com.sbai.data.RouteRuleSet
import com.sbai.data.RuleAction
import com.sbai.data.RuleLogic
import com.sbai.service.SingBoxConfigGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：路由规则引用未声明的 rule_set（如 geosite-cn）会让 sing-box 拒绝启动
 * （"rule-set not found"），这是 VPN 无法启动的已证实原因之一。
 */
class RuleSetReferenceIntegrityTest {

    private fun cfg(state: AppState): JsonObject =
        Json.parseToJsonElement(SingBoxConfigGenerator.generate(state)).jsonObject

    private fun declaredTags(c: JsonObject): Set<String> =
        c["route"]!!.jsonObject["rule_set"]?.jsonArray
            ?.map { it.jsonObject["tag"]!!.jsonPrimitive.content }?.toSet() ?: emptySet()

    private fun referencedTags(c: JsonObject): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(o: JsonObject) {
            o["rule_set"]?.jsonArray?.forEach { out.add(it.jsonPrimitive.content) }
            o["rules"]?.jsonArray?.forEach { (it as? JsonObject)?.let(::walk) }
        }
        c["route"]!!.jsonObject["rules"]?.jsonArray?.forEach { walk(it.jsonObject) }
        c["dns"]?.jsonObject?.get("rules")?.jsonArray?.forEach { walk(it.jsonObject) }
        return out
    }

    @Test fun `and rule referencing undeclared rule set is skipped`() {
        val rule = RouteRule(action = RuleAction.ROUTE_DIRECT, ruleSetTags = listOf("geosite-cn"))
        val c = cfg(AppState(routeRules = listOf(rule)))
        assertFalse(referencedTags(c).contains("geosite-cn"))
    }

    @Test fun `every referenced rule set is declared`() {
        val rules = listOf(
            RouteRule(action = RuleAction.ROUTE_DIRECT, ruleSetTags = listOf("geosite-cn", "declared")),
            RouteRule(action = RuleAction.ROUTE_PROXY, ruleSetTags = listOf("https://example.com/a.srs")),
            RouteRule(action = RuleAction.ROUTE_DIRECT, logic = RuleLogic.OR,
                domains = listOf("a.com"), ruleSetTags = listOf("missing")),
        )
        val c = cfg(AppState(
            routeRules = rules,
            routeRuleSets = listOf(RouteRuleSet(tag = "declared", url = "https://example.com/d.srs")),
        ))
        val declared = declaredTags(c)
        assertTrue(referencedTags(c).all { it in declared })
    }

    @Test fun `or rule keeps remaining conditions without missing reference`() {
        val rule = RouteRule(action = RuleAction.ROUTE_DIRECT, logic = RuleLogic.OR,
            domains = listOf("a.com"), ruleSetTags = listOf("missing"))
        val c = cfg(AppState(routeRules = listOf(rule)))
        val routeRules = c["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val hit = routeRules.firstOrNull { r -> r["domain"]?.jsonArray?.any { it.jsonPrimitive.content == "a.com" } == true }
        assertTrue(hit != null)
        assertFalse(hit!!.containsKey("rule_set"))
    }

    @Test fun `or rule with only missing reference does not become match-all`() {
        val rule = RouteRule(action = RuleAction.ROUTE_DIRECT, logic = RuleLogic.OR, ruleSetTags = listOf("missing"))
        val c = cfg(AppState(routeRules = listOf(rule)))
        val directMatchAll = c["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
            .count { r -> r.keys == setOf("outbound") && r["outbound"]!!.jsonPrimitive.content == "direct" }
        assertEquals(0, directMatchAll)
    }

    @Test fun `tun inbound has no legacy sniff fields`() {
        val c = cfg(AppState())
        c["inbounds"]!!.jsonArray.map { it.jsonObject }.forEach { ib ->
            assertFalse(ib.containsKey("sniff"))
            assertFalse(ib.containsKey("sniff_override_destination"))
            assertFalse(ib.containsKey("domain_strategy"))
        }
    }

    @Test fun `no legacy special outbounds removed in sing-box 1_13`() {
        val c = cfg(AppState())
        val types = c["outbounds"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertFalse(types.contains("block"))
        assertFalse(types.contains("dns"))
    }

    @Test fun `dns servers never emit legacy address_resolver`() {
        val server = com.sbai.data.DnsServer(tag = "d1", type = com.sbai.data.DnsServerType.HTTPS,
            address = "https://dns.google/dns-query", addressResolver = "d0")
        val local = com.sbai.data.DnsServer(tag = "d0", type = com.sbai.data.DnsServerType.UDP, address = "223.5.5.5")
        val c = cfg(AppState(dnsServers = listOf(local, server)))
        val servers = c["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        assertTrue(servers.none { it.containsKey("address_resolver") })
        assertEquals("d0", servers.first { it["tag"]!!.jsonPrimitive.content == "d1" }["domain_resolver"]!!.jsonPrimitive.content)
    }

    @Test fun `legacy final block falls back to entry outbound`() {
        val c = cfg(AppState(settings = com.sbai.data.AppSettings(finalOutbound = "block")))
        val fin = c["route"]!!.jsonObject["final"]!!.jsonPrimitive.content
        val tags = c["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue(fin in tags)
    }

    @Test fun `default domain resolver is a direct dns server`() {
        val c = cfg(AppState())
        val route = c["route"]!!.jsonObject
        val resolver = route["default_domain_resolver"]!!.jsonPrimitive.content
        val srv = c["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == resolver }
        assertFalse(srv.containsKey("detour"))
    }
}
