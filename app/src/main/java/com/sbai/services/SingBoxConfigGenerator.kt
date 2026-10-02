package com.sbai.services

import com.sbai.models.*

/**
 * singbox 配置文件生成器
 */
class SingBoxConfigGenerator {

    fun generateConfig(
        routeRules: List<RouteRule>,
        dnsServers: List<DnsServer>,
        dnsGroups: List<DnsGroup>,
        loadBalanceRules: List<LoadBalanceRule>,
        outbounds: List<String>
    ): String {
        val config = mutableMapOf<String, Any>(
            "log" to mapOf(
                "disabled" to false,
                "level" to "info",
                "timestamp" to true
            ),
            "dns" to generateDnsConfig(dnsServers, dnsGroups),
            "outbounds" to generateOutbounds(outbounds, loadBalanceRules),
            "route" to generateRouteConfig(routeRules)
        )
        
        return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config)
    }

    private fun generateDnsConfig(servers: List<DnsServer>, groups: List<DnsGroup>): Map<String, Any> {
        val serversJson = servers.filter { it.enabled }.map { it.toSingBoxJson() }
        val groupsJson = groups.filter { it.enabled }.map { it.toSingBoxJson() }
        
        val result = mutableMapOf<String, Any>(
            "servers" to serversJson,
            "final" to "dns-default"
        )
        
        // DNS 策略组可以通过 final 字段引用
        if (groupsJson.isNotEmpty()) {
            result["groups"] = groupsJson
        }
        
        return result
    }

    private fun generateOutbounds(outbounds: List<String>, lbRules: List<LoadBalanceRule>): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        
        // 添加负载均衡规则生成的 outbounds
        lbRules.filter { it.enabled }.forEach { rule ->
            result.add(rule.toSingBoxJson())
        }
        
        // 添加普通出站
        outbounds.forEach { tag ->
            result.add(mapOf("tag" to tag))
        }
        
        return result
    }

    private fun generateRouteConfig(rules: List<RouteRule>): Map<String, Any> {
        val rulesJson = rules.filter { it.enabled }.map { rule ->
            val json = rule.toSingBoxJson().toMutableMap()
            
            // 如果规则指定了 DNS，添加 dns_tag
            if (rule.dnsTag != null) {
                json["dns_tag"] = rule.dnsTag
            }
            if (rule.dnsStrategy != null) {
                json["dns_strategy"] = rule.dnsStrategy
            }
            if (rule.ruleSetTag != null) {
                json["rule_set_tag"] = rule.ruleSetTag
            }
            
            json
        }
        
        return mapOf(
            "rules" to rulesJson,
            "auto_detect_interface" to true,
            "final" to "proxy"
        )
    }
}
