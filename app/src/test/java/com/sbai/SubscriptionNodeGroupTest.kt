package com.sbai

import com.sbai.data.AppState
import com.sbai.data.ProxyNode
import com.sbai.data.RouteRuleSet
import com.sbai.data.RuleSetType
import com.sbai.service.SingBoxConfigGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：订阅节点分组规则集（<订阅名>直连/代理/拦截）。
 * localContent 是节点 tag JSON 数组的 LOCAL 规则集 → 生成同名 selector 出口组，
 * 且不再被当作匹配规则集输出到 route.rule_set（否则 sing-box 会因空 rules 拒绝启动）。
 */
class SubscriptionNodeGroupTest {

    private fun cfg(state: AppState) =
        Json.parseToJsonElement(SingBoxConfigGenerator.generate(state)).jsonObject

    private fun node(name: String) = ProxyNode(
        name = name,
        outboundJson = """{"type":"trojan","tag":"$name","server":"1.2.3.4"}""",
    )

    private fun groupRuleSet(tag: String, nodeTags: List<String>) = RouteRuleSet(
        tag = tag,
        type = RuleSetType.LOCAL,
        localContent = Json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(nodeTags.map { kotlinx.serialization.json.JsonPrimitive(it) }),
        ),
    )

    @Test
    fun `node group rule set generates selector outbound and is excluded from route rule_set`() {
        val nodes = listOf(node("n1"), node("n2"))
        val tags = nodes.map { SingBoxConfigGenerator.nodeTagOf(it) }
        val c = cfg(AppState(
            proxyNodes = nodes,
            routeRuleSets = listOf(groupRuleSet("机场A代理", tags)),
        ))
        val outbounds = c["outbounds"]!!.jsonArray.map { it.jsonObject }
        val group = outbounds.firstOrNull { it["tag"]!!.jsonPrimitive.content == "机场A代理" }
        assertTrue(group != null)
        assertEquals("selector", group!!["type"]!!.jsonPrimitive.content)
        val groupMembers = group["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(tags.toSet(), groupMembers.toSet())
        // 不能同时作为匹配规则集输出（空 rules 会被内核拒绝）
        val routeRuleSets = c["route"]!!.jsonObject["rule_set"]!!.jsonArray.map { it.jsonObject }
        assertTrue(routeRuleSets.none { it["tag"]!!.jsonPrimitive.content == "机场A代理" })
    }

    @Test
    fun `normal local rule set with rules json still goes to route rule_set`() {
        val rules = """{"rules":[{"domain_suffix":["example.com"],"outbound":"direct"}]}"""
        val c = cfg(AppState(
            routeRuleSets = listOf(RouteRuleSet(tag = "normal", type = RuleSetType.LOCAL, localContent = rules)),
        ))
        val routeRuleSets = c["route"]!!.jsonObject["rule_set"]!!.jsonArray.map { it.jsonObject }
        assertTrue(routeRuleSets.any { it["tag"]!!.jsonPrimitive.content == "normal" })
    }

    @Test
    fun `empty node group is not treated as node group`() {
        // 空数组不算节点分组：否则生成空 selector（内核拒绝）且被误排除出 route.rule_set。
        val c = cfg(AppState(
            routeRuleSets = listOf(groupRuleSet("机场B直连", emptyList())),
        ))
        val outbounds = c["outbounds"]!!.jsonArray.map { it.jsonObject }
        val group = outbounds.firstOrNull { it["tag"]!!.jsonPrimitive.content == "机场B直连" }
        assertTrue(group == null)  // 不生成空 selector
    }

    @Test
    fun `non-array local content is not treated as node group`() {
        val rs = RouteRuleSet(tag = "obj", type = RuleSetType.LOCAL, localContent = """{"rules":[]}""")
        val c = cfg(AppState(routeRuleSets = listOf(rs)))
        val routeRuleSets = c["route"]!!.jsonObject["rule_set"]!!.jsonArray.map { it.jsonObject }
        assertTrue(routeRuleSets.any { it["tag"]!!.jsonPrimitive.content == "obj" })
        val outbounds = c["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertFalse(outbounds.any { it["tag"]!!.jsonPrimitive.content == "obj" })
    }
}
