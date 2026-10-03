package com.sbai

import com.sbai.service.ShareLinkParser
import kotlinx.serialization.json.Json
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
    fun `unsupported protocol returns null`() {
        assertNull(ShareLinkParser.parse("ssr://something"))
        assertNull(ShareLinkParser.parse("http://example.com"))
    }
}
