package com.sbai.service

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/** 通知栏 QS 磁贴：一键启停（三大代理软件交集功能，以 LxBox 为准） */
class SbTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile(SbAiVpnService.status.value)
    }

    override fun onClick() {
        val running = SbAiVpnService.status.value is SbAiVpnService.ServiceStatus.Running
        if (running) {
            stopService()
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                // 未授权：打开主界面引导授权
                startActivityAndCollapse(
                    Intent(this, com.sbai.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                startVpn()
            }
        }
    }

    private fun startVpn() {
        val intent = Intent(this, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopService() {
        val intent = Intent(this, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_STOP)
        startService(intent)
    }

    private fun updateTile(status: SbAiVpnService.ServiceStatus) {
        qsTile?.apply {
            state = when (status) {
                is SbAiVpnService.ServiceStatus.Running -> Tile.STATE_ACTIVE
                is SbAiVpnService.ServiceStatus.Starting, is SbAiVpnService.ServiceStatus.Stopping -> Tile.STATE_UNAVAILABLE
                else -> Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }
}
