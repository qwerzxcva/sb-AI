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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * libbox CommandClient 单例：连接正在运行的 sing-box 服务，
 * 订阅状态（流量/连接数）、代理组（延迟/选中）、日志与连接事件。
 *
 * 生命周期与 SbAiVpnService 绑定；UI 侧只读取 StateFlow。
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

    private var client: CommandClient? = null
    /** libbox 连接状态机（Go 侧维护），与公开 StateFlow 区分命名 */
    private var connectionState: Connections = Libbox.newConnections()

    @Synchronized
    fun connect() {
        if (client != null) return
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandGroup)
            addCommand(Libbox.CommandLog)
            addCommand(Libbox.CommandConnections)
            statusInterval = StatusIntervalNanos
        }
        val c = Libbox.newCommandClient(this, options)
        runCatching { c.connect() }.onFailure {
            Log.w(TAG, "command client connect failed", it)
            return
        }
        client = c
        // Connections 是 gobind 代理对象（无 close），直接换新实例，旧对象由 GC 释放 refnum
        synchronized(connectionsLock) {
            connectionState = Libbox.newConnections()
        }
        _connections.value = emptyList()
    }

    /**
     * 带重试的连接：服务可能尚未启动（:core 进程 CommandServer 未就绪），
     * 轮询若干次直到连上或超时。用于点击启动后 / 进入首页时。
     */
    suspend fun connectWithRetry(attempts: Int = 10, delayMs: Long = 800) {
        repeat(attempts) {
            if (_connectedToService.value) return
            connect()
            if (_connectedToService.value) return
            kotlinx.coroutines.delay(delayMs)
        }
    }

    @Synchronized
    fun disconnect() {
        _status.value = DashboardStatus()
        _groups.value = emptyList()
        _connections.value = emptyList()
        _connectedToService.value = false
        val c = client
        client = null
        if (c != null) {
            runCatching { c.disconnect() }
        }
        synchronized(connectionsLock) {
            connectionState = Libbox.newConnections()
        }
    }

    /** 关闭单条连接 */
    fun closeConnection(id: String) {
        runCatching { client?.closeConnection(id) }
            .onFailure { Log.w(TAG, "closeConnection failed", it) }
    }

    /** 关闭全部连接 */
    fun closeAllConnections() {
        runCatching { client?.closeConnections() }
            .onFailure { Log.w(TAG, "closeConnections failed", it) }
    }

    fun selectOutbound(groupTag: String, outboundTag: String) {
        runCatching { client?.selectOutbound(groupTag, outboundTag) }
    }

    fun urlTest(groupTag: String) {
        runCatching { client?.urlTest(groupTag) }
    }

    // ---- CommandClientHandler ----

    override fun connected() {
        _connectedToService.value = true
    }

    override fun disconnected(message: String?) {
        _connectedToService.value = false
        if (!message.isNullOrBlank()) appendLog("[disconnected] $message")
    }

    override fun writeStatus(message: StatusMessage) {
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
        val list = buildList {
            while (iterator.hasNext()) {
                val g: OutboundGroup = iterator.next()
                val items = buildList {
                    val it2 = g.items
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
                    }
                }
                add(
                    ProxyGroup(
                        tag = g.tag,
                        type = g.type,
                        selectable = g.selectable,
                        selected = g.selected,
                        items = items,
                    ),
                )
            }
        }
        _groups.value = list
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
                connectionState.applyEvents(events)
                connectionState.filterState(Libbox.ConnectionStateActive.toInt())
                buildList {
                    val it = connectionState.iterator()
                    while (it.hasNext()) {
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

    private const val StatusIntervalNanos = 500_000_000L // 500ms
    private const val TAG = "SbCommandClient"
}
