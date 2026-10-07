package com.sbai.service

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** :core 发布的 VPN 阶段。遥测连接状态不能作为 VPN 运行真源。 */
object VpnRuntimeState {

    enum class Phase { Stopped, Starting, Running, Stopping, Error }

    // 心跳间隔由服务控制；超过四个 5 秒周期才判定租约失效。
    const val LEASE_TIMEOUT_MS = 20_000L
    const val STALE_MESSAGE = "VPN 服务状态未知（运行状态已过期），请重试"

    private const val PREFS = "sbai_vpn"
    private const val KEY_PHASE = "phase"
    private const val KEY_MESSAGE = "last_error"
    private const val STATE_FILE = "sb-ai-vpn-state.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class FileState(
        val phase: Phase = Phase.Stopped,
        val message: String? = null,
        val publishedAt: Long = 0L,
    )

    private val _phase = MutableStateFlow(Phase.Stopped)
    val phase: StateFlow<Phase> = _phase
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /**
     * 由 :core 进程调用：写入真实阶段。
     *
     * @param publishedAt 写入时刻 ms；UI 侧据此判断租约是否过期。
     */
    fun publish(context: Context, phase: Phase, message: String? = null, publishedAt: Long = System.currentTimeMillis()) {
        publishState(
            dir = context.filesDir,
            prefs = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull(),
            phase = phase,
            message = message,
            nowMs = publishedAt,
        )
    }

    /** 核心发布逻辑（dir/prefs 可注入，JVM 单测直接驱动）。 */
    fun publishState(dir: File, prefs: SharedPreferences?, phase: Phase, message: String? = null, nowMs: Long = System.currentTimeMillis()) {
        _phase.value = phase
        _message.value = message
        runCatching {
            val target = File(dir, STATE_FILE)
            val tmp = File(dir, "$STATE_FILE.tmp")
            tmp.writeText(json.encodeToString(FileState.serializer(), FileState(phase, message, nowMs)))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
        prefs?.let { p ->
            runCatching {
                p.edit().putString(KEY_PHASE, phase.name).putString(KEY_MESSAGE, message).commit()
            }
        }
    }

    /** 纯函数：活动阶段无有效租约时显示可重试的异常，而不是继续声称 VPN 正在运行。 */
    fun observedState(
        phase: Phase,
        message: String?,
        publishedAt: Long,
        nowMs: Long,
    ): Pair<Phase, String?> {
        if (phase != Phase.Starting && phase != Phase.Running && phase != Phase.Stopping) {
            return phase to message
        }
        val age = nowMs - publishedAt
        return if (publishedAt > 0L && age >= 0L && age <= LEASE_TIMEOUT_MS) {
            phase to message
        } else {
            Phase.Error to STALE_MESSAGE
        }
    }

    fun refreshFromDisk(context: Context) {
        refreshState(
            dir = context.filesDir,
            prefs = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull(),
        )
    }

    /** UI 每次刷新都按当前时钟重新判定租约，不向磁盘回写推断结果。 */
    fun refreshState(
        dir: File, prefs: SharedPreferences?, nowMs: Long = System.currentTimeMillis(),
    ) {
        val file = File(dir, STATE_FILE)
        if (file.exists()) {
            val parsed = runCatching {
                json.decodeFromString(FileState.serializer(), file.readText())
            }.getOrNull()
            if (parsed != null) {
                val (phase, message) = observedState(
                    parsed.phase, parsed.message, parsed.publishedAt, nowMs,
                )
                _phase.value = phase
                _message.value = message
                return
            }
        }
        if (prefs == null) return
        val stored = runCatching {
            Phase.valueOf(prefs.getString(KEY_PHASE, Phase.Stopped.name) ?: Phase.Stopped.name)
        }.getOrDefault(Phase.Stopped)
        // 兼容通道没有时间戳：旧的活动阶段无法证明存活，绝不能无限期卡住按钮。
        val (phase, message) = observedState(
            stored, prefs.getString(KEY_MESSAGE, null), 0L, nowMs,
        )
        _phase.value = phase
        _message.value = message
    }

    fun isActive(): Boolean =
        _phase.value == Phase.Starting || _phase.value == Phase.Running

    /** 最大「Starting/Stopping」等待时间（ms）；超过视为卡死，UI 端回退到 Stopped。 */
    const val STUCK_TIMEOUT_MS = 90_000L

    /**
     * UI 侧健壮状态判断：若 phase 是 Starting/Stopping 且发布时间戳已超时，视为已回退到 Stopped。
     * 这防止了服务崩溃/被杀导致 VpnRuntimeState 永远卡在中间态，按钮「没有反应」。
     */
    fun isActuallyActive(lastPublishedAt: Long): Boolean {
        val phase = _phase.value
        if (phase != Phase.Starting && phase != Phase.Running) return false
        // Running 不超时；Starting/Stopping 超时则视为失效
        if (phase == Phase.Running) return true
        return (System.currentTimeMillis() - lastPublishedAt) < STUCK_TIMEOUT_MS
    }

    /** 读取上次发布时间戳（供 UI 做卡死检测）。 */
    fun lastPublishedAt(context: Context): Long =
        runCatching {
            File(context.filesDir, STATE_FILE).takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(FileState.serializer(), it).publishedAt } ?: 0L
        }.getOrDefault(0L)
}
