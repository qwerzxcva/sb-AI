package com.sbai

import com.sbai.service.ShareLinkParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ShareLinkParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(url: String) = ShareLinkParser.parse(url).let {
        assertNotNull("解析失败: $url", it)
        it!!
    }

    private fun outboundOf(url: String) = json.parseToJsonElement(parse(url).outboundJson).jsonObject

    @Test
    fun `vless reality`() {
        val o = outboundOf("vless://uuid-123@1.2.3.4:443?security=reality&sni=example.com&fp=chrome&pbk=PUBKEY&sid=ab&flow=xtls-rprx-vision#test-node")
        assertEquals("vless", o["type"]!!.jsonPrimitive.content)
        assertEquals("test-node", o["tag"]!!.jsonPrimitive.content)
        assertEquals("uuid-123", o["uuid"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", o["flow"]!!.jsonPrimitive.content)
        assertEquals("example.com", o["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
        assertEquals("PUBKEY", o["tls"]!!.jsonObject["reality"]!!.jsonObject["public_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless ws + tls`() {
        val o = outboundOf("vless://uuid@example.com:443?security=tls&type=ws&path=%2Fws&host=cdn.example.com&sni=example.com#wsnode")
        assertEquals("ws", o["transport"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("/ws", o["transport"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com", o["transport"]!!.jsonObject["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        assertEquals("true", o["tls"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trojan basic`() {
        val o = outboundOf("trojan://p%40ss@trojan.example.com:443?sni=t.example.com#trojan-node")
        assertEquals("trojan", o["type"]!!.jsonPrimitive.content)
        assertEquals("p@ss", o["password"]!!.jsonPrimitive.content)
        assertEquals("t.example.com", o["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ss sip002 encoded userinfo`() {
        val userB64 = Base64.getEncoder().encodeToString("aes-256-gcm:passw0rd".toByteArray())
        val o = outboundOf("ss://$userB64@1.2.3.4:8388#ssnode")
        assertEquals("shadowsocks", o["type"]!!.jsonPrimitive.content)
        assertEquals("aes-256-gcm", o["method"]!!.jsonPrimitive.content)
        assertEquals("passw0rd", o["password"]!!.jsonPrimitive.content)
        assertEquals("8388", o["server_port"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ss full base64 legacy`() {
        val full = Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:pw@2.3.4.5:1080".toByteArray())
        val o = outboundOf("ss://$full")
        assertEquals("chacha20-ietf-poly1305", o["method"]!!.jsonPrimitive.content)
        assertEquals("pw", o["password"]!!.jsonPrimitive.content)
        assertEquals("2.3.4.5", o["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vmess base64 json`() {
        val payload = Base64.getEncoder().encodeToString(
            """{"ps":"vmess-node","add":"vm.example.com","port":"443","id":"uuid-x","aid":"0","net":"ws","path":"/ray","host":"cdn.x.com","tls":"tls","sni":"vm.example.com","scy":"auto"}""".toByteArray(),
        )
        val o = outboundOf("vmess://$payload")
        assertEquals("vmess", o["type"]!!.jsonPrimitive.content)
        assertEquals("vmess-node", o["tag"]!!.jsonPrimitive.content)
        assertEquals("uuid-x", o["uuid"]!!.jsonPrimitive.content)
        assertEquals("ws", o["transport"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("/ray", o["transport"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("true", o["tls"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hysteria2`() {
        val o = outboundOf("hysteria2://passw0rd@hy2.example.com:443?sni=hy.example.com&insecure=1#hy2-node")
        assertEquals("hysteria2", o["type"]!!.jsonPrimitive.content)
        assertEquals("passw0rd", o["password"]!!.jsonPrimitive.content)
        assertEquals("hy.example.com", o["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
        assertEquals("true", o["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.content)
    }

    @Test
    fun `subscription content base64 wrapped`() {
        val links = listOf(
            "vless://u1@1.1.1.1:443?security=tls#n1",
            "trojan://pw@2.2.2.2:443#n2",
            "ss://" + Base64.getEncoder().encodeToString("aes-128-gcm:pw@3.3.3.3:8388".toByteArray()) + "#n3",
        ).joinToString("\n")
        val wrapped = Base64.getEncoder().encodeToString(links.toByteArray())
        val nodes = ShareLinkParser.parseSubscription(wrapped)
        assertEquals(3, nodes.size)
        assertEquals(listOf("n1", "n2", "n3"), nodes.map { it.name })
    }

    @Test
    fun `subscription content plain lines`() {
        val links = "vless://u@1.1.1.1:443#a\ntrojan://p@2.2.2.2:443#b\nnot-a-link"
        val nodes = ShareLinkParser.parseSubscription(links)
        assertEquals(2, nodes.size)
    }

    @Test
    fun `password containing at sign is not truncated`() {
        val o = outboundOf("trojan://p%40ss%40word@trojan.example.com:443?sni=t.example.com#n")
        assertEquals("p@ss@word", o["password"]!!.jsonPrimitive.content)
        assertEquals("trojan.example.com", o["server"]!!.jsonPrimitive.content)
        assertEquals("443", o["server_port"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hysteria2 basic`() {
        val o = outboundOf("hysteria2://e42ef740-7d13-47de-9174-708692245c08@1002us.debian13.com:18445?insecure=0&sni=releases.ubuntu26.com#美国")
        assertEquals("hysteria2", o["type"]!!.jsonPrimitive.content)
        assertEquals("1002us.debian13.com", o["server"]!!.jsonPrimitive.content)
        assertEquals("18445", o["server_port"]!!.jsonPrimitive.content)
        assertEquals("releases.ubuntu26.com", o["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless uuid containing at sign`() {
        val o = outboundOf("vless://ui%40d@1.2.3.4:443?security=tls#n")
        assertEquals("ui@d", o["uuid"]!!.jsonPrimitive.content)
        assertEquals("1.2.3.4", o["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid port is rejected not silently defaulted`() {
        assertNull(ShareLinkParser.parse("trojan://pw@host.example.com:99999#n"))
        assertNull(ShareLinkParser.parse("trojan://pw@host.example.com:0#n"))
        assertNull(ShareLinkParser.parse("trojan://pw@host.example.com:abc#n"))
    }

    @Test
    fun `ipv6 host literal`() {
        val o = outboundOf("trojan://pw@[2001:db8::1]:443?sni=t.example.com#n")
        assertEquals("2001:db8::1", o["server"]!!.jsonPrimitive.content)
        assertEquals("443", o["server_port"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unsupported protocol returns null`() {
        assertNull(ShareLinkParser.parse("ssr://something"))
        assertNull(ShareLinkParser.parse("http://example.com"))
    }

    @Test
    fun `wireguard uri parses to endpoint format`() {
        val o = outboundOf("wireguard://cHJpdmF0ZQ==@engage.cloudflareclient.com:2408?publickey=cGVlcg==&address=172.16.0.2/32&allowedips=0.0.0.0/0,::/0&keepalive=25&mtu=1280&reserved=1,2,3#WARP")
        assertEquals("wireguard", o["type"]!!.jsonPrimitive.content)
        assertEquals("WARP", o["tag"]!!.jsonPrimitive.content)
        assertEquals("cHJpdmF0ZQ==", o["private_key"]!!.jsonPrimitive.content)
        assertEquals("172.16.0.2/32", o["address"]!!.jsonArray[0].jsonPrimitive.content)

        val peer = o["peers"]!!.jsonArray[0].jsonObject
        assertEquals("engage.cloudflareclient.com", peer["address"]!!.jsonPrimitive.content)
        assertEquals("2408", peer["port"]!!.jsonPrimitive.content)
        assertEquals("cGVlcg==", peer["public_key"]!!.jsonPrimitive.content)
        assertEquals("0.0.0.0/0", peer["allowed_ips"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("25", peer["persistent_keepalive_interval"]!!.jsonPrimitive.content)
        assertEquals("1", peer["reserved"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `wireguard bare address normalized to cidr`() {
        val o = outboundOf("wireguard://k@host:51820?publickey=p&address=10.0.0.2,fd00::2#n")
        assertEquals("10.0.0.2/32", o["address"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("fd00::2/128", o["address"]!!.jsonArray[1].jsonPrimitive.content)
    }

    @Test
    fun `wireguard default allowed ips and mtu`() {
        val o = outboundOf("wireguard://k@host:51820?publickey=p&address=10.0.0.2/32#n")
        val peer = o["peers"]!!.jsonArray[0].jsonObject
        assertEquals("0.0.0.0/0", peer["allowed_ips"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("1408", o["mtu"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wireguard missing key rejected`() {
        assertNull(ShareLinkParser.parse("wireguard://host:51820?publickey=p&address=10.0.0.2/32#n"))
        assertNull(ShareLinkParser.parse("wireguard://k@host:51820?address=10.0.0.2/32#n"))
    }

    @Test
    fun `wireguard invalid reserved ignored`() {
        val o = outboundOf("wireguard://k@host:51820?publickey=p&address=10.0.0.2/32&reserved=999,1,2#n")
        val peer = o["peers"]!!.jsonArray[0].jsonObject
        assertNull(peer["reserved"])
    }

    // ------------------------------------------------------------------
    // isInfoNode：信息节点过滤（剩余流量/到期/官网公告伪装成节点）
    // ------------------------------------------------------------------

    @Test
    fun `info node strong keywords detected`() {
        // 强关键词：必是信息节点
        assertTrue(ShareLinkParser.isInfoNode("剩余流量：50.62 GB"))
        assertTrue(ShareLinkParser.isInfoNode("距离下次重置剩余：27 天"))
        assertTrue(ShareLinkParser.isInfoNode("套餐到期：2026-11-02"))
        assertTrue(ShareLinkParser.isInfoNode("建议：感到卡顿请切换到专线节点"))
        assertTrue(ShareLinkParser.isInfoNode("放丢失官网:https://love.p6m6.com"))
        assertTrue(ShareLinkParser.isInfoNode("放丢失官网2:https://love3.p6m6.com"))
    }

    @Test
    fun `info node weak keyword with digits detected`() {
        // 弱关键词「流量/到期」+ 数字量化
        assertTrue(ShareLinkParser.isInfoNode("流量：50GB"))
        assertTrue(ShareLinkParser.isInfoNode("到期时间 2026"))
    }

    @Test
    fun `real node names not misdetected`() {
        // 真实节点名不含信息关键词，不误伤
        assertTrue(!ShareLinkParser.isInfoNode("🇭🇰【亚洲】香港01丨直连"))
        assertTrue(!ShareLinkParser.isInfoNode("🇯🇵【亚洲】日本01丨Vless"))
        assertTrue(!ShareLinkParser.isInfoNode("🇺🇸【北美洲】美国01原生丨直连【2x】"))
        assertTrue(!ShareLinkParser.isInfoNode("🇭🇰【亚洲】香港01丨V6【1x】"))
        assertTrue(!ShareLinkParser.isInfoNode(""))
    }

    @Test
    fun `info node weak keyword without digits not misdetected`() {
        // 弱关键词「流量」但不含数字（如机场节点名叫"流量专线"）不误伤
        // 注意：「到期」是强关键词，无论含不含数字都会判为信息节点，故这里只测「流量」
        assertTrue(!ShareLinkParser.isInfoNode("流量专线"))
        assertTrue(!ShareLinkParser.isInfoNode("香港流量优化"))
    }
}
