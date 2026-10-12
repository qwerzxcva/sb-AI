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
import com.sbai.service.RouteRuleJsonCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
            action = RuleAction.ROUTE_PROXY,
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
        // 无任何节点时入口回退为 direct（单节点/无节点不套 auto，auto 只包多候选出口）
        assertEquals("direct", rr["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `auto mode works independently of load balance`() {
        // 关掉负载均衡、只开自动模式 + 多节点：入口应为 auto（urltest 优选）
        val node1 = ProxyNode(name = "n1", outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x"}""")
        val node2 = ProxyNode(name = "n2", outboundJson = """{"type":"vless","tag":"n2","server":"1.2.3.5","server_port":443,"uuid":"y"}""")
        val state = AppState(
            proxyNodes = listOf(node1, node2),
            loadBalance = LoadBalanceConfig(enabled = false, autoEnabled = true),
        )
        val cfg = parse(state)
        assertEquals("auto", cfg["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        val tags = outbounds(cfg).map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("auto" in tags)
        // 不应有 lb / lb-selector（负载均衡关闭）
        assertTrue("lb" !in tags && "lb-selector" !in tags)
    }

    @Test
    fun `disabling auto and lb falls back to direct`() {
        val rule = RouteRule(action = RuleAction.ROUTE_PROXY, domains = listOf("a.com"))
        val state = AppState(
            routeRules = listOf(rule),
            loadBalance = LoadBalanceConfig(enabled = false, autoEnabled = false),
        )
        val rr = parse(state)["route"]!!.jsonObject["rules"]!!.jsonArray.last().jsonObject
        assertEquals("direct", rr["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `and logic flattens into single rule with invert`() {
        // AND 语义 = sing-box 单条 rule 的默认行为：所有字段类别平铺（不包 logical）
        val rule = RouteRule(
            action = RuleAction.ROUTE_DIRECT,
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
            action = RuleAction.ROUTE_PROXY,
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
            action = RuleAction.ROUTE_DIRECT,
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
            action = RuleAction.ROUTE_PROXY,
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
            action = RuleAction.REJECT,
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
        // BLOCK 现在用 action=reject（sing-box-lx fork 语义），不再用 outbound=block
        assertEquals("reject", rr["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ip-only rule generates no dns rule`() {
        val rule = RouteRule(
            action = RuleAction.ROUTE_DIRECT,
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
            action = RuleAction.ROUTE_PROXY,
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
            action = RuleAction.ROUTE_DIRECT,
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
    fun `wireguard endpoint nodes go to endpoints not outbounds`() {
        val wg = ProxyNode(
            name = "warp",
            outboundJson = """{"type":"wireguard","tag":"warp","mtu":1408,"address":["172.16.0.2/32"],"private_key":"AAAA","peers":[{"address":"engage.cloudflareclient.com","port":2408,"public_key":"BBBB","allowed_ips":["0.0.0.0/0","::/0"]}]}""",
        )
        val vless = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x"}""",
        )
        val cfg = parse(AppState(proxyNodes = listOf(wg, vless)))

        // wireguard 进 endpoints
        val endpoints = cfg["endpoints"]!!.jsonArray
        assertEquals(1, endpoints.size)
        assertEquals("wireguard", endpoints[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("warp", endpoints[0].jsonObject["tag"]!!.jsonPrimitive.content)

        // vless 进 outbounds，且 outbounds 不含 wireguard
        val obs = outbounds(cfg)
        assertTrue(obs.none { it.jsonObject["type"]!!.jsonPrimitive.content == "wireguard" })
        assertTrue(obs.any { it.jsonObject["tag"]!!.jsonPrimitive.content == "n1" })
    }

    @Test
    fun `no endpoints array when no endpoint nodes`() {
        val vless = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x"}""",
        )
        val cfg = parse(AppState(proxyNodes = listOf(vless)))
        assertNull(cfg["endpoints"])
    }

    @Test
    fun `wireguard tag participates in proxy group`() {
        val wg = ProxyNode(
            name = "warp",
            outboundJson = """{"type":"wireguard","tag":"warp","mtu":1408,"address":["172.16.0.2/32"],"private_key":"AAAA","peers":[{"address":"e.com","port":2408,"public_key":"BBBB","allowed_ips":["0.0.0.0/0"]}]}""",
        )
        val vless = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x"}""",
        )
        // 多节点 → selector 组 proxy，应引用 endpoint tag 和 outbound tag
        val cfg = parse(AppState(proxyNodes = listOf(wg, vless)))
        val proxyGroup = outbounds(cfg).first { it.jsonObject["tag"]!!.jsonPrimitive.content == "proxy" }.jsonObject
        val members = proxyGroup["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("warp" in members)
        assertTrue("n1" in members)
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
        assertEquals("10.0.0.0/8", fake["inet4_range"]!!.jsonPrimitive.content)
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
            action = RuleAction.ROUTE_DIRECT,
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
            action = RuleAction.ROUTE_DIRECT,
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
            action = RuleAction.ROUTE_DIRECT,
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
            action = RuleAction.ROUTE_DIRECT,
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

    @Test
    fun `inline url rule set auto-created and deduplicated`() {
        val url1 = "https://example.com/geoip-cn.srs"
        val url2 = "https://example.com/geosite-ads.srs"
        val rule = RouteRule(
            action = RuleAction.ROUTE_DIRECT,
            ruleSetTags = listOf(url1, "existing-tag", url2, url1), // url1 重复
        )
        val state = AppState(routeRules = listOf(rule), routeRuleSets = listOf())
        val cfg = parse(state)

        // 路由规则里的 rule_set 应引用生成的 tag（不是原始 URL）
        val rr = routeRules(cfg).last().jsonObject
        val tags = rr["rule_set"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(tags.none { it.startsWith("http") })
        assertTrue(tags.contains("existing-tag"))
        assertEquals(2, tags.count { it.startsWith("url-") })  // 2 个不同 URL → 2 个 tag

        // route.rule_set 里应自动创建 2 个 remote 规则集（去重后）
        val ruleSets = cfg["route"]!!.jsonObject["rule_set"]!!.jsonArray
        val urls = ruleSets.map { it.jsonObject["url"]?.jsonPrimitive?.content }
        assertEquals(2, urls.count { it != null })
        assertTrue(urls.contains(url1))
        assertTrue(urls.contains(url2))
        ruleSets.forEach { rs ->
            assertEquals("remote", rs.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("source", rs.jsonObject["format"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `inline url rule set works alongside manual domain and ip`() {
        val rule = RouteRule(
            action = RuleAction.ROUTE_DIRECT,
            domains = listOf("a.com"),
            ipCidrs = listOf("1.2.3.0/24"),
            ruleSetTags = listOf("https://example.com/geoip-cn.srs"),
        )
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val rr = routeRules(cfg).last().jsonObject
        // 域名/IP/规则集在同一条规则里（用户核心意图：不用创建多条规则）
        assertEquals("a.com", rr["domain"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("1.2.3.0/24", rr["ip_cidr"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(rr["rule_set"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `custom tun addresses are used`() {
        val cfg = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    tunAddress = "198.19.0.1/29",
                    tunAddress6 = "fd00::1/64",
                ),
            ),
        )
        val tun = cfg["inbounds"]!!.jsonArray.first().jsonObject
        val addrs = tun["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("198.19.0.1/29", addrs[0])
        assertEquals("fd00::1/64", addrs[1])
    }

    @Test
    fun `default tun addresses when not customized`() {
        val cfg = parse(AppState())
        val tun = cfg["inbounds"]!!.jsonArray.first().jsonObject
        val addrs = tun["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("172.18.0.1/30", addrs[0])
        assertEquals("fdfe:dcba:9876::1/126", addrs[1])
    }

    @Test
    fun `block action emits reject with method not outbound`() {
        val rule = RouteRule(action = RuleAction.REJECT, domains = listOf("ad.com"), rejectMethod = "drop")
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val rr = routeRules(cfg).last().jsonObject
        assertEquals("reject", rr["action"]!!.jsonPrimitive.content)
        assertEquals("drop", rr["reject_method"]!!.jsonPrimitive.content)
        assertNull(rr["outbound"])
    }

    @Test
    fun `block default method emits reject without method`() {
        val rule = RouteRule(action = RuleAction.REJECT, domains = listOf("ad.com"))
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val rr = routeRules(cfg).last().jsonObject
        assertEquals("reject", rr["action"]!!.jsonPrimitive.content)
        assertNull(rr["reject_method"])
    }

    @Test
    fun `source and app and network-env fields emitted in AND mode`() {
        val rule = RouteRule(
            action = RuleAction.ROUTE_DIRECT,
            sourceIpCidrs = listOf("192.168.0.0/16"),
            sourcePorts = listOf(8080),
            packageNames = listOf("com.example.app"),
            processNames = listOf("chrome"),
            users = listOf("root"),
            networkTypes = listOf("wifi"),
            wifiSsids = listOf("HomeWiFi"),
            clashMode = "rule",
            sourceIpIsPrivate = true,
            ipIsPrivate = true,
            networkIsExpensive = true,
        )
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val rr = routeRules(cfg).last().jsonObject
        assertEquals("192.168.0.0/16", rr["source_ip_cidr"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("8080", rr["source_port"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("com.example.app", rr["package_name"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("chrome", rr["process_name"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("root", rr["user"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("wifi", rr["network_type"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("HomeWiFi", rr["wifi_ssid"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("rule", rr["clash_mode"]!!.jsonPrimitive.content)
        assertEquals("true", rr["source_ip_is_private"]!!.jsonPrimitive.content)
        assertEquals("true", rr["ip_is_private"]!!.jsonPrimitive.content)
        assertEquals("true", rr["network_is_expensive"]!!.jsonPrimitive.content)
    }

    @Test
    fun `or logic splits into six categories`() {
        val rule = RouteRule(
            action = RuleAction.ROUTE_DIRECT,
            domains = listOf("a.com"),
            sourceIpCidrs = listOf("10.0.0.0/8"),
            packageNames = listOf("com.x"),
            logic = RuleLogic.OR,
        )
        val cfg = parse(AppState(routeRules = listOf(rule)))
        val rr = routeRules(cfg).last().jsonObject
        assertEquals("logical", rr["type"]!!.jsonPrimitive.content)
        assertEquals("or", rr["mode"]!!.jsonPrimitive.content)
        // domain + source + app = 3 个非空类别
        assertEquals(3, rr["rules"]!!.jsonArray.size)
    }

    @Test
    fun `manual dns rule route-options emits dns option fields only`() {
        // DNS 的 route-options 只接受 timeout/strategy/disable_cache/rewrite_ttl/
        // client_subnet/remove_client_subnet；answer/ns/extra 属于 predefined 动作。
        val rule = DnsRule(
            name = "rewrite",
            domains = listOf("example.com"),
            server = "d1",
            action = "route-options",
            answers = listOf("1.2.3.4"),
            ns = listOf("ns1.example.com"),
            rewriteTtl = 60,
            timeout = "4s",
        )
        val state = AppState(
            dnsRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val dr = parse(state)["dns"]!!.jsonObject["rules"]!!.jsonArray.first().jsonObject
        assertEquals("route-options", dr["action"]!!.jsonPrimitive.content)
        assertEquals(60, dr["rewrite_ttl"]!!.jsonPrimitive.content.toInt())
        assertEquals("4s", dr["timeout"]!!.jsonPrimitive.content)
        // answer/ns 不得出现在 route-options 下（内核会拒绝）
        assertNull(dr["answer"])
        assertNull(dr["ns"])
        // route-options 不写 server
        assertNull(dr["server"])
    }

    @Test
    fun `manual dns rule predefined carries rcode and answers`() {
        val rule = DnsRule(
            name = "fixed",
            domains = listOf("example.com"),
            server = "d1",
            action = "pre-defined",
            rcode = "nxdomain",
            answers = listOf("1.2.3.4"),
        )
        val state = AppState(
            dnsRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val dr = parse(state)["dns"]!!.jsonObject["rules"]!!.jsonArray.first().jsonObject
        assertEquals("predefined", dr["action"]!!.jsonPrimitive.content)
        assertEquals("nxdomain", dr["rcode"]!!.jsonPrimitive.content)
        // 内核用 dns.ZoneParser 解析 answer，裸 IP 无法解析，必须补成合法 zone 记录
        assertEquals("1.2.3.4 60 IN A", dr["answer"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `predefined answer preserves already valid zone records`() {
        val rule = DnsRule(
            name = "fixed",
            domains = listOf("example.com"),
            server = "d1",
            action = "pre-defined",
            answers = listOf("example.com. 300 IN A 1.2.3.4"),
        )
        val state = AppState(
            dnsRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val dr = parse(state)["dns"]!!.jsonObject["rules"]!!.jsonArray.first().jsonObject
        assertEquals(
            "example.com. 300 IN A 1.2.3.4",
            dr["answer"]!!.jsonArray[0].jsonPrimitive.content,
        )
    }

    @Test
    fun `predefined ipv6 answer uses AAAA record`() {
        val rule = DnsRule(
            name = "fixed6",
            domains = listOf("example.com"),
            server = "d1",
            action = "pre-defined",
            answers = listOf("2001:db8::1"),
        )
        val state = AppState(
            dnsRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val dr = parse(state)["dns"]!!.jsonObject["rules"]!!.jsonArray.first().jsonObject
        assertEquals("2001:db8::1 60 IN AAAA", dr["answer"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `manual dns rule reject emits action and rcode without server`() {
        val rule = DnsRule(
            name = "block-ads",
            domainSuffixes = listOf("ads.com"),
            server = "",
            action = "reject",
            rcode = "nxdomain",
        )
        val state = AppState(
            dnsRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val dr = parse(state)["dns"]!!.jsonObject["rules"]!!.jsonArray.first().jsonObject
        assertEquals("reject", dr["action"]!!.jsonPrimitive.content)
        assertEquals("nxdomain", dr["rcode"]!!.jsonPrimitive.content)
        assertNull(dr["server"])
    }

    @Test
    fun `manual dns route rule with empty server is skipped`() {
        val rule = DnsRule(name = "bad", domains = listOf("a.com"), server = "", action = "route")
        val state = AppState(
            dnsRules = listOf(rule),
            dnsServers = listOf(DnsServer(tag = "d1", type = DnsServerType.UDP, address = "223.5.5.5")),
        )
        val rules = parse(state)["dns"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(0, rules.size)   // route 无有效 server → 整条跳过
    }

    @Test
    fun `tun stack from settings`() {
        val cfg = parse(AppState(settings = com.sbai.data.AppSettings(tunStack = com.sbai.data.TunStack.GVISOR)))
        assertEquals("gvisor", cfg["inbounds"]!!.jsonArray.first().jsonObject["stack"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tsbai is default tun stack`() {
        val cfg = parse(AppState())
        assertEquals(
            "tsbAI",
            cfg["inbounds"]!!.jsonArray.first().jsonObject["stack"]!!.jsonPrimitive.content,
        )
    }

    // ---- Clash 专有字段不得泄漏到 sing-box 配置（会让内核拒绝启动）----

    @Test
    fun `dns object has no clash-only keys`() {
        val cfg = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    dnsFallbackServers = listOf("tls://8.8.8.8"),
                    dnsFallbackIpcidr = listOf("240.0.0.0/4"),
                    dnsFakeipFilter = listOf("*.lan"),
                    dnsFakeipFilterPatterns = listOf("^stun\\."),
                    dnsNameserverPolicy = listOf("*.cn=223.5.5.5"),
                ),
            ),
        )
        val dns = cfg["dns"]!!.jsonObject
        listOf(
            "fallback",
            "fallback-filter",
            "fakeip-filter",
            "fakeip-filter-pattern",
            "nameserver-policy",
        ).forEach { key ->
            assertNull("dns.$key 是 Clash 专有字段，不得出现", dns[key])
        }
    }

    @Test
    fun `fallback becomes dns rule with match_response and real server`() {
        val cfg = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    dnsFallbackServers = listOf("tls://8.8.8.8"),
                    dnsFallbackIpcidr = listOf("240.0.0.0/4"),
                ),
            ),
        )
        val dns = cfg["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        val fallbackServer = servers.firstOrNull {
            it["type"]?.jsonPrimitive?.content == "tls" &&
                it["server"]?.jsonPrimitive?.content == "8.8.8.8"
        }
        assertNotNull("fallback 地址必须生成真实 DNS server", fallbackServer)
        val fallbackTag = fallbackServer!!["tag"]!!.jsonPrimitive.content
        val rule = dnsRules(cfg).firstOrNull {
            it.jsonObject["server"]?.jsonPrimitive?.content == fallbackTag
        }?.jsonObject
        assertNotNull("fallback 必须生成 dns rule", rule)
        assertEquals("route", rule!!["action"]!!.jsonPrimitive.content)
        assertEquals(true, rule["match_response"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(
            rule["ip_cidr"]!!.jsonArray.any { it.jsonPrimitive.content == "240.0.0.0/4" },
        )
    }

    @Test
    fun `fakeip filter becomes dns rule pointing at real resolver`() {
        val cfg = parse(
            AppState(
                dnsServers = listOf(
                    DnsServer(tag = "real", type = DnsServerType.UDP, address = "223.5.5.5"),
                ),
                settings = com.sbai.data.AppSettings(
                    dnsFakeipFilter = listOf("*.lan", "localhost"),
                ),
            ),
        )
        val rule = dnsRules(cfg).firstOrNull {
            it.jsonObject["domain"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "*.lan" } == true
        }?.jsonObject
        assertNotNull("fakeIP 过滤域名必须生成 dns rule", rule)
        assertEquals("real", rule!!["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nameserver policy bare address becomes real dns server and rule`() {
        val cfg = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    dnsNameserverPolicy = listOf("*.cn=tls://223.5.5.5"),
                ),
            ),
        )
        val dns = cfg["dns"]!!.jsonObject
        val policyServer = dns["servers"]!!.jsonArray.map { it.jsonObject }.firstOrNull {
            it["type"]?.jsonPrimitive?.content == "tls" &&
                it["server"]?.jsonPrimitive?.content == "223.5.5.5"
        }
        assertNotNull("裸地址必须先落成真实 DNS server", policyServer)
        val rule = dnsRules(cfg).firstOrNull {
            it.jsonObject["server"]?.jsonPrimitive?.content == policyServer!!["tag"]!!.jsonPrimitive.content
        }?.jsonObject
        assertNotNull("nameserver-policy 必须生成 dns rule", rule)
        assertTrue(
            rule!!["domain_suffix"]!!.jsonArray.any { it.jsonPrimitive.content == "cn" },
        )
    }

    @Test
    fun `china ip resource is referenced by a route rule`() {
        // 只声明 inline rule_set 而不被 route.rules 引用 = 死配置，China IP 永不生效
        val res = com.sbai.data.Resource(
            id = "abcdef123456",
            name = "china",
            resType = com.sbai.data.ResourceType.CHINA_IP,
            content = "36.33.64.0/20\n114.114.114.0/24",
        )
        val cfg = parse(
            AppState(
                settings = com.sbai.data.AppSettings(resources = listOf(res)),
            ),
        )
        val route = cfg["route"]!!.jsonObject
        val setTags = route["rule_set"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("rule_set 必须声明", setTags.contains("res-abcdef12"))

        // inline rule_set 的 rules 不得带 outbound（HeadlessRule 无此字段，内核会拒绝）
        val inline = route["rule_set"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]!!.jsonPrimitive.content == "res-abcdef12" }
        inline["rules"]!!.jsonArray.forEach {
            assertNull("HeadlessRule 没有 outbound 字段", it.jsonObject["outbound"])
        }

        // 必须有 route rule 引用该 rule_set 并指向 direct
        val ref = routeRules(cfg).firstOrNull {
            it.jsonObject["rule_set"]?.jsonPrimitive?.content == "res-abcdef12"
        }?.jsonObject
        assertNotNull("China IP 规则集必须被 route rule 引用", ref)
        assertEquals("direct", ref!!["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fakeip filter rules precede the fakeip catch-all`() {
        // DNS 规则按序匹配：buildDnsRules 末尾是「query_type A/AAAA → fakeip」无条件兜底，
        // fakeIP 过滤规则若排在它之后就永远匹配不到。
        val cfg = parse(
            AppState(
                dnsServers = listOf(
                    DnsServer(tag = "fake", type = DnsServerType.FAKEIP),
                    DnsServer(tag = "real", type = DnsServerType.UDP, address = "223.5.5.5"),
                ),
                settings = com.sbai.data.AppSettings(dnsFakeipFilter = listOf("*.lan")),
            ),
        )
        val rules = dnsRules(cfg).map { it.jsonObject }
        val filterIdx = rules.indexOfFirst {
            it["domain"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "*.lan" } == true
        }
        val catchAllIdx = rules.indexOfFirst {
            it["query_type"]?.jsonArray?.size == 2 &&
                it["server"]?.jsonPrimitive?.content == "fake"
        }
        assertTrue("fakeIP 过滤规则必须存在", filterIdx >= 0)
        assertTrue("fakeIP catch-all 必须存在", catchAllIdx >= 0)
        assertTrue(
            "fakeIP 过滤规则（index=$filterIdx）必须排在 catch-all（index=$catchAllIdx）之前",
            filterIdx < catchAllIdx,
        )
    }

    @Test
    fun `nameserver policy rule precedes fakeip catch-all`() {
        val cfg = parse(
            AppState(
                dnsServers = listOf(
                    DnsServer(tag = "fake", type = DnsServerType.FAKEIP),
                    DnsServer(tag = "cn", type = DnsServerType.UDP, address = "223.5.5.5"),
                ),
                settings = com.sbai.data.AppSettings(dnsNameserverPolicy = listOf("*.cn=cn")),
            ),
        )
        val rules = dnsRules(cfg).map { it.jsonObject }
        val policyIdx = rules.indexOfFirst {
            it["server"]?.jsonPrimitive?.content == "cn" &&
                it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "cn" } == true
        }
        val catchAllIdx = rules.indexOfFirst { it["query_type"]?.jsonArray?.size == 2 }
        assertTrue(policyIdx >= 0)
        assertTrue(catchAllIdx >= 0)
        assertTrue("nameserver-policy 规则必须排在 fakeIP catch-all 之前", policyIdx < catchAllIdx)
    }

    @Test
    fun `fallback cidr becomes match_response dns rule`() {
        val cfg = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    dnsFallbackServers = listOf("tls://8.8.8.8"),
                    dnsFallbackIpcidr = listOf("240.0.0.0/4"),
                ),
            ),
        )
        val dns = cfg["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        val fallbackServer = servers.firstOrNull {
            it["type"]?.jsonPrimitive?.content == "tls" &&
                it["server"]?.jsonPrimitive?.content == "8.8.8.8"
        }
        assertNotNull(fallbackServer)
        val rule = dnsRules(cfg).firstOrNull {
            it.jsonObject["server"]?.jsonPrimitive?.content == fallbackServer!!["tag"]!!.jsonPrimitive.content
        }?.jsonObject
        assertNotNull(rule)
        assertEquals(true, rule!!["match_response"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(rule["ip_cidr"]!!.jsonArray.any { it.jsonPrimitive.content == "240.0.0.0/4" })
    }

    @Test
    fun `fallback geosite and domain become separate match_response rules`() {
        val cfg = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    dnsFallbackServers = listOf("tls://8.8.8.8"),
                    dnsFallbackGeosite = listOf("geolocation-!cn"),
                    dnsFallbackDomain = listOf("+.google.com"),
                ),
            ),
        )
        val dns = cfg["dns"]!!.jsonObject
        val fallbackTag = dns["servers"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "tls" }["tag"]!!.jsonPrimitive.content
        val mine = dnsRules(cfg).filter {
            it.jsonObject["server"]?.jsonPrimitive?.content == fallbackTag
        }.map { it.jsonObject }
        // geosite 与 domain 各自独立成规则，且都带 match_response
        assertEquals(2, mine.size)
        assertTrue(mine.all { it["match_response"]!!.jsonPrimitive.content.toBoolean() })
        assertTrue(mine.any { it["geosite"]?.jsonArray?.any { g -> g.jsonPrimitive.content == "geolocation-!cn" } == true })
        assertTrue(mine.any { it["domain"]?.jsonArray?.any { g -> g.jsonPrimitive.content == "+.google.com" } == true })
    }

    @Test
    fun `no fallback rules when no fallback server configured`() {
        // 只配过滤条件但没配 fallback server 时，不得生成任何引用不存在 server 的规则
        val cfg = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    dnsFallbackIpcidr = listOf("240.0.0.0/4"),
                    dnsFallbackGeosite = listOf("geolocation-!cn"),
                ),
            ),
        )
        assertTrue(dnsRules(cfg).none { it.jsonObject["match_response"]?.jsonPrimitive?.content == "true" })
    }

    @Test
    fun `tun excludes own subnet and loopback to avoid dns routing loop`() {
        val cfg = parse(AppState())
        val tun = cfg["inbounds"]!!.jsonArray.first().jsonObject
        // dns_mode=hijack：TUN 派生 DNS 地址上的连接劫持进 DNS 模块
        assertEquals("hijack", tun["dns_mode"]!!.jsonPrimitive.content)
        val excluded = tun["route_exclude_address"]!!.jsonArray.map { it.jsonPrimitive.content }
        // TUN 自身网段必须排除，否则发往 TUN DNS 地址（address+1）的查询会被送回隧道成环
        assertTrue(excluded.any { it.startsWith("172.18.0.") })
        assertTrue(excluded.contains("127.0.0.0/8"))
        assertTrue(excluded.contains("255.255.255.255/32"))
    }

    @Test
    fun `tun excludes ipv6 subnet when ipv6 routing on`() {
        val cfg = parse(
            AppState(settings = com.sbai.data.AppSettings(ipv6Route = true)),
        )
        val excluded = cfg["inbounds"]!!.jsonArray.first().jsonObject["route_exclude_address"]!!.jsonArray
        assertTrue(excluded.any { it.jsonPrimitive.content.contains("fdfe:dcba:9876") })
    }

    @Test
    fun `dns cache capacity omitted below kernel threshold`() {
        // 内核忽略 <1024 的 cache_capacity，生成器不应输出无效值
        val small = parse(AppState(settings = com.sbai.data.AppSettings(dnsCacheCapacity = 512)))
        assertNull(small["dns"]!!.jsonObject["cache_capacity"])
        val ok = parse(AppState(settings = com.sbai.data.AppSettings(dnsCacheCapacity = 4096)))
        assertEquals(4096, ok["dns"]!!.jsonObject["cache_capacity"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `optimistic dns omitted when it conflicts with cache switches`() {
        // 内核会直接拒绝 optimistic 与 disable_cache/disable_expire 共存的配置
        val conflict = parse(
            AppState(
                settings = com.sbai.data.AppSettings(dnsOptimistic = true, dnsDisableCache = true),
            ),
        )
        assertNull(conflict["dns"]!!.jsonObject["optimistic"])

        val ok = parse(AppState(settings = com.sbai.data.AppSettings(dnsOptimistic = true)))
        assertEquals(true, ok["dns"]!!.jsonObject["optimistic"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `optimistic dns object form carries timeout`() {
        val cfg = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    dnsOptimistic = true,
                    dnsOptimisticTimeout = "6h",
                ),
            ),
        )
        val optimistic = cfg["dns"]!!.jsonObject["optimistic"]!!.jsonObject
        assertEquals(true, optimistic["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("6h", optimistic["timeout"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dns reverse mapping and query timeout emitted when set`() {
        val cfg = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    dnsReverseMapping = true,
                    dnsQueryTimeout = "5s",
                ),
            ),
        )
        val dns = cfg["dns"]!!.jsonObject
        assertEquals(true, dns["reverse_mapping"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("5s", dns["timeout"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resolve route rule only when strategy configured`() {
        val off = parse(AppState())
        assertTrue(routeRules(off).none { it.jsonObject["action"]?.jsonPrimitive?.content == "resolve" })

        val on = parse(
            AppState(settings = com.sbai.data.AppSettings(dnsResolveStrategy = "prefer_ipv4")),
        )
        val resolve = routeRules(on).first { it.jsonObject["action"]?.jsonPrimitive?.content == "resolve" }.jsonObject
        assertEquals("prefer_ipv4", resolve["strategy"]!!.jsonPrimitive.content)
        assertTrue(resolve["inbound"]!!.jsonArray.any { it.jsonPrimitive.content == "tun-in" })
    }

    @Test
    fun `tun platform http proxy emitted only when enabled`() {
        val off = parse(AppState())
        assertNull(off["inbounds"]!!.jsonArray.first().jsonObject["platform"])

        val on = parse(
            AppState(
                settings = com.sbai.data.AppSettings(
                    tunHttpProxyEnabled = true,
                    tunHttpProxyPort = 7890,
                    tunHttpProxyBypassDomains = listOf("*.lan"),
                ),
            ),
        )
        val proxy = on["inbounds"]!!.jsonArray.first().jsonObject["platform"]!!.jsonObject["http_proxy"]!!.jsonObject
        assertEquals(true, proxy["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("127.0.0.1", proxy["server"]!!.jsonPrimitive.content)
        assertEquals(7890, proxy["server_port"]!!.jsonPrimitive.content.toInt())
        assertTrue(
            proxy["bypass_domain"]!!.jsonArray.any { it.jsonPrimitive.content == "*.lan" },
        )
    }

    @Test
    fun `tun inbound has no removed legacy sniff fields`() {
        val cfg = parse(AppState())
        val tun = cfg["inbounds"]!!.jsonArray.first().jsonObject
        // sing-box 1.13 起 inbound.sniff / sniff_override_destination 已移除，带上会被内核拒绝
        assertNull(tun["sniff"])
        assertNull(tun["sniff_override_destination"])
        // 嗅探改由 route 规则承担，必须存在
        assertTrue(routeRules(cfg).any { it.jsonObject["action"]?.jsonPrimitive?.content == "sniff" })
    }

    @Test
    fun `multi_queue only emitted for tsbai and go stacks`() {
        val tsbai = parse(
            AppState(settings = com.sbai.data.AppSettings(tunStack = com.sbai.data.TunStack.TSB_AI, tunMultiQueue = true)),
        )
        assertEquals(
            true,
            tsbai["inbounds"]!!.jsonArray.first().jsonObject["multi_queue"]!!.jsonPrimitive.content.toBoolean(),
        )
        val gvisor = parse(
            AppState(settings = com.sbai.data.AppSettings(tunStack = com.sbai.data.TunStack.GVISOR, tunMultiQueue = true)),
        )
        assertNull(
            "gvisor 栈不支持 multi_queue，不得输出",
            gvisor["inbounds"]!!.jsonArray.first().jsonObject["multi_queue"],
        )
    }

    @Test
    fun `fakeip custom ranges and auto rules`() {
        val state = AppState(
            dnsServers = listOf(
                DnsServer(tag = "fake", type = DnsServerType.FAKEIP, inet4Range = "198.20.0.0/16", inet6Range = "fc01::/17"),
            ),
        )
        val cfg = parse(state)
        val fake = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject
        assertEquals("198.20.0.0/16", fake["inet4_range"]!!.jsonPrimitive.content)
        assertEquals("fc01::/17", fake["inet6_range"]!!.jsonPrimitive.content)
        // fakeIP 联动 DNS 规则：query_type A/AAAA → fake
        val dnsRules = cfg["dns"]!!.jsonObject["rules"]!!.jsonArray
        assertTrue(dnsRules.any {
            it.jsonObject["server"]?.jsonPrimitive?.content == "fake" &&
                it.jsonObject["query_type"]?.jsonArray?.size == 2
        })
        // fakeIP 联动路由规则：fakeIP 段 → 代理
        val routeRules = cfg["route"]!!.jsonObject["rules"]!!.jsonArray
        val fakeRoute = routeRules.firstOrNull {
            it.jsonObject["ip_cidr"] != null
        }?.jsonObject
        assertTrue(fakeRoute != null)
        assertTrue(fakeRoute!!["ip_cidr"]!!.jsonArray.any { it.jsonPrimitive.content == "198.20.0.0/16" })
    }

    @Test
    fun `full action set emits correct sing-box action`() {
        fun ruleAction(a: RuleAction): JsonObject {
            val r = RouteRule(action = a, domains = listOf("a.com"))
            return parse(AppState(routeRules = listOf(r)))["route"]!!.jsonObject["rules"]!!.jsonArray.last().jsonObject
        }
        assertEquals("sniff", ruleAction(RuleAction.SNIFF)["action"]!!.jsonPrimitive.content)
        assertEquals("resolve", ruleAction(RuleAction.RESOLVE)["action"]!!.jsonPrimitive.content)
        assertEquals("hijack-dns", ruleAction(RuleAction.HIJACK_DNS)["action"]!!.jsonPrimitive.content)
        // 空 route-options 会被内核拒绝，生成器直接跳过该规则；填了字段才生成
        val emptyOpts = parse(
            AppState(routeRules = listOf(RouteRule(action = RuleAction.ROUTE_OPTIONS, domains = listOf("a.com")))),
        )["route"]!!.jsonObject["rules"]!!.jsonArray
        assertTrue(
            "未填任何字段的 route-options 不得生成",
            emptyOpts.none { it.jsonObject["action"]?.jsonPrimitive?.content == "route-options" },
        )
        val withFields = parse(
            AppState(
                routeRules = listOf(
                    RouteRule(
                        action = RuleAction.ROUTE_OPTIONS,
                        domains = listOf("a.com"),
                        tlsFragment = true,
                    ),
                ),
            ),
        )["route"]!!.jsonObject["rules"]!!.jsonArray.last().jsonObject
        assertEquals("route-options", withFields["action"]!!.jsonPrimitive.content)
        assertEquals(true, withFields["tls_fragment"]!!.jsonPrimitive.content.toBoolean())
        // route 动作：outbound 而非 action
        assertEquals("direct", ruleAction(RuleAction.ROUTE_DIRECT)["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lxbox rule set inline with package_name_regex and reject parses`() {
        // 用户反馈的 lxbox 格式：route 包裹 + inline rule_set + package_name_regex + action=reject
        val text = """{
          "route": {
            "rule_set": [{"tag":"unknown","type":"inline","rules":[{"invert":true,"package_name_regex":"^"}]}],
            "rules": [{"rule_set":"unknown","action":"reject"}]
          }
        }"""
        val r = RouteRuleJsonCodec.fromJson(text)
        assertTrue(r is RouteRuleJsonCodec.ParseResult.Success)
        val rule = (r as RouteRuleJsonCodec.ParseResult.Success).rule
        assertEquals(RuleAction.REJECT, rule.action)
        assertEquals(listOf("unknown"), rule.ruleSetTags)
    }

    // ------------------------------------------------------------------
    // DPI 硬化：TLS 分片（参考 LxBox 016）
    // ------------------------------------------------------------------

    @Test
    fun `tls fragment off leaves outbound tls untouched`() {
        val node = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x","tls":{"enabled":true,"server_name":"a.com"}}""",
        )
        val state = AppState(proxyNodes = listOf(node))
        val cfg = parse(state)
        val ob = outbounds(cfg).first { it.jsonObject["tag"]?.jsonPrimitive?.content == "n1" }.jsonObject
        val tls = ob["tls"]!!.jsonObject
        assertNull(tls["fragment"])
        assertNull(tls["record_fragment"])
    }

    @Test
    fun `tls record fragment on injects record_fragment only`() {
        val node = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x","tls":{"enabled":true,"server_name":"a.com"}}""",
        )
        val state = AppState(
            proxyNodes = listOf(node),
            settings = com.sbai.data.AppSettings(tlsRecordFragment = true),
        )
        val cfg = parse(state)
        val ob = outbounds(cfg).first { it.jsonObject["tag"]?.jsonPrimitive?.content == "n1" }.jsonObject
        val tls = ob["tls"]!!.jsonObject
        assertEquals("true", tls["record_fragment"]!!.jsonPrimitive.content)
        assertNull(tls["fragment"])
    }

    @Test
    fun `tls fragment on injects fragment and fallback delay`() {
        val node = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x","tls":{"enabled":true,"server_name":"a.com"}}""",
        )
        val state = AppState(
            proxyNodes = listOf(node),
            settings = com.sbai.data.AppSettings(tlsFragment = true, tlsFragmentFallbackDelay = "700ms"),
        )
        val cfg = parse(state)
        val ob = outbounds(cfg).first { it.jsonObject["tag"]?.jsonPrimitive?.content == "n1" }.jsonObject
        val tls = ob["tls"]!!.jsonObject
        assertEquals("true", tls["fragment"]!!.jsonPrimitive.content)
        assertEquals("700ms", tls["fragment_fallback_delay"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tls fragment skipped when utls present`() {
        // uTLS 已改造 ClientHello，fragment 会被内核拒绝，应跳过 fragment（record_fragment 仍注入）
        val node = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x","tls":{"enabled":true,"server_name":"a.com","utls":{"enabled":true,"fingerprint":"chrome"}}}""",
        )
        val state = AppState(
            proxyNodes = listOf(node),
            settings = com.sbai.data.AppSettings(tlsFragment = true, tlsRecordFragment = true),
        )
        val cfg = parse(state)
        val ob = outbounds(cfg).first { it.jsonObject["tag"]?.jsonPrimitive?.content == "n1" }.jsonObject
        val tls = ob["tls"]!!.jsonObject
        assertNull(tls["fragment"])
        assertEquals("true", tls["record_fragment"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tls fragment not injected on non-tls protocol`() {
        // shadowsocks 无 tls 块，不注入任何分片字段
        val node = ProxyNode(
            name = "ss1",
            outboundJson = """{"type":"shadowsocks","tag":"ss1","server":"1.2.3.4","server_port":8388,"method":"aes-128-gcm","password":"p"}""",
        )
        val state = AppState(
            proxyNodes = listOf(node),
            settings = com.sbai.data.AppSettings(tlsFragment = true, tlsRecordFragment = true),
        )
        val cfg = parse(state)
        val ob = outbounds(cfg).first { it.jsonObject["tag"]?.jsonPrimitive?.content == "ss1" }.jsonObject
        assertNull(ob["tls"])
    }

    @Test
    fun `tls fragment not injected on detour node`() {
        // detour 节点：分片交由内核决定，不注入
        val node = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x","detour":"relay","tls":{"enabled":true,"server_name":"a.com"}}""",
        )
        val state = AppState(
            proxyNodes = listOf(node),
            settings = com.sbai.data.AppSettings(tlsFragment = true),
        )
        val cfg = parse(state)
        val ob = outbounds(cfg).first { it.jsonObject["tag"]?.jsonPrimitive?.content == "n1" }.jsonObject
        assertNull(ob["tls"]!!.jsonObject["fragment"])
    }

    // ------------------------------------------------------------------
    // Tailscale endpoint（参考 LxBox 030）
    // ------------------------------------------------------------------

    @Test
    fun `tailscale node emitted into endpoints not outbounds`() {
        val ts = ProxyNode(
            name = "ts1",
            outboundJson = """{"type":"tailscale","tag":"ts1","auth_key":"tskey-auth-xxx","hostname":"phone"}""",
        )
        val state = AppState(proxyNodes = listOf(ts))
        val cfg = parse(state)
        // 应出现在 endpoints 数组，而非 outbounds 数组
        val eps = cfg["endpoints"]!!.jsonArray
        val tsEp = eps.first { it.jsonObject["tag"]?.jsonPrimitive?.content == "ts1" }.jsonObject
        assertEquals("tailscale", tsEp["type"]!!.jsonPrimitive.content)
        assertEquals("tskey-auth-xxx", tsEp["auth_key"]!!.jsonPrimitive.content)
        // outbounds 里不应有 tailscale 节点（只有 direct/block 兜底）
        val obTags = outbounds(cfg).map { it.jsonObject["tag"]?.jsonPrimitive?.content }
        assertTrue("ts1" !in obTags)
    }

    @Test
    fun `tailscale node can be referenced by proxy group`() {
        // tailscale endpoint 的 tag 应能被 proxy selector 组引用（endpoint tag 与 outbound tag 同等）
        val ts = ProxyNode(
            name = "ts1",
            outboundJson = """{"type":"tailscale","tag":"ts1","exit_node":"nas"}""",
        )
        val vless = ProxyNode(
            name = "n1",
            outboundJson = """{"type":"vless","tag":"n1","server":"1.2.3.4","server_port":443,"uuid":"x"}""",
        )
        val state = AppState(proxyNodes = listOf(ts, vless))
        val cfg = parse(state)
        // 多节点 → proxy selector 组，outbounds 应引用 ts1 和 n1
        val proxy = outbounds(cfg).first { it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy" }.jsonObject
        val refs = proxy["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("ts1" in refs)
        assertTrue("n1" in refs)
    }
}
