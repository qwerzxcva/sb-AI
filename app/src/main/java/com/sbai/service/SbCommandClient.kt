package com.sbai.service

import android.util.Log
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItem
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * libbox CommandClient 单例：连接正在运行的 sing-box 服务，
 * 订阅状态（流量/连接数）、代理组（延迟/选中）、日志与连接事件。
 *
 * 每个进程独立持有客户端；连接状态仅表示遥测通道，不代表 VPN 服务状态。
 */
object SbCommandClient : CommandClientHandler {

    data class DashboardStatus(
        val memory: Long = 0,
        val connectionsIn: Int = 0,
        val connectionsOut: Int = 0,
        val uplink: Long = 0,       // B/s
        val downlink: Long = 0,     // B/s
        val uplinkTotal: Long = 0,  // B
        val downlinkTotal: Long = 0,
        val connected: Boolean = false,
    )

    data class ProxyGroup(
        val tag: String,
        val type: String,
        val selectable: Boolean,
        val selected: String,
        val items: List<ProxyItem>,
    )

    data class ProxyItem(
        val tag: String,
        val type: String,
        val delay: Int,          // -1 = 未测
        val testTime: Long,
    )

    /** 活跃连接快照（由 libbox Connections 状态机维护） */
    data class ConnectionEntry(
        val id: String,
        val network: String,
        val source: String,
        val destination: String,
        val domain: String,
        val protocol: String,
        val rule: String,
        val outbound: String,
        val outboundType: String,
        val processPath: String,
        val userName: String,
        val uplinkTotal: Long,
        val downlinkTotal: Long,
        val createdAt: Long,
    )

    private val _status = MutableStateFlow(DashboardStatus())
    val status: StateFlow<DashboardStatus> = _status

    private val _groups = MutableStateFlow<List<ProxyGroup>>(emptyList())
    val groups: StateFlow<List<ProxyGroup>> = _groups

    /** urltest 结果缓存：groupTag → (outboundTag → delay) */
    private val _urlTestResults = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    val urlTestResults: StateFlow<Map<String, Map<String, Int>>> = _urlTestResults

    private val _connectedToService = MutableStateFlow(false)
    val connectedToService: StateFlow<Boolean> = _connectedToService

    private val _connections = MutableStateFlow<List<ConnectionEntry>>(emptyList())
    val connections: StateFlow<List<ConnectionEntry>> = _connections

    /** 保护 libbox Connections 状态机（Go 回调线程 vs UI 读取） */
    private val connectionsLock = Any()

    data class LogLine(val seq: Long, val text: String)

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs
    private val logQueue = ConcurrentLinkedQueue<LogLine>()
    private val logSeq = java.util.concurrent.atomic.AtomicLong(0)
    private const val MAX_LOGS = 500
    private const val MAX_CONNECTIONS = 300

    @Volatile private var client: CommandClient? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val retryMutex = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicLong(0)
    /** Lazy: merely observing a flow must not allocate native objects on the UI thread. */
    private var connectionState: Connections? = null

    // Native callbacks can occur on another thread before connect/disconnect returns.
    // Never hold a JVM monitor around native calls; stale handlers cannot publish state.
    private fun handler(epoch: Long): CommandClientHandler =
        java.lang.reflect.Proxy.newProxyInstance(
            CommandClientHandler::class.java.classLoader,
            arrayOf(CommandClientHandler::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SbCommandClientHandler($epoch)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> {
                    if (generation.get() == epoch) {
                        method.invoke(this, *(args ?: emptyArray()))
                    } else null
                }
            }
        } as CommandClientHandler

    /** Compatibility entry point; all native lifecycle work is dispatched to IO. */
    fun connect() {
        val epoch = generation.get()
        ioScope.launch { connectNow(epoch) }
    }

