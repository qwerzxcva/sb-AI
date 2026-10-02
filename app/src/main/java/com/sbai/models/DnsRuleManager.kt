package com.sbai.models

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * DNS 服务器类型
 */
enum class DnsServerType(val value: String, val label: String) {
    LOCAL("local", "本地"),
    REMOTE("remote", "远程");

    companion object {
        fun fromValue(value: String): DnsServerType = values().firstOrNull { it.value == value } ?: REMOTE
        val valuesList = values().toList()
    }
}

/**
 * DNS 服务器配置
 */
@Serializable
data class DnsServer(
    val id: Int = 0,
    val remarks: String = "",
    val enabled: Boolean = true,
    val address: String = "",
    val type: String = "remote",
    val tag: String? = null,
    val preferIpv4: Boolean = false,
    val preferIpv6: Boolean = false,
    val domains: List<String> = emptyList(),
    val clientSubnet: List<String> = emptyList(),
    val ruleSetTag: String? = null
) {
    fun toSingBoxJson(): Map<String, Any> {
        return mutableMapOf<String, Any>(
            "tag" to (tag ?: "dns_${address.replace(Regex("[^a-zA-Z0-9]"), "_")}"),
            "address" to address,
            "type" to type,
            "domains" to domains,
            "prefer_ipv4" to preferIpv4,
            "prefer_ipv6" to preferIpv6,
            "client_subnet" to clientSubnet
        ).also {
            if (ruleSetTag != null) it["rule_set_tag"] = ruleSetTag
        }
    }
}

/**
 * DNS 策略组
 */
@Serializable
data class DnsGroup(
    val id: Int = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val servers: List<String> = emptyList(),
    val strategy: String = "default",
    val preferIPv4: Boolean = false,
    val preferIPv6: Boolean = false
) {
    fun toSingBoxJson(): Map<String, Any> {
        return mapOf(
            "tag" to name,
            "servers" to servers,
            "strategy" to strategy,
            "prefer_ipv4" to preferIPv4,
            "prefer_ipv6" to preferIPv6
        )
    }
}

/**
 * DNS 规则管理器
 */
class DnsRuleManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("dns_rules", Context.MODE_PRIVATE)
    private val serversKey = "dns_servers"
    private val groupsKey = "dns_groups"

    private val json = Json { ignoreUnknownKeys = true }

    var servers: MutableList<DnsServer> = mutableListOf()
        private set

    var groups: MutableList<DnsGroup> = mutableListOf()
        private set

    fun load() {
        val serversStr = prefs.getString(serversKey, "[]") ?: "[]"
        servers = mutableListOf<DnsServer>().apply {
            addAll(json.decodeFromString<List<DnsServer>>(serversStr))
        }
        val groupsStr = prefs.getString(groupsKey, "[]") ?: "[]"
        groups = mutableListOf<DnsGroup>().apply {
            addAll(json.decodeFromString<List<DnsGroup>>(groupsStr))
        }
    }

    fun save() {
        prefs.edit().apply {
            putString(serversKey, json.encodeToString(servers))
            putString(groupsKey, json.encodeToString(groups))
            apply()
        }
    }

    fun addServer(server: DnsServer) {
        servers.add(server.copy(id = System.currentTimeMillis().toInt()))
        save()
    }

    fun updateServer(server: DnsServer) {
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) {
            servers[index] = server
            save()
        }
    }

    fun deleteServer(id: Int) {
        servers.removeAll { it.id == id }
        save()
    }

    fun toggleServer(id: Int) {
        val index = servers.indexOfFirst { it.id == id }
        if (index >= 0) {
            servers[index] = servers[index].copy(enabled = !servers[index].enabled)
            save()
        }
    }

    fun addGroup(group: DnsGroup) {
        groups.add(group.copy(id = System.currentTimeMillis().toInt()))
        save()
    }

    fun updateGroup(group: DnsGroup) {
        val index = groups.indexOfFirst { it.id == group.id }
        if (index >= 0) {
            groups[index] = group
            save()
        }
    }

    fun deleteGroup(id: Int) {
        groups.removeAll { it.id == id }
        save()
    }

    fun toggleGroup(id: Int) {
        val index = groups.indexOfFirst { it.id == id }
        if (index >= 0) {
            groups[index] = groups[index].copy(enabled = !groups[index].enabled)
            save()
        }
    }

    fun getAvailableServerTags(): List<String> {
        return servers.filter { it.enabled }.map { it.tag ?: "dns_${it.address}" }
    }

    fun getAvailableGroupTags(): List<String> {
        return groups.filter { it.enabled }.map { it.name }
    }
}
