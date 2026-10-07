package com.sbai.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.sbai.data.RuleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通知栏 QS 磁贴：一键启停。
 *
 * 关键：磁贴运行在 **UI 进程**，而 SbAiVpnService 运行在 **:core 进程**——
 * 服务的进程内 StateFlow（SbAiVpnService.status）在 UI 进程永远读到初始值 Stopped，
 * 旧实现据此判断 running 恒为 false：VPN 运行时磁贴仍显示未激活、点击只会重复启动，
 * 停止功能彻底失效（P0）。现改用跨进程真源 VpnRuntimeState（文件通道，UI 进程可刷新）。
 */
class SbTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshAndUpdate()
    }

    override fun onClick() {
        // 同步刷一次文件通道（小 JSON 文件读取，<1ms），拿到 :core 发布的真实阶段
        VpnRuntimeState.refreshFromDisk(applicationContext)
        val phase = VpnRuntimeState.phase.value
        when (phase) {
            VpnRuntimeState.Phase.Running,
            VpnRuntimeState.Phase.Starting,
            VpnRuntimeState.Phase.Stopping -> {
                // 运行中/过渡态：请求停止（过渡态点击 = 用户想退出，同样走停止）
                stopService()
            }
            else -> {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    // 未授权：打开主界面引导授权
                    val intent = Intent(this, com.sbai.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // Android 14+：startActivityAndCollapse(Intent) 已废弃且会抛异常，改用 PendingIntent 重载
                        val pi = PendingIntent.getActivity(
                            this,
                            0,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        startActivityAndCollapse(pi)
                    } else {
                        @Suppress("DEPRECATION")
                        @SuppressLint("StartActivityAndCollapseDeprecated")
                        startActivityAndCollapse(intent)
                    }
                } else {
                    startVpn()
                }
            }
        }
    }

    private fun refreshAndUpdate() {
        VpnRuntimeState.refreshFromDisk(applicationContext)
        updateTile()
    }

    private fun startVpn() {
        val intent = Intent(this, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopService() {
        val intent = Intent(this, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_STOP)
        startService(intent)
    }

    private fun updateTile() {
        val phase = VpnRuntimeState.phase.value
        qsTile?.apply {
            state = when (phase) {
                VpnRuntimeState.Phase.Running -> Tile.STATE_ACTIVE
                VpnRuntimeState.Phase.Starting,
                VpnRuntimeState.Phase.Stopping -> Tile.STATE_UNAVAILABLE
                else -> Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
