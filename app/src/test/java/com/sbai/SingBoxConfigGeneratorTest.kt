package com.sbai

import com.sbai.data.AppState
import com.sbai.data.DnsGroup
import com.sbai.data.DnsServer
import com.sbai.data.DnsServerType
import com.sbai.data.LoadBalanceConfig
import com.sbai.data.LoadBalanceMode
import com.sbai.data.ProxyNode
import com.sbai.data.RouteRule
import com.sbai.data.RuleAction
import com.sbai.data.RuleLogic
import com.sbai.data.RouteRuleSet
import com.sbai.data.RuleSetType
import com.sbai.service.SingBoxConfigGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigGeneratorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(state: AppState) =
        json.parseToJsonElement(SingBoxConfigGenerator.generate(state)).jsonObject

    private fun dnsRules(cfg: kotlinx.serialization.json.JsonObject) =
        cfg["dns"]!!.jsonObject["rules"]!!.jsonArray

    private fun routeRules(cfg: kotlinx.serialization.json.JsonObject) =
        cfg["route"]!!.jsonObject["rules"]!!.jsonArray

    private fun outbounds(cfg: kotlinx.serialization.json.JsonObject) =
        cfg["outbounds"]!!.jsonArray

    @Test
    fun `route rule with per-line values and multi-select network protocol`() {
        val rule = RouteRule(
            name = "test",
            action = RuleAction.PROXY,
            domains = listOf("a.com", "b.com"),
            ipCidrs = listOf("1.2.3.0/24"),
            networks = listOf("tcp", "udp"),
            protocols = listOf("http", "tls"),
        )
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val rr = routeRules(cfg).last().jsonObject
        assertEquals(2, rr["domain"]!!.jsonArray.size)
        assertEquals(2, rr["network"]!!.jsonArray.size)
        assertEquals(2, rr["protocol"]!!.jsonArray.size)
        assertEquals("direct", rr["outbound"]!!.jsonPrimitive.content) // 无节点时代理入口回退为 direct
    }

    @Test
    fun `logical and or with invert`() {
        val rule = RouteRule(
            action = RuleAction.DIRECT,
            domains = listOf("a.com"),
            ipCidrs = listOf("1.2.3.0/24"),
            logic = RuleLogic.AND,
            invert = true,
        )
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val rr = routeRules(cfg).last().jsonObject
        assertEquals("logical", rr["type"]!!.jsonPrimitive.content)
        assertEquals("and", rr["mode"]!!.jsonPrimitive.content)
        assertEquals("true", rr["invert"]!!.jsonPrimitive.content)
        assertEquals("direct", rr["outbound"]!!.jsonPrimitive.content)
        assertEquals(2, rr["rules"]!!.jsonArray.size)
    }

    @Test
    fun `dns group resolves to first server for route rule dns rule`() {
        val rule = RouteRule(
            action = RuleAction.PROXY,
            domains = listOf("a.com"),
            dnsTag = "my-group",
        )
        val state = AppState(
            routeRules = listOf(rule),
            dnsServers = listOf(
                DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5"),
                DnsServer(tag = "d2", type = DnsServerType.UDP, address = "8.8.8.8"),
            ),
            dnsGroups = listOf(DnsGroup(name = "my-group", serverTags = listOf("d2", "d1"))),
        )
        val cfg = parse(state)
        val dr = dnsRules(cfg).single().jsonObject
        assertEquals("d2", dr["server"]!!.jsonPrimitive.content)
        assertNull(dr["ip_strategy"])
    }

    @Test
    fun `ipv4-only route rule generates ip_strategy dns rule`() {
        val rule = RouteRule(
            action = RuleAction.DIRECT,
            domains = listOf("a.com"),
            ipv4 = true,
            ipv6 = false,
        )
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val dr = dnsRules(cfg).single().jsonObject
        assertEquals("ipv4_only", dr["ip_strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dns group and ip strategy merge into single dns rule`() {
        // 需求 4 + 5：同时选择 DNS 和取消 IPv6 时，只生成一条 DNS 规则
        val rule = RouteRule(
            action = RuleAction.PROXY,
            domains = listOf("a.com"),
            dnsTag = "d1",
            ipv4 = true,
            ipv6 = false,
        )
        val state = AppState(
            routeRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val cfg = parse(state)
        val rules = dnsRules(cfg)
        assertEquals(1, rules.size)
        val dr = rules.single().jsonObject
        assertEquals("d1", dr["server"]!!.jsonPrimitive.content)
        assertEquals("ipv4_only", dr["ip_strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `block rule generates no dns rule`() {
        val rule = RouteRule(
            action = RuleAction.BLOCK,
            domains = listOf("a.com"),
            dnsTag = "d1",
            ipv4 = true,
            ipv6 = false,
        )
        val state = AppState(
            routeRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val cfg = parse(state)
        assertEquals(0, dnsRules(cfg).size)
        val rr = routeRules(cfg).last().jsonObject
        assertEquals("block", rr["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ip-only rule generates no dns rule`() {
        val rule = RouteRule(
            action = RuleAction.DIRECT,
            ipCidrs = listOf("1.2.3.0/24"),
            dnsTag = "d1",
        )
        val state = AppState(
            routeRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val cfg = parse(state)
        assertEquals(0, dnsRules(cfg).size)
    }

    @Test
    fun `rule set ipv4 ipv6 generates dns rule`() {
        val rs = RouteRuleSet(
            tag = "geoip-cn",
            type = RuleSetType.REMOTE,
            url = "https://example.com/geoip-cn.json",
            ipv4 = true,
            ipv6 = false,
        )
        val state = AppState(
            routeRuleSets = listOf(rs),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val cfg = parse(state)
        val dr = dnsRules(cfg).single().jsonObject
        assertEquals("dns-default", dr["server"]!!.jsonPrimitive.content)
        assertEquals("ipv4_only", dr["ip_strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `load balance balanced mode with auto wrapper`() {
        val node = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x"}""",
        )
        val state = AppState(
            proxyNodes = listOf(node),
            loadBalance = LoadBalanceConfig(
                enabled = true,
                mode = LoadBalanceMode.BALANCED,
                autoEnabled = true,
            ),
        )
        val cfg = parse(state)
        val obs = outbounds(cfg)
        val tags = obs.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("lb" in tags)
        assertTrue("auto" in tags)
        assertTrue("n1" in tags)

        val lb = obs.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "lb" }.jsonObject
        assertEquals("urltest", lb["type"]!!.jsonPrimitive.content)
        assertTrue(lb["tolerance"]!!.jsonPrimitive.content.toInt() > 0)

        val auto = obs.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "auto" }.jsonObject
        assertEquals("urltest", auto["type"]!!.jsonPrimitive.content)

        // 路由 final 应指向 auto
        assertEquals("auto", cfg["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `load balance manual mode uses selector`() {
        val node = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x"}""",
        )
        val state = AppState(
            proxyNodes = listOf(node),
            loadBalance = LoadBalanceConfig(
                enabled = true,
                mode = LoadBalanceMode.MANUAL,
                autoEnabled = false,
            ),
        )
        val cfg = parse(state)
        val obs = outbounds(cfg)
        val selector = obs.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "lb-selector" }.jsonObject
        assertEquals("selector", selector["type"]!!.jsonPrimitive.content)
        // 无 auto
        assertTrue(obs.none { it.jsonObject["tag"]!!.jsonPrimitive.content == "auto" })
    }

    @Test
    fun `dns servers no detour required`() {
        val state = AppState(
            dnsServers = listOf(
                DnsServer(tag = "d1", type = DnsServerType.HTTPS, address = "https://dns.google/dns-query"),
            ),
        )
        val cfg = parse(state)
        val servers = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray
        val d1 = servers.first().jsonObject
        assertEquals("dns.google", d1["server"]!!.jsonPrimitive.content)
        assertNull(d1["detour"])
    }

    @Test
    fun `dns server ecs and ech emitted`() {
        val state = AppState(
            dnsServers = listOf(
                DnsServer(
                    tag = "d1", type = DnsServerType.HTTPS, address = "https://dns.google/dns-query",
                    clientSubnet = "1.0.1.0/24", echEnabled = true,
                ),
            ),
        )
        val cfg = parse(state)
        val d1 = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject
        assertEquals("1.0.1.0/24", d1["client_subnet"]!!.jsonPrimitive.content)
        assertEquals("true", d1["tls"]!!.jsonObject["ech"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `default config has tun inbound and dns fallback`() {
        val cfg = parse(AppState())
        val inbounds = cfg["inbounds"]!!.jsonArray
        assertEquals("tun", inbounds.first().jsonObject["type"]!!.jsonPrimitive.content)
        val servers = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray
        assertTrue(servers.size >= 2)
        assertNotNull(cfg["route"]!!.jsonObject["final"])
    }
}
