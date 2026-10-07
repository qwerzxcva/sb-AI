package com.sbai

import com.sbai.data.AppState
import com.sbai.data.AppSettings
import com.sbai.data.ProxyNode
import com.sbai.data.RouteRule
import com.sbai.data.RuleAction
import com.sbai.service.ShareLinkParser
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.service.UtlsFingerprintNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实订阅端到端回归 — "链接解析 → 配置生成 → 内核 schema 合规"。
 *
 * 覆盖点：
 * 1. xray/Clash uTLS 别名（hellochrome_120 / chameleon）→ normalize 为 sing-box 规范枚举
 * 2. dns.strategy 顶层字段不出现（sing-box 1.14 FATAL 根因）
 * 3. wireguard 节点进 endpoints[] 而非 outbounds[]（sing-box 1.12+ 要求）
 * 4. 所有 utls fingerprint 落在内核认可的 canonical 集合内
 * 5. 混合订阅（vless/trojan/hysteria2/wireguard/ss）全部能生成合法 JSON
 * 6. base64 整段订阅 + 明文逐行订阅两种格式都解析成功
 *
 * 注：JUnit 断言参数顺序为 (message, condition)，与 kotlin.test 相反。
 */
class RealSubscriptionPipelineTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // 真实订阅常见混合节点（含 xray 别名 uTLS 指纹 + wireguard endpoint）
    private val MIXED_PLAINTEXT = """
        # test notes
        vless://abc123@1.1.1.1:443?security=tls&fp=hellochrome_120&sni=example.com#mixed-vless
        trojan://pass@2.2.2.2:443?fp=chameleon&sn=s.example.com#mixed-trojan
        ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNS1wYXNzd29yZA==@3.3.3.3:8386#mixed-ss
        hysteria2://user:pass@4.4.4.4:443?sni=hysteria.example.com#mixed-hy2
        wireguard://dXNlcjpzZWNyZXQ=@5.5.5.5:51820?publickey=xyz&address=10.0.0.2,fd00::2&allowedips=0.0.0.0/0,::/0#mixed-wg
    """.trimIndent()

    @Test
    fun `parse mixed subscription and generate config - schema checks`() {
        // 1) 解析
        val parsed = ShareLinkParser.parseSubscription(MIXED_PLAINTEXT)
        assertTrue("应解析出至少 5 个节点，实际: ${parsed.size} (${parsed.map { it.name }})", parsed.size >= 5)

        // 2) 转为 ProxyNode
        val nodes = parsed.map { n -> ProxyNode(name = n.name, outboundJson = n.outboundJson) }

        // 3) 生成配置
        val state = AppState(
            proxyNodes = nodes,
            routeRules = listOf(
                RouteRule(action = RuleAction.ROUTE_DIRECT, domains = listOf("example.com")),
            ),
            settings = AppSettings(dnsStrategy = "prefer_ipv4"),
        )
        val cfgStr = SingBoxConfigGenerator.generate(state)
        assertFalse("配置生成结果不应为空", cfgStr.isBlank())

        // 4) dns 顶层不含 strategy（P0 修复核心断言）
        val cfg = json.parseToJsonElement(cfgStr).jsonObject
        val dns = cfg["dns"]?.jsonObject ?: error("dns 块缺失")
        assertFalse("dns 顶层不应含 strategy 字段（sing-box 1.14 FATAL 根因）", dns.containsKey("strategy"))
        val servers = dns["servers"]?.jsonArray ?: emptyList()
        assertTrue("dns.servers 应非空", servers.isNotEmpty())

        // 5) wireguard 节点进 endpoints[] 而非 outbounds[]
        val endpoints = cfg["endpoints"]?.jsonArray ?: emptyList()
        val outbounds = cfg["outbounds"]?.jsonArray ?: emptyList()
        val wgInEndpoints = endpoints.any { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "wireguard" }
        val wgInOutbounds = outbounds.any { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "wireguard" }
        assertTrue("wireguard 节点应出现在 endpoints[]", wgInEndpoints)
        assertFalse("wireguard 节点不应出现在 outbounds[]", wgInOutbounds)

        // 6) 所有 utls fingerprint 必须落在内核认可的 canonical 集合（normalize 兜底保证）
        val allowedFps = setOf("chrome", "firefox", "safari", "edge", "ios", "qq", "android", "random", "none")
        (outbounds + endpoints).forEach { b ->
            val tls = b.jsonObject["tls"]?.jsonObject
            tls?.let { t ->
                val fp = t["utls"]?.jsonObject?.get("fingerprint")?.jsonPrimitive?.contentOrNull
                if (fp != null) {
                    assertTrue("fingerprint '$fp' 不在内核认可的 canonical 集合内（$allowedFps）", fp in allowedFps)
                }
            }
        }

        // 7) 所有节点 tag 必须出现在 outbounds/endpoints 中
        val allTags = (outbounds + endpoints).map { it.jsonObject["tag"]?.jsonPrimitive?.contentOrNull }.toSet()
        nodes.forEach { n ->
            val tag = Json.parseToJsonElement(n.outboundJson).jsonObject["tag"]?.jsonPrimitive?.contentOrNull
            assertTrue("节点 ${n.name} 的 outboundJson 应含 tag", tag != null)
            assertTrue("tag '$tag' 应在 outbounds/endpoints 中出现", tag!! in allTags)
        }
    }

    @Test
    fun `utls normalization covers xray alias variants used in real subscriptions`() {
        val cases = mapOf(
            "hellochrome_120" to "chrome",
            "hellochrome_118" to "chrome",
            "HelloChrome_101" to "chrome",
            "hellofirefox_117" to "firefox",
            "hellofirefox" to "firefox",
            "chameleon" to "chrome",       // 未知别名 → chrome 兜底
            "chrome" to "chrome",
            "firefox" to "firefox",
            "safari" to "safari",
            "random" to "random",
            "invalid_fp_xxx" to "chrome",  // 未知 → chrome 兜底（保证内核不 FATAL）
            "" to "chrome",               // 空 → chrome 兜底
        )
        cases.forEach { (raw, expected) ->
            assertEquals("normalize($raw)", expected, UtlsFingerprintNormalizer.normalize(raw))
        }
    }

    /** 混合订阅的 base64 整段解码也能正常工作。 */
    @Test
    fun `base64 encoded mixed subscription round-trip`() {
        val plain = "vless://abc@1.2.3.4:443?fp=hellochrome_120&security=tls#b64vless\ntrojan://p@5.5.5.5:443?fp=chameleon#b64trojan"
        val b64 = java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
        val parsed = ShareLinkParser.parseSubscription(b64)
        assertTrue("base64 订阅至少解析出 2 个节点，实际: ${parsed.size}", parsed.size >= 2)
    }
}
