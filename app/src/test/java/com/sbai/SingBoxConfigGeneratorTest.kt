package com.sbai

import com.sbai.data.AppState
import com.sbai.data.DnsGroup
import com.sbai.data.DnsRule
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
    fun `and logic flattens into single rule with invert`() {
        // AND 语义 = sing-box 单条 rule 的默认行为：所有字段类别平铺（不包 logical）
        val rule = RouteRule(
            action = RuleAction.DIRECT,
            domains = listOf("a.com"),
            ipCidrs = listOf("1.2.3.0/24"),
            logic = RuleLogic.AND,
            invert = true,
        )
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val rr = routeRules(cfg).last().jsonObject
        assertNull(rr["type"])   // AND 平铺，无 logical 包装
        assertNull(rr["mode"])
        assertEquals("a.com", rr["domain"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("1.2.3.0/24", rr["ip_cidr"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("true", rr["invert"]!!.jsonPrimitive.content)
        assertEquals("direct", rr["outbound"]!!.jsonPrimitive.content)
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
        // 有自定义 DNS 时，ip_strategy 规则指向首个启用的 DNS（而非悬空的 dns-default）
        assertEquals("d1", dr["server"]!!.jsonPrimitive.content)
        assertEquals("ipv4_only", dr["ip_strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rule set ip strategy falls back to built-in dns-default when no custom dns`() {
        val rs = RouteRuleSet(
            tag = "geoip-cn",
            type = RuleSetType.REMOTE,
            url = "https://example.com/geoip-cn.json",
            ipv4 = false,
            ipv6 = true,
        )
        val cfg = parse(AppState(routeRuleSets = listOf(rs)))
        val dr = dnsRules(cfg).single().jsonObject
        assertEquals("dns-default", dr["server"]!!.jsonPrimitive.content)
        assertEquals("ipv6_only", dr["ip_strategy"]!!.jsonPrimitive.content)
        // dns-default 必须真实存在于 servers 中，不能悬空
        val servers = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray
        assertTrue(servers.any { it.jsonObject["tag"]!!.jsonPrimitive.content == "dns-default" })
    }

    @Test
    fun `empty dns group does not emit dangling server reference`() {
        val rule = RouteRule(
            action = RuleAction.PROXY,
            domains = listOf("a.com"),
            dnsTag = "empty-group",
        )
        val state = AppState(
            routeRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
            dnsGroups = listOf(DnsGroup(name = "empty-group", serverTags = emptyList())),
        )
        val cfg = parse(state)
        // 空 group → 不生成 DNS 规则（避免悬空引用）
        assertEquals(0, dnsRules(cfg).size)
    }

    @Test
    fun `both ipv4 and ipv6 unchecked emits no ip_strategy rule`() {
        val rule = RouteRule(
            action = RuleAction.DIRECT,
            domains = listOf("a.com"),
            ipv4 = false,
            ipv6 = false,
        )
        val cfg = parse(AppState(routeRules = listOf(rule)))
        assertEquals(0, dnsRules(cfg).size)
    }

    @Test
    fun `duplicate node tags are deduplicated`() {
        val json = """{"type":"vless","tag":"dup","server":"1.2.3.4","server_port":443,"uuid":"x"}"""
        val state = AppState(
            proxyNodes = listOf(
                ProxyNode(name = "a", outboundJson = json),
                ProxyNode(name = "b", outboundJson = json),
            ),
        )
        val cfg = parse(state)
        val tags = outbounds(cfg).map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(1, tags.count { it == "dup" })
    }

    @Test
    fun `doh path is preserved`() {
        val state = AppState(
            dnsServers = listOf(
                DnsServer(tag = "doh", type = DnsServerType.HTTPS, address = "https://dns.alidns.com/dns-query"),
            ),
        )
        val d = parse(state)["dns"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject
        assertEquals("dns.alidns.com", d["server"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", d["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fakeip has valid ranges and no clash api is exposed`() {
        val state = AppState(
            dnsServers = listOf(DnsServer(tag = "fake", type = DnsServerType.FAKEIP)),
        )
        val cfg = parse(state)
        val fake = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject
        assertEquals("198.18.0.0/15", fake["inet4_range"]!!.jsonPrimitive.content)
        assertEquals("fc00::/18", fake["inet6_range"]!!.jsonPrimitive.content)
        // 控制面不得通过 clash_api 暴露
        assertNull(cfg["experimental"]!!.jsonObject["clash_api"])
    }

    @Test
    fun `inline rule set has no format field`() {
        val rs = RouteRuleSet(
            tag = "local-set",
            type = RuleSetType.LOCAL,
            localContent = """{"version":3,"rules":[{"domain_suffix":["a.com"]}]}""",
        )
        val cfg = parse(AppState(routeRuleSets = listOf(rs)))
        val set = cfg["route"]!!.jsonObject["rule_set"]!!.jsonArray.first().jsonObject
        assertEquals("inline", set["type"]!!.jsonPrimitive.content)
        assertNull(set["format"])
        assertEquals(1, set["rules"]!!.jsonArray.size)
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
    fun `per-app proxy include and exclude`() {
        val include = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    perAppProxy = com.sbai.data.PerAppProxy(
                        mode = com.sbai.data.PerAppProxyMode.INCLUDE,
                        packages = listOf("com.example.a", "com.example.b"),
                    ),
                ),
            ),
        )
        val tun = include["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(2, tun["include_package"]!!.jsonArray.size)

        val exclude = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    perAppProxy = com.sbai.data.PerAppProxy(
                        mode = com.sbai.data.PerAppProxyMode.EXCLUDE,
                        packages = listOf("com.example.c"),
                    ),
                ),
            ),
        )
        val tun2 = exclude["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals(1, tun2["exclude_package"]!!.jsonArray.size)
        assertTrue(!tun2.containsKey("include_package"))
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

    @Test
    fun `round_robin emits fork balancer pool extension`() {
        val node = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x"}""",
        )
        val state = AppState(
            proxyNodes = listOf(node),
            loadBalance = LoadBalanceConfig(
                enabled = true,
                mode = LoadBalanceMode.BALANCED,
                urltestMode = com.sbai.data.UrltestMode.ROUND_ROBIN,
                pool = 3,
                poolTolerance = 0,
                stickyHash = listOf(com.sbai.data.StickyHashKey.PROCESS, com.sbai.data.StickyHashKey.DOMAIN),
            ),
        )
        val cfg = parse(state)
        val lb = outbounds(cfg).first { it.jsonObject["tag"]!!.jsonPrimitive.content == "lb" }.jsonObject
        assertEquals("urltest", lb["type"]!!.jsonPrimitive.content)
        assertEquals("round_robin", lb["mode"]!!.jsonPrimitive.content)
        val balancer = lb["balancer"]!!.jsonObject
        assertEquals(3, balancer["pool"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, balancer["pool_tolerance"]!!.jsonPrimitive.content.toInt())
        val sticky = balancer["sticky_hash"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("process", "domain"), sticky)
    }

    @Test
    fun `least_test emits no balancer block`() {
        val node = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x"}""",
        )
        val state = AppState(
            proxyNodes = listOf(node),
            loadBalance = LoadBalanceConfig(
                enabled = true,
                mode = LoadBalanceMode.BALANCED,
                urltestMode = com.sbai.data.UrltestMode.LEAST_TEST,
            ),
        )
        val lb = parse(state).let { cfg ->
            outbounds(cfg).first { it.jsonObject["tag"]!!.jsonPrimitive.content == "lb" }.jsonObject
        }
        assertNull(lb["mode"])
        assertNull(lb["balancer"])
    }

    @Test
    fun `manual dns rules come before auto-derived rules`() {
        val manual = DnsRule(
            name = "manual",
            domains = listOf("manual.com"),
            server = "d1",
        )
        val autoSrc = RouteRule(
            action = RuleAction.DIRECT,
            domains = listOf("auto.com"),
            dnsTag = "d1",
        )
        val state = AppState(
            routeRules = listOf(autoSrc),
            dnsRules = listOf(manual),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val cfg = parse(state)
        val rules = dnsRules(cfg)
        assertEquals(2, rules.size)
        // 手动在前
        assertEquals("manual.com", rules[0].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("auto.com", rules[1].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `auto dns rules helper reflects route rules for UI preview`() {
        val rule = RouteRule(
            action = RuleAction.DIRECT,
            domains = listOf("a.com"),
            dnsTag = "d1",
        )
        val state = AppState(
            routeRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val auto = SingBoxConfigGenerator.autoDnsRules(state)
        assertEquals(1, auto.size)
        assertEquals(rule.id, auto[0].autoFromRouteRuleId)
        assertEquals("d1", auto[0].server)
    }

    @Test
    fun `or logic with single category flattens instead of wrapping logical`() {
        val rule = RouteRule(
            action = RuleAction.DIRECT,
            domains = listOf("a.com"),
            logic = RuleLogic.OR,
        )
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val rr = routeRules(cfg).last().jsonObject
        // 只有一个条件类别时 OR == AND，直接平铺，不包 logical
        assertNull(rr["type"])
        assertEquals("direct", rr["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `or logic with multiple categories wraps logical or`() {
        val rule = RouteRule(
            action = RuleAction.DIRECT,
            domains = listOf("a.com"),
            ipCidrs = listOf("1.2.3.0/24"),
            logic = RuleLogic.OR,
        )
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val rr = routeRules(cfg).last().jsonObject
        assertEquals("logical", rr["type"]!!.jsonPrimitive.content)
        assertEquals("or", rr["mode"]!!.jsonPrimitive.content)
        assertEquals(2, rr["rules"]!!.jsonArray.size)
    }
}
