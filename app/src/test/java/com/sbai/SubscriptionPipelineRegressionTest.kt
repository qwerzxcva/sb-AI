package com.sbai

import com.sbai.service.ShareLinkParser
import com.sbai.service.SubscriptionFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SubscriptionPipelineRegressionTest {
    private val link = "trojan://test-password@node.example:443#HK"
    private val yaml = """
        proxies:
          - name: HK
            type: trojan
            server: node.example
            port: 443
            password: test-password
    """.trimIndent()
    private val config = """{"outbounds":[{"type":"trojan","tag":"HK","server":"node.example","server_port":443,"password":"test-password"}]}"""

    @Test fun `url safe alphabet cannot be silently discarded by MIME decoder`() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xff.toByte())
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertTrue(encoded.contains('-') && encoded.contains('_'))
        assertArrayEquals(bytes, ShareLinkParser.decodeB64(encoded))
        assertNull(ShareLinkParser.decodeB64("dGVzdA!!!"))
    }

    @Test fun `base64 accepts whitespace missing padding and BOM`() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(link.toByteArray())
        val wrapped = "\uFEFF" + encoded.chunked(13).joinToString("\r\n")
        assertEquals("HK", SubscriptionFormat.parse(wrapped)!!.nodes.single().name)
        assertEquals("HK", SubscriptionFormat.parse("\uFEFF$link")!!.nodes.single().name)
    }

    @Test fun `wrapped yaml and json are detected after decoding`() {
        for ((content, format) in listOf(yaml to "clash_yaml", config to "json_config")) {
            val encoded = Base64.getEncoder().withoutPadding().encodeToString(("\uFEFF" + content).toByteArray())
            val result = SubscriptionFormat.parse(encoded)!!
            assertEquals(format, result.format)
            assertEquals(1, result.nodes.size)
        }
    }

    @Test fun `indented BOM clash and clash JSON are detected`() {
        assertEquals(1, SubscriptionFormat.parse("\uFEFF\n" + yaml.prependIndent("  "))!!.nodes.size)
        val clashJson = """{"proxies":[{"type":"trojan","name":"HK","server":"node.example","port":443,"password":"test-password"}]}"""
        assertEquals("clash_yaml", SubscriptionFormat.parse(clashJson)!!.format)
    }

    @Test fun `clash trojan defaults to TLS and is not incorrectly insecure`() {
        val node = SubscriptionFormat.parse(yaml)!!.nodes.single()
        val tls = Json.parseToJsonElement(node.outboundJson).jsonObject["tls"]!!.jsonObject
        assertEquals("true", tls["enabled"]!!.jsonPrimitive.content)
    }

    @Test fun `malformed nodes do not poison valid siblings`() {
        val mixed = """{"outbounds":[
            {"type":{},"tag":"bad"},
            {"type":"vless","tag":"no-server","server_port":443,"uuid":"test-id"},
            {"type":"trojan","tag":"bad-port","server":"node.example","server_port":70000,"password":"p"},
            {"type":"trojan","tag":"valid","server":"node.example","server_port":443,"password":"p"},
            {"protocol":"vmess","tag":"xray-not-converted","settings":{}},
            {"type":"direct","tag":"direct"}
        ]}"""
        val result = SubscriptionFormat.parse(mixed)!!
        assertEquals(listOf("valid"), result.nodes.map { it.name })
        assertEquals(2, result.rejectedNodeCount)
    }

    @Test fun `invalid result has no nodes eligible for replacing existing state`() {
        val bad = config.replace("443", "0")
        val result = SubscriptionFormat.parse(bad)!!
        assertTrue(result.nodes.isEmpty())
        assertEquals(1, result.rejectedNodeCount)
        assertNull(SubscriptionFormat.parse("<html>login required</html>"))
    }

    @Test fun `protocol filter matches type not password name or server`() {
        val outbound = """{"type":"vless","tag":"ss","password":"trojan","server":"vmess.example"}"""
        assertFalse(SubscriptionFormat.matchesProtocol(outbound, "ss"))
        assertFalse(SubscriptionFormat.matchesProtocol(outbound, "trojan"))
        assertTrue(SubscriptionFormat.matchesProtocol(outbound, "SS, VLESS"))
        assertTrue(SubscriptionFormat.matchesProtocol("""{"type":"hysteria2"}""", "hy2"))
    }

    @Test fun `legitimate traffic and recommendation names survive info filtering`() {
        assertFalse(ShareLinkParser.isInfoNode("香港 建议使用 01"))
        assertFalse(ShareLinkParser.isInfoNode("香港 大流量 01"))
        assertTrue(ShareLinkParser.isInfoNode("剩余流量 50GB"))
        assertTrue(ShareLinkParser.isInfoNode("到期 2027-01-01"))
    }

    @Test fun `singbox wireguard endpoints are extracted`() {
        val endpoint = """{"endpoints":[{"type":"wireguard","tag":"wg","private_key":"test-private","address":["10.0.0.2/32"],"peers":[{"address":"node.example","port":51820,"public_key":"test-public"}]}]}"""
        assertEquals("wg", SubscriptionFormat.parse(endpoint)!!.nodes.single().name)
    }

    @Test fun `literal plus in URI credentials is preserved`() {
        val node = ShareLinkParser.parse("trojan://a+b@node.example:443#test")!!
        assertEquals("a+b", Json.parseToJsonElement(node.outboundJson).jsonObject["password"]!!.jsonPrimitive.content)
    }
}
