package com.sbai

import com.sbai.data.RuleAction
import com.sbai.service.SubscriptionFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFormatTest {

    @Test
    fun `clash yaml with proxies groups rules parses`() {
        val yaml = """
proxies:
  - name: "节点A"
    type: ss
    server: server-a.com
    port: 8388
    cipher: aes-128-gcm
    password: "pass123"
  - name: "节点B"
    type: vless
    server: server-b.com
    port: 443
    uuid: "abc-123"
    network: ws
    tls: true
    client-fingerprint: chrome
    servername: server-b.com
    ws-opts:
      path: /ray
      headers:
        Host: cdn.example.com
proxy-groups:
  - name: "自动选择"
    type: url-test
    proxies: ["节点A", "节点B"]
    url: "https://www.gstatic.com/generate_204"
    interval: 300
rules:
  - "DOMAIN,example.com,DIRECT"
  - "DOMAIN-SUFFIX,ads.com,REJECT"
  - "IP-CIDR,10.0.0.0/8,DIRECT"
  - "MATCH,自动选择"
""".trimIndent()
        val result = SubscriptionFormat.parse(yaml)
        assertNotNull(result)
        result!!
        assertEquals("clash_yaml", result.format)
        assertEquals(2, result.nodes.size)

        // 节点 A
        val nodeA = result.nodes.find { it.name == "节点A" }!!
        assertTrue(nodeA.outboundJson.contains("\"shadowsocks\""))
        assertTrue(nodeA.outboundJson.contains("\"aes-128-gcm\""))

        // 节点 B：vless + ws + tls + utls
        val nodeB = result.nodes.find { it.name == "节点B" }!!
        assertTrue(nodeB.outboundJson.contains("\"vless\""))
        assertTrue(nodeB.outboundJson.contains("\"ws\""))
        assertTrue(nodeB.outboundJson.contains("\"utls\""))
        assertTrue(nodeB.outboundJson.contains("\"chrome\""))

        // 规则
        assertEquals(3, result.routeRules.size)
        assertEquals(RuleAction.ROUTE_DIRECT, result.routeRules[0].action)
        assertEquals(listOf("example.com"), result.routeRules[0].domains)
        assertEquals(RuleAction.REJECT, result.routeRules[1].action)
        assertEquals(listOf("ads.com"), result.routeRules[1].domainSuffixes)
        assertEquals(listOf("10.0.0.0/8"), result.routeRules[2].ipCidrs)
    }

    @Test
    fun `sing-box json config extracts outbounds and rules`() {
        val json = """
{
  "log": {"level": "warn"},
  "outbounds": [
    {"type": "vless", "tag": "n1", "server": "1.2.3.4", "server_port": 443, "uuid": "x"},
    {"type": "trojan", "tag": "n2", "server": "5.6.7.8", "server_port": 443, "password": "p"},
    {"type": "direct", "tag": "direct"},
    {"type": "urltest", "tag": "auto", "outbounds": ["n1"]}
  ],
  "route": {
    "rules": [
      {"domain_suffix": ["ads.com"], "action": "reject"}
    ]
  }
}
""".trimIndent()
        val result = SubscriptionFormat.parse(json)
        assertNotNull(result)
        result!!
        assertEquals("json_config", result.format)
        // 跳过 direct/urltest 系统 outbound
        assertEquals(2, result.nodes.size)
        assertEquals(setOf("n1", "n2"), result.nodes.map { it.name }.toSet())
        // 提取 route.rules
        assertEquals(1, result.routeRules.size)
        assertEquals(RuleAction.REJECT, result.routeRules[0].action)
    }

    @Test
    fun `share links still work`() {
        val links = "vless://u@1.1.1.1:443?security=tls#n1\ntrojan://p@2.2.2.2:443#n2"
        val result = SubscriptionFormat.parse(links)
        assertNotNull(result)
        result!!
        assertEquals("share_links", result.format)
        assertEquals(2, result.nodes.size)
        assertEquals(0, result.routeRules.size)
    }

    @Test
    fun `invalid content returns null`() {
        assertEquals(null, SubscriptionFormat.parse("<html><body>404</body></html>"))
        assertEquals(null, SubscriptionFormat.parse(""))
        assertEquals(null, SubscriptionFormat.parse("random garbage text"))
    }

    @Test
    fun `hysteria2 share links parse correctly`() {
        val hy2Link = "hysteria2://e42ef740-7d13-47de-9174-708692245c08@1002us.debian13.com:18445?insecure=0&sni=releases.ubuntu26.com#节点1"
        val result = SubscriptionFormat.parse(hy2Link)
        assertNotNull(result)
        result!!
        assertEquals("share_links", result.format)
        assertEquals(1, result.nodes.size)
        assertTrue(result.nodes[0].outboundJson.contains("\"hysteria2\""))
        assertTrue(result.nodes[0].outboundJson.contains("\"1002us.debian13.com\""))
        // server_port 是整数，JSON 序列化后不带引号
        assertTrue(result.nodes[0].outboundJson.contains("18445"))
    }

    @Test
    fun `clash hysteria2 ports range maps to server_ports`() {
        val yaml = """
proxies:
  - name: "香港01"
    type: hysteria2
    server: 209.9.200.33
    port: 20000
    ports: 20000-50000
    password: "pass"
    sni: d1.awsstatic.com
    skip-cert-verify: true
""".trimIndent()
        val result = SubscriptionFormat.parse(yaml)
        assertNotNull(result)
        result!!
        assertEquals(1, result.nodes.size)
        val json = result.nodes[0].outboundJson
        // server_ports 应包含 "20000:50000"（hyphen → colon）
        assertTrue(json.contains("server_ports"))
        assertTrue(json.contains("20000:50000"))
    }

    @Test
    fun `clash hysteria2 mport multi ranges maps to server_ports`() {
        val yaml = """
proxies:
  - name: "香港02"
    type: hysteria2
    server: 209.9.200.33
    port: 20000
    mport: 20000-30000,40000
    password: "pass"
    sni: bing.com
""".trimIndent()
        val result = SubscriptionFormat.parse(yaml)
        assertNotNull(result)
        result!!
        val json = result.nodes[0].outboundJson
        // 范围 + 单端口：20000:30000 和 40000:40000
        assertTrue(json.contains("20000:30000"))
        assertTrue(json.contains("40000:40000"))
    }

    @Test
    fun `clash hysteria2 without ports has no server_ports`() {
        val yaml = """
proxies:
  - name: "香港03"
    type: hysteria2
    server: hk01.poke-mon.xyz
    port: 8443
    password: "pass"
    sni: www.bing.com
""".trimIndent()
        val result = SubscriptionFormat.parse(yaml)
        assertNotNull(result)
        result!!
        val json = result.nodes[0].outboundJson
        assertTrue(!json.contains("server_ports"))
    }
}
