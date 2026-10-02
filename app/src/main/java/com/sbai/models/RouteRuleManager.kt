package com.sbai.models

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 网络类型
 */
enum class NetworkType(val value: String, val label: String) {
    TCP("tcp", "TCP"),
    UDP("udp", "UDP"),
    ALL("all", "全部");

    companion object {
        fun fromValue(value: String): NetworkType = values().firstOrNull { it.value == value } ?: ALL
        val valuesList = values().toList()
    }
}

/**
 * 协议类型
 */
enum class ProtocolType(val value: String, val label: String) {
    HTTP("http", "HTTP"),
    TLS("tls", "TLS"),
    QUIC("quic", "QUIC"),
    GRPC("grpc", "gRPC"),
    STUBBED_DOMAIN("stubbed-domain", "Stubbed Domain"),
    FAKE_DNS("fake-dns", "Fake DNS"),
    ALL("all", "全部");

    companion object {
        fun fromValue(value: String): ProtocolType = values().firstOrNull { it.value == value } ?: ALL
        val valuesList = values().toList()
    }
}

/**
 * 逻辑运算模式
 */
enum class LogicalMode(val value: String, val label: String) {
    NONE("none", "无"),
    AND("and", "AND"),
    OR("or", "OR"),
    INVERT("invert", "反转");

    companion object {
        fun fromValue(value: String): LogicalMode = values().firstOrNull { it.value == value } ?: NONE
        val valuesList = values().toList()
    }
}

/**
 * DNS 策略
 */
enum class DnsStrategy(val value: String, val label: String) {
    DEFAULT("default", "默认"),
    IPV4_ONLY("ipv4-only", "仅 IPv4"),
    IPV6_ONLY("ipv6-only", "仅 IPv6"),
    IPV4_PREFER("ipv4-prefer", "偏好 IPv4"),
    IPV6_PREFER("ipv6-prefer", "偏好 IPv6"),
    ALL("all", "全部");

    companion object {
        fun fromValue(value: String): DnsStrategy = values().firstOrNull { it.value == value } ?: DEFAULT
        val valuesList = values().toList()
    }
}

/**
 * 路由规则动作
 */
enum class RouteAction(val value: String, val label: String) {
    ROUTE("route", "路由"),
    DIRECT("direct", "直连"),
    REJECT("reject", "拒绝"),
    LOAD_BALANCE("load-balance", "负载均衡");

    companion object {
        fun fromValue(value: String): RouteAction = values().firstOrNull { it.value == value } ?: ROUTE
        val valuesList = values().toList()
    }
}

/**
 * 单条路由规则
 */
@Serializable
data class RouteRule(
    val id: Int = 0,
    val remarks: String = "",
    val enabled: Boolean = true,
    val domains: List<String> = emptyList(),
    val ips: List<String> = emptyList(),
    val ipCidrs: List<String> = emptyList(),
    val networks: List<String> = listOf("all"),
    val protocols: List<String> = listOf("all"),
    val logicalMode: String = "none",
    val action: String = "route",
    val outbound: String = "",
    val dnsStrategy: String? = null,
    val dnsTag: String? = null,
    val ruleSetTag: String? = null,
    val invert: Boolean = false,
    val disableCache: Boolean = false,
    val downloadDomain: String? = null
) {
    fun toSingBoxJson(): Map<String, Any> {
        val json = mutableMapOf<String, Any>(
            "remarks" to remarks,
            "enabled" to enabled,
            "domain" to domains,
            "ip_cidr" to ipCidrs,
            "network" to networks,
            "protocol" to protocols,
            "action" to action
        )
        if (logicalMode != "none") json["logical_mode"] = logicalMode
        if (action == "route" && outbound.isNotEmpty()) json["outbound"] = outbound
        if (dnsStrategy != null) json["dns_strategy"] = dnsStrategy
        if (dnsTag != null) json["dns_tag"] = dnsTag
        if (ruleSetTag != null) json["rule_set_tag"] = ruleSetTag
        if (invert) json["invert"] = true
        if (disableCache) json["disable_cache"] = true
        if (downloadDomain != null) json["domain_downloader"] = downloadDomain
        return json
    }
}

/**
 * 路由规则管理器
 */
class RouteRuleManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("route_rules", Context.MODE_PRIVATE)
    private val rulesKey = "route_rules"
    private val outboundsKey = "available_outbounds"
    private val dnsTagsKey = "available_dns_tags"
    private val ruleSetsKey = "available_rule_sets"

    private val json = Json { ignoreUnknownKeys = true }

    var rules: MutableList<RouteRule> = mutableListOf()
        private set

    var availableOutbounds: List<String> = emptyList()
        private set

    var availableDnsTags: List<String> = emptyList()
        private set

    var availableRuleSets: List<String> = emptyList()
        private set

    fun load() {
        val jsonStr = prefs.getString(rulesKey, "[]") ?: "[]"
        rules = mutableListOf<RouteRule>().apply {
            addAll(json.decodeFromString<List<RouteRule>>(jsonStr))
        }
        val outboundsStr = prefs.getString(outboundsKey, "[]") ?: "[]"
        availableOutbounds = json.decodeFromString(outboundsStr)
        val dnsTagsStr = prefs.getString(dnsTagsKey, "[]") ?: "[]"
        availableDnsTags = json.decodeFromString(dnsTagsStr)
        val ruleSetsStr = prefs.getString(ruleSetsKey, "[]") ?: "[]"
        availableRuleSets = json.decodeFromString(ruleSetsStr)
    }

    fun save() {
        prefs.edit().apply {
            putString(rulesKey, json.encodeToString(rules))
            putString(outboundsKey, json.encodeToString(availableOutbounds))
            putString(dnsTagsKey, json.encodeToString(availableDnsTags))
            putString(ruleSetsKey, json.encodeToString(availableRuleSets))
            apply()
        }
    }

    fun addRule(rule: RouteRule) {
        rules.add(rule.copy(id = System.currentTimeMillis().toInt()))
        save()
    }

    fun updateRule(rule: RouteRule) {
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            rules[index] = rule
            save()
        }
    }

    fun deleteRule(id: Int) {
        rules.removeAll { it.id == id }
        save()
    }

    fun toggleRule(id: Int) {
        val index = rules.indexOfFirst { it.id == id }
        if (index >= 0) {
            rules[index] = rules[index].copy(enabled = !rules[index].enabled)
            save()
        }
    }

    fun setOutbounds(outbounds: List<String>) {
        availableOutbounds = outbounds
        save()
    }

    fun setDnsTags(tags: List<String>) {
        availableDnsTags = tags
        save()
    }

    fun setRuleSets(ruleSets: List<String>) {
        availableRuleSets = ruleSets
        save()
    }
}
