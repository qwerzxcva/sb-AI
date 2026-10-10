package com.sbai.service

import com.sbai.data.RouteRule
import com.sbai.data.RouteRuleSet
import com.sbai.data.RuleAction
import com.sbai.data.RuleLogic
import com.sbai.data.RuleSetType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把一份订阅自带的路由规则按动作归并成三个本地规则集：直连 / 代理 / 拦截。
 * 每类只留一条引用该规则集的路由规则，避免订阅里几百条规则平铺进路由列表。
 */
object SubscriptionRuleMerger {

    data class Merged(
        val ruleSets: List<RouteRuleSet>,
        val rules: List<RouteRule>,
    )

    fun merge(subscriptionId: String, subscriptionName: String, imported: List<RouteRule>): Merged {
        val base = subscriptionName.ifBlank { "订阅" }.take(24)
        val buckets = listOf(
            RuleAction.ROUTE_DIRECT to "直连",
            RuleAction.ROUTE_PROXY to "代理",
            RuleAction.REJECT to "拦截",
        )
        val ruleSets = mutableListOf<RouteRuleSet>()
        val rules = mutableListOf<RouteRule>()
        buckets.forEach { (action, suffix) ->
            val group = imported.filter { it.action == action && it.enabled }
            if (group.isEmpty()) return@forEach
            val tag = "$base$suffix"
            val content = toInlineRuleSet(group) ?: return@forEach
            ruleSets += RouteRuleSet(
                tag = tag,
                type = RuleSetType.LOCAL,
                localContent = content,
            )
            rules += RouteRule(
                name = "$base · $suffix",
                action = action,
                ruleSetTags = listOf(tag),
                subscriptionId = subscriptionId,
            )
        }
        return Merged(ruleSets, rules)
    }

    /** 只合并能表达成 source 规则的字段；含进程/端口等复杂条件的规则返回 null，调用方原样保留。 */
    private fun toInlineRuleSet(rules: List<RouteRule>): String? {
        val simple = rules.filter { isMergeable(it) }
        if (simple.isEmpty()) return null
        val merged = buildJsonObject {
            put("version", 3)
            put("rules", buildJsonArray {
                add(buildJsonObject {
                    val domains = simple.flatMap { it.domains }.distinct()
                    val suffixes = simple.flatMap { it.domainSuffixes }.distinct()
                    val keywords = simple.flatMap { it.domainKeywords }.distinct()
                    val regexes = simple.flatMap { it.domainRegexes }.distinct()
                    val cidrs = simple.flatMap { it.ipCidrs }.distinct()
                    if (domains.isNotEmpty()) put("domain", jsonStrings(domains))
                    if (suffixes.isNotEmpty()) put("domain_suffix", jsonStrings(suffixes))
                    if (keywords.isNotEmpty()) put("domain_keyword", jsonStrings(keywords))
                    if (regexes.isNotEmpty()) put("domain_regex", jsonStrings(regexes))
                    if (cidrs.isNotEmpty()) put("ip_cidr", jsonStrings(cidrs))
                })
            })
        }
        return merged.toString()
    }

    fun isMergeable(rule: RouteRule): Boolean =
        rule.logic == RuleLogic.AND &&
            !rule.invert &&
            rule.networks.isEmpty() &&
            rule.protocols.isEmpty() &&
            rule.ports.isEmpty() &&
            rule.portRanges.isEmpty() &&
            rule.ruleSetTags.isEmpty() &&
            rule.sourceIpCidrs.isEmpty() &&
            rule.packageNames.isEmpty() &&
            rule.processNames.isEmpty() &&
            (rule.domains.isNotEmpty() || rule.domainSuffixes.isNotEmpty() ||
                rule.domainKeywords.isNotEmpty() || rule.domainRegexes.isNotEmpty() ||
                rule.ipCidrs.isNotEmpty())

    private fun jsonStrings(values: List<String>) = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
