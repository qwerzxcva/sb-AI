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

/** QS 磁贴与首页读取相同的跨进程 VPN 阶段；不能读取 :core 进程内的 ServiceStatus。 */
class SbTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val phase = withContext(Dispatchers.IO) {
                VpnRuntimeState.refreshFromDisk(applicationContext)
                VpnRuntimeState.phase.value
            }
            updateTile(phase)
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val phase = withContext(Dispatchers.IO) {
                VpnRuntimeState.refreshFromDisk(applicationContext)
                VpnRuntimeState.phase.value
            }
            when (phase) {
                VpnRuntimeState.Phase.Starting, VpnRuntimeState.Phase.Stopping -> updateTile(phase)
                VpnRuntimeState.Phase.Running -> {
                    startService(Intent(this@SbTileService, SbAiVpnService::class.java)
                        .setAction(SbAiVpnService.ACTION_STOP))
                    updateTile(VpnRuntimeState.Phase.Stopping)
                }
                VpnRuntimeState.Phase.Stopped, VpnRuntimeState.Phase.Error -> {
                    if (VpnService.prepare(this@SbTileService) != null) {
                        val intent = Intent(this@SbTileService, com.sbai.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val pi = PendingIntent.getActivity(this@SbTileService, 0, intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            startActivityAndCollapse(pi)
                        } else {
                            @Suppress("DEPRECATION")
                            @SuppressLint("StartActivityAndCollapseDeprecated")
                            startActivityAndCollapse(intent)
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            RuleStore.get(applicationContext).afterPendingWrites {
                                try {
                                    ContextCompat.startForegroundService(applicationContext,
                                        Intent(applicationContext, SbAiVpnService::class.java)
                                            .setAction(SbAiVpnService.ACTION_START))
                                } catch (e: Exception) {
                                    VpnRuntimeState.publish(applicationContext,
                                        VpnRuntimeState.Phase.Error,
                                        "无法启动 VPN 服务（${e.javaClass.simpleName}）")
                                }
                            }
                        }
                        updateTile(VpnRuntimeState.Phase.Starting)
                    }
                }
            }
        }
    }

    private fun updateTile(phase: VpnRuntimeState.Phase) {
        qsTile?.apply {
            state = when (phase) {
                VpnRuntimeState.Phase.Running -> Tile.STATE_ACTIVE
                VpnRuntimeState.Phase.Starting, VpnRuntimeState.Phase.Stopping -> Tile.STATE_UNAVAILABLE
                VpnRuntimeState.Phase.Stopped, VpnRuntimeState.Phase.Error -> Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