    private suspend fun connectNow(requestEpoch: Long) = lifecycleMutex.withLock {
        if (generation.get() != requestEpoch || _connectedToService.value) return@withLock
        val epoch = generation.incrementAndGet()
        val old = client
        client = null
        runCatching { old?.disconnect() }
        try {
            synchronized(connectionsLock) { connectionState = Libbox.newConnections() }
            _connections.value = emptyList()
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                addCommand(Libbox.CommandGroup)
                addCommand(Libbox.CommandLog)
                addCommand(Libbox.CommandConnections)
                addCommand(Libbox.CommandOutbounds)
                addCommand(Libbox.CommandDNS)
                addCommand(Libbox.CommandClashMode)
                statusInterval = StatusIntervalNanos
            }
            val c = Libbox.newCommandClient(handler(epoch), options)
            client = c // Own partial connections so failures can always release resources.
            c.connect()
        } catch (t: Exception) {
            generation.compareAndSet(epoch, epoch + 1)
            _connectedToService.value = false
            val failed = client
            client = null
            runCatching { failed?.disconnect() }
            Log.w(TAG, "command client connect failed", t)
        }
    }

    /** Deduplicated IO retries; failure never changes the authoritative VPN state. */
    suspend fun connectWithRetry(attempts: Int = 10, delayMs: Long = 800) =
        withContext(Dispatchers.IO) {
            if (!retryMutex.tryLock()) return@withContext
            try {
                repeat(attempts) {
                    if (_connectedToService.value) return@withContext
                    connectNow(generation.get())
                    if (_connectedToService.value) return@withContext
                    kotlinx.coroutines.delay(delayMs)
                }
            } finally {
                retryMutex.unlock()
            }
        }

    fun disconnect() {
        val epoch = generation.incrementAndGet() // Invalidate callbacks immediately.
        _status.value = DashboardStatus()
        _groups.value = emptyList()
        _connections.value = emptyList()
        _connectedToService.value = false
        ioScope.launch {
            lifecycleMutex.withLock {
                // A newer request owns the client now; do not tear it down.
                if (generation.get() != epoch) return@withLock
                val c = client
                client = null
                runCatching { c?.disconnect() }
                synchronized(connectionsLock) { connectionState = null }
            }
        }
    }

    // 以下均为 libbox native 同步 IPC 调用（unix socket 往返），UI 点击直接调用会阻塞主线程；
    // 真机 ANR 栈已证实 native CommandClient 调用在 main 线程会导致输入超时。统一投递到 IO。

    /** 关闭单条连接 */
    fun closeConnection(id: String) {
        ioScope.launch {
            runCatching { client?.closeConnection(id) }
                .onFailure { Log.w(TAG, "closeConnection failed", it) }
        }
    }

    /** 关闭全部连接 */
    fun closeAllConnections() {
        ioScope.launch {
            runCatching { client?.closeConnections() }
                .onFailure { Log.w(TAG, "closeConnections failed", it) }
        }
    }

    fun selectOutbound(groupTag: String, outboundTag: String) {
        ioScope.launch {
            runCatching { client?.selectOutbound(groupTag, outboundTag) }
                .onFailure { Log.w(TAG, "selectOutbound failed", it) }
        }
    }

    fun urlTest(groupTag: String) {
        ioScope.launch {
            runCatching { client?.urlTest(groupTag) }
                .onFailure { Log.w(TAG, "urlTest failed for group: $groupTag", it) }
        }
    }

    /** 对指定节点执行单次测速 */
    fun urlTestOutbound(
        groupTag: String,
        outboundTag: String,
        timeoutMs: Int = 5000,
    ): Int? = runCatching {
        client?.urlTestOutbound(groupTag, outboundTag, timeoutMs)
            ?.delay?.takeIf { it > 0 }
    }.getOrNull()

    /** 批量更新节点列表中的 urlTestDelay 字段（由 UI 触发） */
    fun syncUrlTestResultsToNodes(store: com.sbai.data.RuleStore) {
        val results = _urlTestResults.value
        if (results.isEmpty()) return
        val state = store.state.value
        var changed = false
        // 构建 outboundTag → delay 的 flat map
        val delayMap = results.entries
            .flatMap { (_, items) -> items.entries }
            .associate { (tag, delay) -> tag to delay }
        val updatedNodes = state.proxyNodes.map { node ->
            val delay = delayMap[SingBoxConfigGenerator.nodeTagOf(node)]
            if (delay != null && delay != node.urlTestDelay) {
                changed = true
                node.copy(urlTestDelay = delay, urlTestTime = System.currentTimeMillis())
            } else {
                node
            }
        }
        if (changed) {
            store.update { s -> s.copy(proxyNodes = updatedNodes) }
        }
    }

    // ---- CommandClientHandler ----

    override fun connected() {
        _connectedToService.value = true
    }

    override fun disconnected(message: String?) {
        _connectedToService.value = false
        _status.value = DashboardStatus()
        if (!message.isNullOrBlank()) appendLog("[disconnected] $message")
    }

    override fun writeStatus(message: StatusMessage) {
        // 只在值真正变化时才更新，避免不必要的 recomposition
        val current = _status.value
        if (current.connected && current.memory == message.memory &&
            current.connectionsIn == message.connectionsIn &&
            current.connectionsOut == message.connectionsOut &&
            current.uplink == message.uplink &&
            current.downlink == message.downlink &&
            current.uplinkTotal == message.uplinkTotal &&
            current.downlinkTotal == message.downlinkTotal
        ) {
            return // 值未变化，跳过更新
        }
        _status.value = DashboardStatus(
            memory = message.memory,
            connectionsIn = message.connectionsIn,
            connectionsOut = message.connectionsOut,
            uplink = message.uplink,
            downlink = message.downlink,
            uplinkTotal = message.uplinkTotal,
            downlinkTotal = message.downlinkTotal,
            connected = true,
        )
    }

    override fun writeGroups(iterator: OutboundGroupIterator) {
        // 构建结果 map: groupTag → (outboundTag → delay)
        val groupResults = mutableMapOf<String, MutableMap<String, Int>>()
        val newGroups = mutableListOf<ProxyGroup>()
        
        while (iterator.hasNext()) {
            val g: OutboundGroup = iterator.next()
            val items = buildList {
                val it2 = g.items
                val itemMap = mutableMapOf<String, Int>()
                while (it2.hasNext()) {
                    val item: OutboundGroupItem = it2.next()
                    add(
                        ProxyItem(
                            tag = item.tag,
                            type = item.type,
                            delay = item.urlTestDelay,
                            testTime = item.urlTestTime,
                        ),
                    )
                    if (item.urlTestDelay > 0) {
                        itemMap[item.tag] = item.urlTestDelay
                    }
                }
                if (itemMap.isNotEmpty()) {
                    groupResults[g.tag] = itemMap
                }
            }
            newGroups.add(
                ProxyGroup(
                    tag = g.tag,
                    type = g.type,
                    selectable = g.selectable,
                    selected = g.selected,
                    items = items,
                ),
            )
        }
        
        // 只在组列表真正变化时才更新
        val currentGroups = _groups.value
        val groupsChanged = newGroups.size != currentGroups.size || 
            newGroups.zip(currentGroups).any { (new, old) -> new.tag != old.tag || new.selected != old.selected }
        
        if (groupsChanged) {
            _groups.value = newGroups
        }
        
        if (groupResults.isNotEmpty()) {
            _urlTestResults.value = groupResults
        }
    }

    override fun writeLogs(iterator: LogIterator) {
        while (iterator.hasNext()) {
            val entry = iterator.next()
            appendLog("[${entry.level}] ${entry.message}")
        }
    }

    override fun writeConnectionEvents(events: io.nekohasekai.libbox.ConnectionEvents) {        // events 由 Go 侧传入；用 libbox Connections 状态机维护（不手动解析事件类型）
        val snapshot = synchronized(connectionsLock) {
            runCatching {
                val connectionState = connectionState ?: return@synchronized emptyList()
                connectionState.applyEvents(events)
                connectionState.filterState(Libbox.ConnectionStateActive.toInt())
                buildList {
                    val it = connectionState.iterator()
                    // Bound Kotlin allocation, not just the already-built published list.
                    while (size < MAX_CONNECTIONS && it.hasNext()) {
                        val c = it.next()
                        val proc = runCatching { c.processInfo }.getOrNull()
                        add(
                            ConnectionEntry(
                                id = c.id.orEmpty(),
                                network = c.network.orEmpty(),
                                source = c.source.orEmpty(),
                                destination = c.destination.orEmpty(),
                                domain = c.domain.orEmpty(),
                                protocol = c.protocol.orEmpty(),
                                rule = c.rule.orEmpty(),
                                outbound = c.outbound.orEmpty(),
                                outboundType = c.outboundType.orEmpty(),
                                processPath = proc?.processPath.orEmpty(),
                                userName = proc?.userName.orEmpty(),
                                uplinkTotal = c.uplinkTotal,
                                downlinkTotal = c.downlinkTotal,
                                createdAt = c.createdAt,
                            ),
                        )
                    }
                }
            }.getOrElse {
                Log.w(TAG, "connection snapshot failed", it)
                _connections.value
            }
        }
        // 保留迭代器顺序的前 MAX_CONNECTIONS 条；不假定 native 迭代顺序为时间排序。
        // Native 活动连接状态仍由内核维护，此处仅限制 UI 快照。
        _connections.value = snapshot
    }

    override fun writeDNSQuery(query: io.nekohasekai.libbox.DnsQuery) {
        // DNS 查询监控：lx 内核特有回调，暂仅记录失败项
        if (query.failed) {
            appendLog("[dns] ${query.domain} → ${query.dnsServer} 失败: ${query.error}")
        }
    }

    override fun writeOutbounds(iterator: OutboundGroupItemIterator) {
        // 节点明细已通过 writeGroups 中的 group.items 提供，此处忽略
    }

    override fun initializeClashMode(modeList: io.nekohasekai.libbox.StringIterator, currentMode: String) = Unit
    override fun updateClashMode(newMode: String) = Unit
    override fun setDefaultLogLevel(level: Int) = Unit
    override fun clearLogs() {
        logQueue.clear()
        _logs.value = emptyList()
    }

    fun clearLogsLocal() {
        logQueue.clear()
        _logs.value = emptyList()
    }

    private fun appendLog(line: String) {
        logQueue.add(LogLine(logSeq.incrementAndGet(), line))
        while (logQueue.size > MAX_LOGS) logQueue.poll()
        _logs.value = logQueue.toList()
    }

    internal const val StatusIntervalNanos = 1_000_000_000L // 1s（平衡实时性和性能）
    private const val TAG = "SbCommandClient"
}
