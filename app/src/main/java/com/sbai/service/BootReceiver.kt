package com.sbai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.sbai.data.RuleStore

/** 开机自启：仅当已授予 VPN 权限且用户开启「开机自启」时启动服务 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = RuleStore.get(context).state.value.settings
        if (!settings.autoStartOnBoot) return
        if (VpnService.prepare(context) != null) return // 未授权，放弃
        val service = Intent(context, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_START)
        ContextCompat.startForegroundService(context, service)
    }
}
