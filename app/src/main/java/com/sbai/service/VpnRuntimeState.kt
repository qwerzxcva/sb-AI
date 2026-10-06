package com.sbai.service

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    private const val STATE_FILE = "sb-ai-vpn-state.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class FileState(
        val phase: Phase = Phase.Stopped,
        val message: String? = null,
        /** 发布方（:core）的写入时刻 */
        val publishedAt: Long = 0L,
    )

    private val _phase = MutableStateFlow(Phase.Stopped)
    val phase: StateFlow<Phase> = _phase

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /**
     * 由 :core 进程调用：写入真实阶段。
     *
     * 双通道发布：
     *  1) filesDir JSON 文件（主通道，原子写：.tmp + rename）——UI 进程每次 refreshFromDisk
     *     都读到磁盘最新值，不受「各进程 SharedPreferences 内存缓存」问题影响。
     *     若只用 SharedPreferences，UI 进程一旦提前打开过该 prefs，:core 的 commit
     *     对 UI 侧完全不可见（P0：启动失败但 UI 一直显示旧状态「已停止」）。
     *  2) SharedPreferences（兼容通道，同进程内仍即时生效；老版本 UI 的回退读取路径）。
     */
    fun publish(context: Context, phase: Phase, message: String? = null) {
        publishState(
            dir = context.filesDir,
            prefs = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull(),
            phase = phase,
            message = message,
        )
    }

    /** 核心发布逻辑（dir/prefs 可注入，JVM 单测直接驱动）。 */
    fun publishState(dir: File, prefs: SharedPreferences?, phase: Phase, message: String? = null) {
        _phase.value = phase
        _message.value = message
        runCatching {
            val target = File(dir, STATE_FILE)
            val tmp = File(dir, "$STATE_FILE.tmp")
            tmp.writeText(json.encodeToString(FileState.serializer(), FileState(phase, message, System.currentTimeMillis())))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
        prefs?.let { p ->
            runCatching {
                p.edit()
                    .putString(KEY_PHASE, phase.name)
                    .putString(KEY_MESSAGE, message)
                    .commit()
            }
        }
    }

    /**
     * 由 UI 进程调用：读取 :core 发布的最新阶段（刷新内存镜像）。
     * 优先读 JSON 文件（跨进程永远最新）；文件不存在/损坏时回退旧 SharedPreferences。
     */
    fun refreshFromDisk(context: Context) {
        refreshState(
            dir = context.filesDir,
            prefs = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull(),
        )
    }

    /** 核心刷新逻辑（dir/prefs 可注入，JVM 单测直接驱动）。 */
    fun refreshState(dir: File, prefs: SharedPreferences?) {
        val file = File(dir, STATE_FILE)
        if (file.exists()) {
            val parsed = runCatching {
                json.decodeFromString(FileState.serializer(), file.readText())
            }.getOrNull()
            if (parsed != null) {
                _phase.value = parsed.phase
                _message.value = parsed.message
                return
            }
        }
        if (prefs == null) return
        val stored = runCatching {
            Phase.valueOf(prefs.getString(KEY_PHASE, Phase.Stopped.name) ?: Phase.Stopped.name)
        }.getOrDefault(Phase.Stopped)
        _phase.value = stored
        _message.value = prefs.getString(KEY_MESSAGE, null)
    }

    fun isActive(): Boolean =
        _phase.value == Phase.Starting || _phase.value == Phase.Running
}
