package com.sbai.service

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 跨进程 VPN 运行态。
 *
 * 背景：SbAiVpnService 跑在独立的 `:core` 进程，它的 `_status` StateFlow 是进程内的，
 * UI 进程读到的恒为 Stopped。此前 UI 只能以 CommandClient 是否连上 CommandServer
 * （coreConnected）当作运行真源——但那是遥测通道，握手有延迟也可能失败，
 * 会出现「VPN 实际在跑，界面显示已停止」，用户再点一次启动。
 *
 * 这里用 filesDir 下的一个小文件做跨进程状态通道：:core 在状态变迁时写，
 * UI 轮询读。文件极小的 JSON 不需要 SharedPreferences（跨进程无失效问题），
 * 写盘用 tmp+rename 保证 UI 不会读到半截内容。
 */
object VpnRuntimeState {

    private const val FILE_NAME = "vpn_state.json"

    /** 与 SbAiVpnService.ServiceStatus 对应的可序列化状态。 */
    enum class Phase { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    data class Snapshot(
        val phase: Phase,
        val message: String = "",
        val sinceMs: Long = 0L,
    )

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    /** :core 进程调用：发布当前状态。 */
    fun publish(context: Context, phase: Phase, message: String = "", sinceMs: Long = 0L) {
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE_NAME.${android.os.Process.myPid()}.tmp")
            tmp.writeText(
                """{"phase":"${phase.name}","message":"${escape(message)}","since":$sinceMs}""",
            )
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "publish failed", it) }
    }

    /** UI 进程调用：读取最新状态；文件不存在或解析失败返回 null（调用方自行兜底）。 */
    fun read(context: Context): Snapshot? {
        val f = file(context)
        if (!f.exists()) return null
        return runCatching {
            val text = f.readText()
            val phase = Regex("\"phase\"\\s*:\\s*\"([A-Z]+)\"")
                .find(text)?.groupValues?.get(1)
                ?: return null
            val message = Regex("\"message\"\\s*:\\s*\"(.*?)\"")
                .find(text)?.groupValues?.get(1)
                ?.let { unescape(it) }
                .orEmpty()
            val since = Regex("\"since\"\\s*:\\s*(\\d+)")
                .find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            Snapshot(Phase.valueOf(phase), message, since)
        }.getOrNull()
    }

    /** UI 进程调用：清掉残留状态（如启动失败后复位）。 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun unescape(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\")

    private const val TAG = "VpnRuntimeState"
}
