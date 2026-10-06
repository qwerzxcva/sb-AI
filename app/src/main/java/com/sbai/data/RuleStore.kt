package com.sbai.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    // 异步落盘用的后台作用域（单线程，保证写入顺序）
    private val storeScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1),
    )
    private var persistJob: kotlinx.coroutines.Job? = null

    private fun load(): AppState {
        val raw = prefs.getString(KEY_STATE, null) ?: return AppState()
        return runCatching { json.decodeFromString(AppState.serializer(), raw) }
            .getOrElse {
                // 解析失败时先备份原始内容再回退默认值，避免下次 persist 把用户数据静默清空
                android.util.Log.e("RuleStore", "配置解析失败，已备份原始内容", it)
                runCatching {
                    prefs.edit()
                        .putString("app_state.corrupt.${System.currentTimeMillis()}", raw)
                        .commit()
                }
                AppState()
            }
    }

    /**
     * 从磁盘重新载入配置。
     *
     * 必需场景：VPN 服务运行在独立的 `:core` 进程，该进程的 RuleStore 单例在首次构造时
     * 只读一次磁盘快照；UI 进程改了配置后，:core 里的内存 StateFlow 仍是旧值。
     * 因此服务每次启动前必须调用本方法，否则会拿旧配置起内核。
     */
    fun reload(): AppState = synchronized(this) {
        val fresh = load()
        _state.value = fresh
        fresh
    }

    private fun persist(next: AppState) {
        _state.value = next
        // 关键优化：写盘改为后台异步 apply()，避免主线程被大 JSON 序列化阻塞（卡顿元凶）。
        // 跨进程可见性由两点保证：
        //   1) VPN 服务(:core)启动前会调用 reload() 重新读盘；
        //   2) 下面对「订阅/节点/设置」等关键变更，额外用 commit() 同步一次（见 commitNow）。
        // 这样既消除了每次切开关都同步写整份配置的主线程开销，又不丢跨进程一致性。
        persistJob?.cancel()
        persistJob = storeScope.launch {
            runCatching {
                prefs.edit()
                    .putString(KEY_STATE, json.encodeToString(AppState.serializer(), next))
                    .apply()
            }.onFailure {
                android.util.Log.w("RuleStore", "配置异步写入失败", it)
            }
        }
    }

    /** 对跨进程立即生效的关键变更，同步落盘一次（低频调用，开销可接受） */
    private fun commitNow(next: AppState) {
        runCatching {
            prefs.edit()
                .putString(KEY_STATE, json.encodeToString(AppState.serializer(), next))
                .commit()
        }.onFailure {
            android.util.Log.w("RuleStore", "配置同步写入失败", it)
        }
    }

    fun update(transform: (AppState) -> AppState) = synchronized(this) {
        val next = transform(_state.value)
        persist(next)
        next
    }

    /** 订阅/节点/负载均衡等变更后调用：在异步写之外再同步落盘一次，保证 :core 立即可见 */
    fun updateCommitted(transform: (AppState) -> AppState) = synchronized(this) {
        val next = transform(_state.value)
        _state.value = next
        persistJob?.cancel()
        commitNow(next)
        next
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
        // fakeIP 唯一性：sing-box 只允许一个 fakeip server；新建/启用 fakeip 时禁用其它 fakeip
        if (target.type == DnsServerType.FAKEIP && target.enabled) {
            for (i in list.indices) {
                if (list[i].type == DnsServerType.FAKEIP && list[i].id != target.id && list[i].enabled) {
                    list[i] = list[i].copy(enabled = false)
                }
            }
        }
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
    fun updateLoadBalance(lb: LoadBalanceConfig) = updateCommitted { s -> s.copy(loadBalance = lb) }

    // ---- Proxy nodes ----
    fun upsertProxyNode(node: ProxyNode) = updateCommitted { s ->
        val list = s.proxyNodes.toMutableList()
        val idx = list.indexOfFirst { it.id == node.id }
        if (idx >= 0) list[idx] = node else list.add(node)
        s.copy(proxyNodes = list)
    }

    fun deleteProxyNode(id: String) = updateCommitted { s ->
        s.copy(proxyNodes = s.proxyNodes.filterNot { it.id == id })
    }

    /** 用订阅解析结果整体替换该订阅下的节点（替换后做跨订阅去重：配置完全相同只保留首次出现） */
    fun replaceSubscriptionNodes(subscriptionId: String, nodes: List<ProxyNode>) = updateCommitted { s ->
        val merged = s.proxyNodes.filterNot { it.subscriptionId == subscriptionId } + nodes
        s.copy(proxyNodes = dedupeNodes(merged))
    }

    /** 跨订阅/跨来源去重：按规范化配置去重，保留首次出现的节点（手动节点与先导入的订阅优先） */
    private fun dedupeNodes(nodes: List<ProxyNode>): List<ProxyNode> {
        val seen = HashSet<String>()
        return nodes.filter { node ->
            seen.add(com.sbai.service.NodeDedup.normalize(node.outboundJson))
        }
    }

    // ---- Subscriptions ----
    fun upsertSubscription(sub: Subscription) = updateCommitted { s ->
        val list = s.subscriptions.toMutableList()
        val idx = list.indexOfFirst { it.id == sub.id }
        if (idx >= 0) list[idx] = sub else list.add(sub)
        s.copy(subscriptions = list)
    }

    fun deleteSubscription(id: String) = updateCommitted { s ->
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

    // ---- Config Profiles（多配置快照） ----
    fun createProfile(name: String, state: AppState): ConfigProfile {
        val snapshot = json.encodeToString(AppState.serializer(), state)
        val profile = ConfigProfile(name = name, snapshot = snapshot)
        update { s -> s.copy(profiles = s.profiles + profile, activeProfileId = profile.id) }
        return profile
    }

    fun deleteProfile(id: String) = update { s ->
        val list = s.profiles.filterNot { it.id == id }
        s.copy(profiles = list, activeProfileId = if (s.activeProfileId == id) "" else s.activeProfileId)
    }

    fun activateProfile(id: String) {
        val profile = _state.value.profiles.find { it.id == id } ?: return
        persist(_state.value.copy(activeProfileId = id))
        // 快照还原到内存 StateFlow（磁盘存的是当前编辑态，profile.snapshot 保持原始快照）
        val restored = runCatching { json.decodeFromString(AppState.serializer(), profile.snapshot) }
            .getOrElse { _state.value }
        _state.value = restored.copy(activeProfileId = id)
    }

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
