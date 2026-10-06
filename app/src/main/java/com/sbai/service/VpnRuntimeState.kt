package com.sbai.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * VPN 服务真实运行阶段的跨进程真源（UI 进程可读）。
 *
 * 背景：
 *  - SbAiVpnService 运行在 :core 进程（AndroidManifest 中 android:process=":core"），
 *    其 status 是进程内 StateFlow，不会跨进程共享，UI 进程读到的可能是初始值。
 *  - SbCommandClient.connectedToService 只表示遥测通道是否连上命令服务端，
 *    不能用来判断 VPN 是否在跑：连接失败不等于服务停止，服务已启动也可能暂未连上。
 *
 * 因此 :core 进程把阶段写入 SharedPreferences（MODE_PRIVATE 对同应用跨进程可读，
 * 这里仅存状态枚举和错误消息，不存订阅 URL 或凭据），UI 进程读取并镜像到 StateFlow。
 */
object VpnRuntimeState {

    enum class Phase { Stopped, Starting, Running, Stopping, Error }

    private const val PREFS = "sbai_vpn"
    private const val KEY_PHASE = "phase"
    private const val KEY_MESSAGE = "last_error"

    private val _phase = MutableStateFlow(Phase.Stopped)
    val phase: StateFlow<Phase> = _phase

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** 由 :core 进程调用：写入真实阶段。 */
    fun publish(context: Context, phase: Phase, message: String? = null) {
        _phase.value = phase
        _message.value = message
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PHASE, phase.name)
                .putString(KEY_MESSAGE, message)
                .apply()
        }
    }

    /** 由 UI 进程调用：读取 :core 发布的最新阶段（刷新内存镜像）。 */
    fun refreshFromDisk(context: Context) {
        val prefs = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        val stored = runCatching {
            Phase.valueOf(prefs.getString(KEY_PHASE, Phase.Stopped.name) ?: Phase.Stopped.name)
        }.getOrDefault(Phase.Stopped)
        _phase.value = stored
        _message.value = prefs.getString(KEY_MESSAGE, null)
    }

    fun isActive(): Boolean =
        _phase.value == Phase.Starting || _phase.value == Phase.Running
}
