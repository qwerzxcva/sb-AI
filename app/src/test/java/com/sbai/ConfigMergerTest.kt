package com.sbai

import com.sbai.data.OverridePriority
import com.sbai.service.ConfigMerger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigMergerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val ui = """
    {
      "log": { "level": "warn" },
      "dns": {
        "servers": [ { "type": "udp", "tag": "d1", "server": "223.5.5.5" } ],
        "rules": [ { "domain": ["ui.com"], "server": "d1" } ]
      },
      "route": { "rules": [ { "domain": ["ui.com"], "outbound": "proxy" } ], "final": "proxy" },
      "outbounds": [
        { "type": "vless", "tag": "ui-node", "server": "1.1.1.1" },
        { "type": "direct", "tag": "direct" }
      ]
    }
    """.trimIndent()

    private val imported = """
    {
      "log": { "level": "info" },
      "ntp": { "enabled": true },
      "dns": {
        "servers": [
          { "type": "udp", "tag": "d1", "server": "8.8.8.8" },
          { "type": "https", "tag": "imported-dns", "server": "9.9.9.9" }
        ],
        "rules": [ { "domain": ["imported.com"], "server": "imported-dns" } ]
      },
      "route": { "rules": [ { "domain": ["imported.com"], "outbound": "imported-node" } ], "final": "imported-node" },
      "outbounds": [
        { "type": "vless", "tag": "ui-node", "server": "9.9.9.9" },
        { "type": "trojan", "tag": "imported-node", "server": "2.2.2.2" }
      ]
    }
    """.trimIndent()

    private fun parse(s: String) = json.parseToJsonElement(s).jsonObject

    @Test
    fun `ui highest keeps ui scalars and only fills gaps`() {
        val merged = parse(ConfigMerger.merge(ui, imported, OverridePriority.UI_HIGHEST))

        // UI 标量胜出
        assertEquals("warn", merged["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertEquals("proxy", merged["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)

        // 导入独有的顶层 key 被补充
        assertEquals("true", merged["ntp"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)

        // 数组按 tag 合并：UI 的 d1 保留（223.5.5.5），导入独有的 imported-dns 追加
        val servers = merged["dns"]!!.jsonObject["servers"]!!.jsonArray
        val tags = servers.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(listOf("d1", "imported-dns"), tags)
        val d1 = servers.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "d1" }.jsonObject
        assertEquals("223.5.5.5", d1["server"]!!.jsonPrimitive.content)

        // outbounds：UI 的 ui-node 保留（1.1.1.1），imported-node 追加
        val obs = merged["outbounds"]!!.jsonArray
        val uiNode = obs.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "ui-node" }.jsonObject
        assertEquals("1.1.1.1", uiNode["server"]!!.jsonPrimitive.content)
        assertTrue(obs.any { it.jsonObject["tag"]!!.jsonPrimitive.content == "imported-node" })

        // route.rules：UI 在前（高优先级先匹配），导入在后
        val routeRules = merged["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals("ui.com", routeRules[0].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("imported.com", routeRules[1].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `import highest overrides ui scalars`() {
        val merged = parse(ConfigMerger.merge(ui, imported, OverridePriority.IMPORT_HIGHEST))

        assertEquals("info", merged["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertEquals("imported-node", merged["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)

        // 数组按 tag 合并：导入的 d1 胜出（8.8.8.8）
        val servers = merged["dns"]!!.jsonObject["servers"]!!.jsonArray
        val d1 = servers.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "d1" }.jsonObject
        assertEquals("8.8.8.8", d1["server"]!!.jsonPrimitive.content)

        // route.rules：导入在前
        val routeRules = merged["route"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals("imported.com", routeRules[0].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("ui.com", routeRules[1].jsonObject["domain"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `duplicate identical rule entries are deduplicated`() {
        val uiDup = """{ "route": { "rules": [ {"domain":["a.com"],"outbound":"direct"} ] } }"""
        val importedDup = """{ "route": { "rules": [ {"domain":["a.com"],"outbound":"direct"}, {"domain":["b.com"],"outbound":"proxy"} ] } }"""
        val merged = parse(ConfigMerger.merge(uiDup, importedDup, OverridePriority.UI_HIGHEST))
        val rules = merged["route"]!!.jsonObject["rules"]!!.jsonArray
        // UI 在前（a.com），导入独有的 b.com 追加，重复的 a.com 不再出现
        assertEquals(2, rules.size)
    }
}
