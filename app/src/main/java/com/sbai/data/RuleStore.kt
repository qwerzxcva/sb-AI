package com.sbai.data

import android.content.Context
import android.content.SharedPreferences
import com.sbai.service.NodeDedup
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 规则仓库：filesDir/sb-ai-state.json（原子写）+ 内存 StateFlow。
 *
 * 跨进程数据通道（关键设计）：
 *  - UI 进程与 :core 进程（SbAiVpnService）各自持有一个 RuleStore 单例。
 *  - 旧实现用 SharedPreferences 落盘：两个进程各自缓存内存副本，:core 的 commit
 *    （如自动禁用坏节点）对 UI 侧不可见，UI 的下次写入会用旧内存整份覆盖 :core 的
 *    变更 → 自动禁用被静默撤销、VPN 反复起不来（P0 相关根因之一）。
 *  - 现改为 JSON 文件 + tmp/rename 原子写：任何进程读文件都拿到最新落盘值；
 *    每次写前先 rebaseOnDisk（mtime 快路径，无外部新写则不重解析），保证
 *    「后写者基于最新磁盘态」，两个进程互不丢更新。
 *  - 旧 SharedPreferences（sb_ai_rules/app_state）仅作首次升级迁移源，不再写。
 */
class RuleStore private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    /** 仅用于旧数据迁移（一次性读取） */
    private val legacyPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val stateFile = File(appContext.filesDir, STATE_FILE_NAME)

    /** 写方标识：:core 进程写 "core"，UI/WorkManager 等写 "ui" */
    private val writerTag: String = detectWriterTag(appContext)
    /** 本进程已知的最新磁盘 updatedAt（快路径比较用，volatile：后台写线程更新） */
    @Volatile
    private var lastSeenUpdatedAt: Long = 0L

    @Serializable
    private data class Envelope(
        val updatedAt: Long,
        val writer: String,
        val data: AppState,
    )

    private val _state = MutableStateFlow(initialLoad())
    val state: StateFlow<AppState> = _state.asStateFlow()

    // 异步落盘用的后台作用域（单线程，保证写入顺序）
    private val storeScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1),
    )
    private var persistJob: kotlinx.coroutines.Job? = null

    private fun initialLoad(): AppState {
        val env = readEnvelope()
        if (env != null) {
            lastSeenUpdatedAt = env.updatedAt
            return env.data
        }
        // 首次升级/老用户：状态还在旧 SharedPreferences，迁移到新文件后以文件为准
        val legacyRaw = legacyPrefs.getString(KEY_STATE, null)
        val legacy = legacyRaw?.let {
            runCatching { json.decodeFromString(AppState.serializer(), it) }.getOrNull()
        }
        if (legacy != null) {
            lastSeenUpdatedAt = writeEnvelope(legacy)
            return legacy
        }
        return AppState()
    }

    /** 读磁盘信封；解析失败做隔离备份（不覆盖、不丢数据），返回 null */
    private fun readEnvelope(): Envelope? {
        val f = stateFile
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(Envelope.serializer(), f.readText()) }
            .getOrElse {
                android.util.Log.e("RuleStore", "配置文件解析失败，已隔离备份原始内容", it)
                runCatching {
                    f.copyTo(File(f.parentFile, "$STATE_FILE_NAME.corrupt.${System.currentTimeMillis()}"), overwrite = false)
                }
                null
            }
    }

    /**
     * 原子写：先写「本进程专属」tmp（避免跨进程 tmp 竞态），再 rename 到目标。
     * 返回写入的 updatedAt。
     */
    private fun writeEnvelope(data: AppState): Long {
        val ts = System.currentTimeMillis()
        val pid = android.os.Process.myPid()
        val tmpName = "$STATE_FILE_NAME.${writerTag}.$pid.tmp"
        val tmp = File(stateFile.parentFile, tmpName)
        tmp.writeText(json.encodeToString(Envelope.serializer(), Envelope(ts, writerTag, data)))
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(tmp.readText())
            tmp.delete()
        }
        lastSeenUpdatedAt = ts
        return ts
    }

    /**
     * 写前 rebase：磁盘上若有比本进程已知状态更新的写入（:core 的自动禁用等），
     * 先把磁盘态刷进内存，后续 transform 基于最新数据。
     * mtime 快路径：没有外部新写时只 stat 一次文件，不重新解析 JSON。
     */
    private fun rebaseOnDisk(): AppState = synchronized(this) {
        val f = stateFile
        if (f.exists() && f.lastModified() > lastSeenUpdatedAt) {
            val env = readEnvelope()
            if (env != null) {
                _state.value = env.data
                lastSeenUpdatedAt = env.updatedAt
                android.util.Log.i("RuleStore", "rebased on external write (writer=${env.writer}, ts=${env.updatedAt})")
            }
        }
        _state.value
    }

    /**
     * 从磁盘重新载入配置（全量，忽略 rebase 快路径）。
     *
     * 必需场景：VPN 服务运行在独立的 `:core` 进程，服务启动前调用本方法拿到
     * UI 刚改的最新配置。:core 的 reload() 是全量冷读，不依赖 mtime/lastSeen。
     */
    fun reload(): AppState = synchronized(this) {
        val env = readEnvelope()
        _state.value = env?.data ?: legacyLoad()
        lastSeenUpdatedAt = env?.updatedAt ?: 0L
        _state.value
    }

    /**
     * UI 侧定期调用（配合 2s 轮询）：若 :core 期间写过盘（自动禁用坏节点等），
     * 把最新磁盘态刷进内存 StateFlow，避免 UI 界面与 :core 实际配置脱节。
     * 返回是否发生了外部刷新。
     */
    fun refreshFromDisk(): Boolean = synchronized(this) {
        val f = stateFile
        if (!f.exists() || f.lastModified() <= lastSeenUpdatedAt) return@synchronized false
        val env = readEnvelope() ?: return@synchronized false
        if (env.updatedAt <= lastSeenUpdatedAt) return@synchronized false
        _state.value = env.data
        lastSeenUpdatedAt = env.updatedAt
        true
    }

    private fun persist(next: AppState) {
        _state.value = next
        // 写盘改后台异步原子写（tmp+rename），避免主线程被大 JSON 序列化阻塞（卡顿元凶）。
        // 跨进程可见性由 rebaseOnDisk + 原子 rename 保证（旧 SharedPreferences 方案做不到）。
        persistJob?.cancel()
        persistJob = storeScope.launch {
            runCatching { writeEnvelope(next) }
                .onFailure { android.util.Log.w("RuleStore", "配置异步写入失败", it) }
        }
    }

    /** 同步落盘（订阅/节点等跨进程立即生效的关键变更；:core 的自动禁用也走这里） */
    private fun commitNow(next: AppState) {
        runCatching { writeEnvelope(next) }
            .onFailure { android.util.Log.w("RuleStore", "配置同步写入失败", it) }
    }

    fun update(transform: (AppState) -> AppState) = synchronized(this) {
        val base = rebaseOnDisk()
        val next = transform(base)
        persist(next)
        next
    }

    /** 订阅/节点/负载均衡等变更后调用：rebase + 同步落盘，保证 :core 立即可见 */
    fun updateCommitted(transform: (AppState) -> AppState) = synchronized(this) {
        val base = rebaseOnDisk()
        val next = transform(base)
        _state.value = next
        persistJob?.cancel()
        commitNow(next)
        next
    }

    // ---- 领域方法（全部委托到 rebase 后的 update / updateCommitted） ----

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
        val target = if (server.tag.isBlank()) {
            server.copy(tag = nextTag(s.dnsServers.map { it.tag }, "dns"))
        } else server
        val list = s.dnsServers.toMutableList()
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

    fun replaceSubscriptionNodes(subscriptionId: String, nodes: List<ProxyNode>) = updateCommitted { s ->
        val others = s.proxyNodes.filterNot { it.subscriptionId == subscriptionId }
        val allowDedupeFor = { sid: String? ->
            if (sid == null || sid == subscriptionId) {
                s.subscriptions.firstOrNull { it.id == subscriptionId }?.removeDuplicates ?: true
            } else {
                s.subscriptions.firstOrNull { it.id == sid }?.removeDuplicates ?: true
            }
        }
        s.copy(proxyNodes = NodeDedup.mergeWithSubscription(others, nodes, subscriptionId, allowDedupeFor))
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
        val restored = runCatching { json.decodeFromString(AppState.serializer(), profile.snapshot) }
            .getOrElse { _state.value }
        _state.value = restored.copy(activeProfileId = id)
    }

    companion object {
        private const val PREFS_NAME = "sb_ai_rules"
        private const val KEY_STATE = "app_state"
        private const val STATE_FILE_NAME = "sb-ai-state.json"

        @Volatile
        private var instance: RuleStore? = null

        fun get(context: Context): RuleStore =
            instance ?: synchronized(this) {
                instance ?: RuleStore(context.applicationContext).also { instance = it }
            }
    }

    /** 检测当前进程归属；UI 进程写 "ui"，:core 进程写 "core"。 */
    private fun detectWriterTag(context: Context): String {
        // getRunningAppProcesses() 已废弃但跨版本可用；我们只取当前 PID 对应的那一条，
        // 不依赖系统精确度，够用。
        val name = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
                ?: ""
        }.getOrDefault("")
        return if (name.endsWith(":core")) "core" else "ui"
    }

    /** 首次启动时旧 sp 数据的一次性读取（仅用于迁移，不在后续路径中调用） */
    private fun legacyLoad(): AppState {
        val raw = legacyPrefs.getString(KEY_STATE, null) ?: return AppState()
        return runCatching { json.decodeFromString<AppState>(raw) }
            .getOrElse {
                android.util.Log.e("RuleStore", "旧 sp 配置文件解析失败，已隔离备份原始内容", it)
                runCatching {
                    legacyPrefs.edit()
                        .putString("app_state.corrupt.${System.currentTimeMillis()}", raw)
                        .commit()
                }
                AppState()
            }
    }
}
