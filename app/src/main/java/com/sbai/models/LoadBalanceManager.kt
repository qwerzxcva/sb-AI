package com.sbai.models

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 负载均衡模式
 */
enum class LoadBalanceMode(
    val value: String,
    val configKey: String,
    val label: String
) {
    ROUND_ROBIN("roundrobin", "roundrobin", "轮询"),
    CONSISTENT_HASH("consistenthash", "consistenthash", "一致性哈希"),
    RANDOM("random", "random", "随机"),
    PASSIVE_CHECK("passivecheck", "passivecheck", "被动检测"),
    URL_TEST("urltest", "urltest", "URL 测试"),
    AUTO("auto", "auto", "自动");

    companion object {
        fun fromConfigKey(key: String): LoadBalanceMode = values().firstOrNull { it.configKey == key } ?: ROUND_ROBIN
    }
}

/**
 * 负载均衡规则
 */
@Serializable
data class LoadBalanceRule(
    val id: Int = 0,
    val remarks: String = "",
    val enabled: Boolean = true,
    val outbounds: List<String> = emptyList(),
    val mode: LoadBalanceMode = LoadBalanceMode.ROUND_ROBIN,
    val url: String? = null,
    val interval: Int = 30,
    val tolerance: Int = 50,
    val stickyHash: String? = null,
    val defaultOutbound: String? = null,
    val fallbackToDirect: Boolean = false,
    val fallbackToDefault: Boolean = false,
    val poolSize: Int = 3,
    val poolTolerance: Int = 50
) {
    fun toSingBoxJson(): Map<String, Any> {
        val json = mutableMapOf<String, Any>(
            "tag" to remarks,
            "type" to mode.value,
            "outbounds" to outbounds
        )
        
        if (mode == LoadBalanceMode.URL_TEST || mode == LoadBalanceMode.PASSIVE_CHECK) {
            if (url != null) json["url"] = url
            json["interval"] = listOf(interval)
            json["tolerance"] = tolerance
        }
        
        if (mode == LoadBalanceMode.CONSISTENT_HASH && stickyHash != null) {
            json["sticky_hash"] = stickyHash
        }
        
        if (defaultOutbound != null) {
            json["default"] = defaultOutbound
        }
        
        return json.toMap()
    }
}

/**
 * 负载均衡管理器
 */
class LoadBalanceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("load_balance", Context.MODE_PRIVATE)
    private val rulesKey = "lb_rules"
    private val outboundsKey = "available_outbounds"

    private val json = Json { ignoreUnknownKeys = true }

    var rules: MutableList<LoadBalanceRule> = mutableListOf()
        private set

    var availableOutbounds: List<String> = emptyList()
        private set

    fun load() {
        val rulesStr = prefs.getString(rulesKey, "[]") ?: "[]"
        rules = mutableListOf<LoadBalanceRule>().apply {
            addAll(json.decodeFromString<List<LoadBalanceRule>>(rulesStr))
        }
        val outboundsStr = prefs.getString(outboundsKey, "[]") ?: "[]"
        availableOutbounds = json.decodeFromString(outboundsStr)
    }

    fun save() {
        prefs.edit().apply {
            putString(rulesKey, json.encodeToString(rules))
            putString(outboundsKey, json.encodeToString(availableOutbounds))
            apply()
        }
    }

    fun addRule(rule: LoadBalanceRule) {
        rules.add(rule.copy(id = System.currentTimeMillis().toInt()))
        save()
    }

    fun updateRule(rule: LoadBalanceRule) {
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
}
