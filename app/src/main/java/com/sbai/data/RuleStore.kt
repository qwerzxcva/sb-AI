package com.sbai.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * 规则仓库：SharedPreferences + kotlinx.serialization 持久化。
 * 全部读取走内存 StateFlow，写入后立即落盘。
 */
class RuleStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private fun load(): AppState {
        val raw = prefs.getString(KEY_STATE, null) ?: return AppState()
        return runCatching { json.decodeFromString(AppState.serializer(), raw) }
            .getOrElse { AppState() }
    }

    private fun persist(next: AppState) {
        _state.value = next
        prefs.edit().putString(KEY_STATE, json.encodeToString(AppState.serializer(), next)).apply()
    }

    fun update(transform: (AppState) -> AppState) = synchronized(this) {
        persist(transform(_state.value))
    }

    // ---- Route rules ----
    fun upsertRouteRule(rule: RouteRule) = update { s ->
        val list = s.routeRules.toMutableList()
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule else list.add(rule)
        s.copy(routeRules = list)
    }

    fun deleteRouteRule(id: String) = update { s ->
        s.copy(routeRules = s.routeRules.filterNot { it.id == id })
    }

    fun moveRouteRule(id: String, up: Boolean) = update { s ->
        val list = s.routeRules.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        val target = if (up) idx - 1 else idx + 1
        if (idx >= 0 && target in list.indices) {
            val tmp = list[idx]; list[idx] = list[target]; list[target] = tmp
        }
        s.copy(routeRules = list)
    }

    /** 长按拖动排序后整体写回（越靠上优先级越高） */
    fun reorderRouteRules(orderedIds: List<String>) = update { s ->
        val byId = s.routeRules.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { byId[it] }
        val rest = s.routeRules.filterNot { it.id in orderedIds }
        s.copy(routeRules = reordered + rest)
    }

    /** 按顺序生成不冲突的 tag：prefix-1, prefix-2, ... */
    private fun nextTag(existing: List<String>, prefix: String): String {
        val used = existing.toSet()
        var n = 1
        while ("$prefix-$n" in used) n++
        return "$prefix-$n"
    }

    // ---- Route rule sets ----
    fun upsertRuleSet(rs: RouteRuleSet) = update { s ->
        // 未填 tag 时按顺序自动生成（ruleset-1, ...）
        val target = if (rs.tag.isBlank()) {
            rs.copy(tag = nextTag(s.routeRuleSets.map { it.tag }, "ruleset"))
        } else rs
        val list = s.routeRuleSets.toMutableList()
        val idx = list.indexOfFirst { it.id == target.id }
        if (idx >= 0) list[idx] = target else list.add(target)
        s.copy(routeRuleSets = list)
    }

    fun deleteRuleSet(id: String) = update { s ->
        val removedTag = s.routeRuleSets.firstOrNull { it.id == id }?.tag
        s.copy(
            routeRuleSets = s.routeRuleSets.filterNot { it.id == id },
            // 级联清理：剔除所有规则对该规则集 tag 的引用，避免悬空 rule_set 导致 checkConfig 失败
            routeRules = s.routeRules.map { r ->
                if (removedTag != null && removedTag in r.ruleSetTags) {
                    r.copy(ruleSetTags = r.ruleSetTags - removedTag)
                } else r
            },
            dnsRules = s.dnsRules.map { r ->
                if (removedTag != null && removedTag in r.ruleSetTags) {
                    r.copy(ruleSetTags = r.ruleSetTags - removedTag)
                } else r
            },
        )
    }

    // ---- DNS servers ----
    fun upsertDnsServer(server: DnsServer) = update { s ->
        // 未填 tag 时按顺序自动生成（dns-1, dns-2, ...）
        val target = if (server.tag.isBlank()) {
            server.copy(tag = nextTag(s.dnsServers.map { it.tag }, "dns"))
        } else server
        val list = s.dnsServers.toMutableList()
        val idx = list.indexOfFirst { it.id == target.id }
        if (idx >= 0) list[idx] = target else list.add(target)
        s.copy(dnsServers = list)
    }

    fun deleteDnsServer(id: String) = update { s ->
        val removed = s.dnsServers.firstOrNull { it.id == id }?.tag
        s.copy(
            dnsServers = s.dnsServers.filterNot { it.id == id },
            dnsGroups = s.dnsGroups.map { g ->
                if (removed != null && removed in g.serverTags) g.copy(serverTags = g.serverTags - removed) else g
            },
        )
    }

    // ---- DNS groups ----
    fun upsertDnsGroup(group: DnsGroup) = update { s ->
        val target = if (group.name.isBlank()) {
            group.copy(name = nextTag(s.dnsGroups.map { it.name }, "group"))
        } else group
        val list = s.dnsGroups.toMutableList()
        val idx = list.indexOfFirst { it.id == target.id }
        if (idx >= 0) list[idx] = target else list.add(target)
        s.copy(dnsGroups = list)
    }

    fun deleteDnsGroup(id: String) = update { s ->
        s.copy(dnsGroups = s.dnsGroups.filterNot { it.id == id })
    }

    // ---- DNS rules（手动创建，可排序）----
    fun upsertDnsRule(rule: DnsRule) = update { s ->
        val list = s.dnsRules.toMutableList()
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule else list.add(rule)
        s.copy(dnsRules = list)
    }

    fun deleteDnsRule(id: String) = update { s ->
        s.copy(dnsRules = s.dnsRules.filterNot { it.id == id })
    }

    /** 整体重排（长按拖动排序后写回） */
    fun reorderDnsRules(orderedIds: List<String>) = update { s ->
        val byId = s.dnsRules.associateBy { it.id }
        val reordered = orderedIds.mapNotNull { byId[it] }
        val rest = s.dnsRules.filterNot { it.id in orderedIds }
        s.copy(dnsRules = reordered + rest)
    }

    // ---- Load balance ----
    fun updateLoadBalance(lb: LoadBalanceConfig) = update { s -> s.copy(loadBalance = lb) }

    // ---- Proxy nodes ----
    fun upsertProxyNode(node: ProxyNode) = update { s ->
        val list = s.proxyNodes.toMutableList()
        val idx = list.indexOfFirst { it.id == node.id }
        if (idx >= 0) list[idx] = node else list.add(node)
        s.copy(proxyNodes = list)
    }

    fun deleteProxyNode(id: String) = update { s ->
        s.copy(proxyNodes = s.proxyNodes.filterNot { it.id == id })
    }

    /** 用订阅解析结果整体替换该订阅下的节点 */
    fun replaceSubscriptionNodes(subscriptionId: String, nodes: List<ProxyNode>) = update { s ->
        s.copy(proxyNodes = s.proxyNodes.filterNot { it.subscriptionId == subscriptionId } + nodes)
    }

    // ---- Subscriptions ----
    fun upsertSubscription(sub: Subscription) = update { s ->
        val list = s.subscriptions.toMutableList()
        val idx = list.indexOfFirst { it.id == sub.id }
        if (idx >= 0) list[idx] = sub else list.add(sub)
        s.copy(subscriptions = list)
    }

    fun deleteSubscription(id: String) = update { s ->
        s.copy(
            subscriptions = s.subscriptions.filterNot { it.id == id },
            proxyNodes = s.proxyNodes.filterNot { it.subscriptionId == id },
        )
    }

    // ---- Settings ----
    fun updateSettings(settings: AppSettings) = update { s -> s.copy(settings = settings) }

    // ---- 静态 hosts 映射 ----
    fun upsertHostsEntry(entry: HostsEntry) = update { s ->
        val list = s.customHosts.toMutableList()
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        s.copy(customHosts = list)
    }

    fun deleteHostsEntry(id: String) = update { s ->
        s.copy(customHosts = s.customHosts.filterNot { it.id == id })
    }

    /** 备份导入：整体替换应用状态 */
    fun replaceAll(state: AppState) = persist(state)

    companion object {
        private const val PREFS_NAME = "sb_ai_rules"
        private const val KEY_STATE = "app_state"

        @Volatile
        private var instance: RuleStore? = null

        fun get(context: Context): RuleStore =
            instance ?: synchronized(this) {
                instance ?: RuleStore(context.applicationContext).also { instance = it }
            }
    }
}
