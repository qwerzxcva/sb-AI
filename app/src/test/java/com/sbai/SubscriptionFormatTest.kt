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
}
