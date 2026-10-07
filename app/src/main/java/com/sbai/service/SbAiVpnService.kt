package com.sbai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.sbai.MainActivity
import com.sbai.R
import com.sbai.data.RuleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * sb-AI VPN 前台服务：
 * 读取 RuleStore 中的规则状态 → 生成 sing-box 配置 → 通过 libbox CommandServer 启动。
 */
class SbAiVpnService : VpnService() {

    sealed interface ServiceStatus {
        data object Stopped : ServiceStatus
        data object Starting : ServiceStatus
        data object Running : ServiceStatus
        data object Stopping : ServiceStatus
        data class Error(val message: String) : ServiceStatus
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Native start/stop may synchronously call back into stopVpn: never hold requestLock
    // across native calls. The mutex serializes resource ownership on IO instead.
    private val lifecycleMutex = kotlinx.coroutines.sync.Mutex()
    private val requestLock = Any()
    private var startRequested = false
    private var stopRequested = false
    private var destroyed = false
    private var runtime: LibboxServiceRuntime? = null
    private var platformInterface: SbPlatformInterface? = null

    private fun canStart(): Boolean = synchronized(requestLock) {
        startRequested && !stopRequested && !destroyed
    }

    // Called only while holding lifecycleMutex, including partial-start failures.
    private fun cleanup() {
        val rt = runtime
        val platform = platformInterface
        runtime = null
        platformInterface = null
        runCatching { SbCommandClient.disconnect() }
            .onFailure { Log.w(TAG, "command client cleanup failed", it) }
        try {
            runCatching { rt?.stop() }
                .onFailure { Log.w(TAG, "runtime cleanup failed", it) }
        } finally {
            runCatching { platform?.close() }
                .onFailure { Log.w(TAG, "platform cleanup failed", it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        // 系统重建时不自动恢复（VPN 需要用户授权上下文），避免无通知的僵尸服务
        return START_NOT_STICKY
    }

    private fun startVpn() {
        synchronized(requestLock) {
            if (startRequested || stopRequested || destroyed) return
            startRequested = true
            _status.value = ServiceStatus.Starting
            // 写当前时间点，方便 UI 做超时恢复
            VpnRuntimeState.publish(this, VpnRuntimeState.Phase.Starting, null, System.currentTimeMillis())
            try {
                startForegroundWithNotification()
            } catch (t: Throwable) {
                startRequested = false
                stopRequested = true
                _status.value = ServiceStatus.Error(t.message ?: "unknown")
                persistError(t.message ?: t.javaClass.simpleName)
                VpnRuntimeState.publish(this, VpnRuntimeState.Phase.Error, t.message ?: t.javaClass.simpleName, System.currentTimeMillis())
                stopSelf()
                return
            }
        }

        scope.launch {
            lifecycleMutex.withLock {
                var running = false
                var failed = false
                try {
                    // canStart=false 时不再静默 return@withLock，一律走错误分支，
                    // 保证 VpnRuntimeState 永远能落到 Stopped/Error，不会卡死在 Starting。
                    if (!canStart()) {
                        Log.w(TAG, "startVpn: cancelled before setup (stop/destroy requested)")
                        // 已在 startRequested 重置阶段被 requestStop 设置；此处兜底确保状态机终态化。
                        synchronized(requestLock) {
                            if (_status.value !is ServiceStatus.Error) {
                                _status.value = ServiceStatus.Stopped
                            }
                        }
                        VpnRuntimeState.publish(this@SbAiVpnService, VpnRuntimeState.Phase.Stopped, null, System.currentTimeMillis())
                        return@withLock
                    }
                    Log.i(TAG, "startVpn: calling LibboxRuntime.setup")
                    LibboxRuntime.setup(this@SbAiVpnService)
                    Log.i(TAG, "startVpn: LibboxRuntime.setup completed")

                    // :core 进程的 RuleStore 是首次构造时的磁盘快照，必须 reload 才能拿到 UI 刚改的配置
                    val store = RuleStore.get(this@SbAiVpnService)
                    var state = store.reload()
                    Log.i(TAG, "startVpn: state reloaded, subscriptions=${state.subscriptions.size}")

                    // 配置生成 + 校验 + 自动禁用坏节点重试（有限轮次）。
                    // 参考 LxBox 009：内核拒绝的节点自动禁用，用剩余好节点让 VPN 起来。
                    var config = SingBoxConfigGenerator.generate(state)
                    var disabledAny = false
                    for (round in 0 until NodeAutoDisabler.MAX_ROUNDS) {
                        // 同样的：canStart 失败走明确终态，不静默消失
                        if (!canStart()) {
                            Log.w(TAG, "startVpn: cancelled during round $round auto-disable")
                            synchronized(requestLock) {
                                if (_status.value !is ServiceStatus.Error) {
                                    _status.value = ServiceStatus.Stopped
                                }
                            }
                            VpnRuntimeState.publish(this@SbAiVpnService, VpnRuntimeState.Phase.Stopped, null, System.currentTimeMillis())
                            return@withLock
                        }
                        val err = SingBoxConfigGenerator.validate(config)
                        if (err == null) break
                        Log.w(TAG, "startVpn: round $round config invalid: $err")
                        val next = NodeAutoDisabler.disableRejectedNode(state, err)
                        if (next == null) {
                            error("配置校验失败: $err")
                        }
                        store.updateCommitted { next }
                        state = next
                        disabledAny = true
                        config = SingBoxConfigGenerator.generate(state)
                    }
                    if (!canStart()) {
                        Log.w(TAG, "startVpn: cancelled before final config validate")
                        synchronized(requestLock) {
                            if (_status.value !is ServiceStatus.Error) {
                                _status.value = ServiceStatus.Stopped
                            }
                        }
                        VpnRuntimeState.publish(this@SbAiVpnService, VpnRuntimeState.Phase.Stopped, null, System.currentTimeMillis())
                        return@withLock
                    }
                    SingBoxConfigGenerator.validate(config)?.let { msg ->
                        Log.e(TAG, "startVpn: config still invalid after auto-disable: $msg")
                        error("配置校验失败: $msg")
                    }
                    if (disabledAny) {
                        Log.i(TAG, "startVpn: auto-disabled bad nodes, proceeding with remaining")
                    }
                    Log.i(TAG, "startVpn: config generated, length=${config.length}")

                    val configFile = LibboxRuntime.configFile(this@SbAiVpnService)
                    configFile.parentFile?.mkdirs()
                    configFile.writeText(config)
                    Log.i(TAG, "startVpn: config written to ${configFile.absolutePath}")

                    if (!canStart()) {
                        Log.w(TAG, "startVpn: cancelled before writing config")
                        synchronized(requestLock) {
                            if (_status.value !is ServiceStatus.Error) {
                                _status.value = ServiceStatus.Stopped
                            }
                        }
                        VpnRuntimeState.publish(this@SbAiVpnService, VpnRuntimeState.Phase.Stopped, null, System.currentTimeMillis())
                        return@withLock
                    }
                    Log.i(TAG, "startVpn: creating platform interface")
                    val platform = SbPlatformInterface(this@SbAiVpnService)
                    platformInterface = platform
                    Log.i(TAG, "startVpn: creating runtime")
                    val rt = LibboxServiceRuntime(platform) { stopVpn() }
                    runtime = rt // Own partially initialized resources before native start.
                    Log.i(TAG, "startVpn: starting runtime")
                    rt.start(config)
                    Log.i(TAG, "startVpn: runtime started successfully")

                    if (!canStart()) {
                        Log.w(TAG, "startVpn: cancelled after rt.start returns")
                        synchronized(requestLock) {
                            if (_status.value !is ServiceStatus.Error) {
                                _status.value = ServiceStatus.Stopped
                            }
                        }
                        VpnRuntimeState.publish(this@SbAiVpnService, VpnRuntimeState.Phase.Stopped, null, System.currentTimeMillis())
                        return@withLock
                    }
                    runCatching { SbCommandClient.connect() }
                        .onFailure { Log.w(TAG, "command client unavailable", it) }

                    synchronized(requestLock) {
                        // Stop invalidates publication immediately, even if native start blocks.
                        if (startRequested && !stopRequested && !destroyed) {
                            persistError(null)
                            updateNotification(getString(R.string.vpn_notification_title))
                            _status.value = ServiceStatus.Running
                            VpnRuntimeState.publish(this@SbAiVpnService, VpnRuntimeState.Phase.Running, null, System.currentTimeMillis())
                            running = true
                            Log.i(TAG, "startVpn: VPN running")
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "failed to start vpn", t)
                    synchronized(requestLock) {
                        if (!stopRequested && !destroyed) {
                            startRequested = false
                            stopRequested = true
                            _status.value = ServiceStatus.Error(t.message ?: "unknown")
                            persistError(t.message ?: t.javaClass.simpleName)
                            VpnRuntimeState.publish(this@SbAiVpnService, VpnRuntimeState.Phase.Error, t.message ?: t.javaClass.simpleName, System.currentTimeMillis())
                            failed = true
                        }
                    }
                } finally {
                    if (!running) cleanup()
                    if (failed) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    /** 把启动错误写入 SharedPreferences（commit 同步），供 UI 进程读取展示 */
    private fun persistError(msg: String?) {
        runCatching {
            getSharedPreferences("sbai_vpn", MODE_PRIVATE).edit()
                .putString("last_error", msg)
                .commit()
        }
    }

    private fun stopVpn() = requestStop()

    private fun requestStop(destroying: Boolean = false) {
        synchronized(requestLock) {
            if (destroying) destroyed = true
            if (stopRequested && !destroying) return
            stopRequested = true
            startRequested = false
            if (_status.value !is ServiceStatus.Error) {
                _status.value = ServiceStatus.Stopping
                // 写时间戳；UI 侧用「Starting 超过 90s 视为卡死」做兜底
                VpnRuntimeState.publish(this, VpnRuntimeState.Phase.Stopping, null, System.currentTimeMillis())
            }
        }
        scope.launch {
            try {
                lifecycleMutex.withLock {
                    cleanup()
                    synchronized(requestLock) {
                        if (_status.value !is ServiceStatus.Error) {
                            _status.value = ServiceStatus.Stopped
                        }
                        // 无论 destroying 与否，stop 完成都写 Stopped
                        VpnRuntimeState.publish(this@SbAiVpnService, VpnRuntimeState.Phase.Stopped, null, System.currentTimeMillis())
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (!destroying) stopSelf()
                    // 注意：destroying 路径下 stopSelf() 由系统或 UI 另起 startVpn 触发；
                    // 不在此处再次调用，避免 startVpn 和 onDestroy 并发的锁竞争。
                }
            } catch (t: Throwable) {
                // cleanup 失败也兜底写状态，不让 phase 卡死
                Log.e(TAG, "requestStop failed", t)
                runCatching {
                    VpnRuntimeState.publish(this@SbAiVpnService, VpnRuntimeState.Phase.Error,
                        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName, System.currentTimeMillis())
                }
            }
            // 不再 scope.cancel()：销毁场景下原 startVpn 协程在 lifecycleMutex 释放后会被 GC 回收；
            // 若这里强制 cancel，可能把正在执行的 rt.stop()/platform.close() 中断，造成 native 资源泄漏。
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.vpn_notification_title)))
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SbAiVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 通知栏切换节点（prev / next），走广播：接收方在 UI 进程读写命令客户端
        val prevIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(VpnControlReceiver.ACTION_PREV_NODE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nextIntent = PendingIntent.getBroadcast(
            this,
            3,
            Intent(VpnControlReceiver.ACTION_NEXT_NODE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_qs_proxy)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "上一个", prevIntent).build())
            .addAction(Notification.Action.Builder(null, "下一个", nextIntent).build())
            .addAction(
                Notification.Action.Builder(null, getString(R.string.action_stop), stopIntent).build(),
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        requestStop(destroying = true)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.sbai.action.START"
        const val ACTION_STOP = "com.sbai.action.STOP"
        private const val CHANNEL_ID = "sb_ai_vpn"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SbAiVpnService"

        private val _status = MutableStateFlow<ServiceStatus>(ServiceStatus.Stopped)
        val status: StateFlow<ServiceStatus> = _status
    }
}
