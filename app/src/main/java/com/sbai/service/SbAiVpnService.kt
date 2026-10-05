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
    private var runtime: LibboxServiceRuntime? = null
    private var platformInterface: SbPlatformInterface? = null

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
        // 仅允许从 Stopped / Error 进入启动流程，防止重入创建第二个 CommandServer。
        // 注意：_status 是本进程（:core）内的 StateFlow，不跨进程共享；
        // 但同一个 :core 进程内多次 startService 仍会走到这里，因此这个守卫是必要的。
        if (runtime != null) {
            Log.i(TAG, "startVpn: runtime already exists, reloading config instead")
            return
        }
        when (_status.value) {
            ServiceStatus.Running, ServiceStatus.Starting, ServiceStatus.Stopping -> return
            else -> Unit
        }
        Log.i(TAG, "startVpn: current status=${_status.value}")
        _status.value = ServiceStatus.Starting
        startForegroundWithNotification()

        scope.launch {
            try {
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
                repeat(NodeAutoDisabler.MAX_ROUNDS) { round ->
                    val err = SingBoxConfigGenerator.validate(config)
                    if (err == null) return@repeat  // 校验通过，跳出循环
                    Log.w(TAG, "startVpn: round $round config invalid: $err")
                    // 尝试从错误中定位坏节点并禁用
                    val next = NodeAutoDisabler.disableRejectedNode(state, err)
                    if (next == null) {
                        // 错误无法归因到具体节点（可能是路由/DNS 配置错误），保留原始错误
                        error("配置校验失败: $err")
                    }
                    // 禁用成功后落盘并重新生成配置再校验
                    store.updateCommitted { next }
                    state = next
                    disabledAny = true
                    config = SingBoxConfigGenerator.generate(state)
                }
                // 循环结束后必须最终校验一次（repeat 里 return@repeat 跳过时已通过；
                // 若跑满轮次仍有坏节点，最后一次生成的 config 可能是坏的，这里兜底拦截）
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

                Log.i(TAG, "startVpn: creating platform interface")
                val platform = SbPlatformInterface(this@SbAiVpnService)
                Log.i(TAG, "startVpn: creating runtime")
                val rt = LibboxServiceRuntime(platform) { stopVpn() }
                Log.i(TAG, "startVpn: starting runtime")
                rt.start(config)
                Log.i(TAG, "startVpn: runtime started successfully")

                runCatching { SbCommandClient.connect() }
                    .onFailure { Log.w(TAG, "command client unavailable", it) }

                platformInterface = platform
                runtime = rt
                _status.value = ServiceStatus.Running
                persistError(null)   // 启动成功，清除旧错误
                updateNotification(getString(R.string.vpn_notification_title))
                Log.i(TAG, "startVpn: VPN running")
            } catch (t: Throwable) {
                Log.e(TAG, "failed to start vpn", t)
                _status.value = ServiceStatus.Error(t.message ?: "unknown")
                persistError(t.message ?: t.javaClass.simpleName)   // 跨进程传给 UI 显示
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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

    private fun stopVpn() {
        _status.value = ServiceStatus.Stopping
        scope.launch {
            SbCommandClient.disconnect()
            runCatching { runtime?.stop() }
            runtime = null
            platformInterface = null
            _status.value = ServiceStatus.Stopped
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        // 服务被系统销毁/回收时，必须复位共享状态：
        // 否则 _status 停留在 Running，重入守卫会让用户永远无法再次启动。
        SbCommandClient.disconnect()
        runCatching { runtime?.stop() }
        runtime = null
        platformInterface = null
        _status.value = ServiceStatus.Stopped
        scope.cancel()
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
