package com.sbai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * ADB/广播控制入口（参考 sb-AI 广播控制）。
 *
 * 支持的 action：
 *  - com.sbai.action.START_VPN   — 启动 VPN（需已授权）
 *  - com.sbai.action.STOP_VPN    — 停止 VPN
 *  - com.sbai.action.SUB_UPDATE  — 立即触发一次订阅刷新
 *  - com.sbai.action.STATUS      — 返回当前状态（无实际效果，仅供探测）
 *
 * 额外 extra：
 *  - EXTRA_FORCE（Boolean）: true 时跳过 VpnService.prepare 检查（已授权场景）
 *
 * 典型用法：
 *   adb shell am broadcast -a com.sbai.action.START_VPN -p com.sbai
 *   adb shell am broadcast -a com.sbai.action.STOP_VPN -p com.sbai
 *   adb shell am broadcast -a com.sbai.action.SUB_UPDATE -p com.sbai
 */
class VpnControlReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "VpnControlReceiver"
        const val ACTION_START_VPN = "com.sbai.action.START_VPN"
        const val ACTION_STOP_VPN = "com.sbai.action.STOP_VPN"
        const val ACTION_SUB_UPDATE = "com.sbai.action.SUB_UPDATE"
        const val ACTION_STATUS = "com.sbai.action.STATUS"
        const val ACTION_RESOURCE_UPDATE = "com.sbai.action.RESOURCE_UPDATE"
        const val ACTION_NEXT_NODE = "com.sbai.action.NEXT_NODE"
        const val ACTION_PREV_NODE = "com.sbai.action.PREV_NODE"
        const val EXTRA_FORCE = "extra_force"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_VPN -> startVpn(context, intent)
            ACTION_STOP_VPN -> stopVpn(context)
            ACTION_SUB_UPDATE -> triggerSubUpdate(context)
            ACTION_RESOURCE_UPDATE -> triggerResourceUpdate(context)
            ACTION_NEXT_NODE -> switchNode(forward = true, context = context)
            ACTION_PREV_NODE -> switchNode(forward = false, context = context)
            ACTION_STATUS -> Log.i(TAG, "status query: running=${SbAiVpnService.status.value is SbAiVpnService.ServiceStatus.Running}")
            else -> Log.w(TAG, "unknown action: ${intent.action}")
        }
    }

    /**
     * 通知栏切换节点：找到可切换的代理组（selector/urltest），切换到下一个/上一个节点。
     * 走 SbCommandClient（命令客户端已连接内核时），不影响服务生命周期。
     */
    private fun switchNode(forward: Boolean, context: android.content.Context) {
        // 通知栏/快捷设置触发时，若主进程尚未进入配置流程（如应用刚冷启动），兜底确保 remote 连接可用。
        SbCommandClient.configureRemote(context.applicationContext)
        val groups = SbCommandClient.groups.value
        if (groups.isEmpty()) {
            Log.w(TAG, "switchNode: no groups available (core not connected?)")
            return
        }
        // 优先切 lb-selector / lb / proxy 组（选择器/测速组），找到第一个 selectable 的组
        val candidates = groups.filter { it.selectable && it.items.size > 1 }
            .sortedBy { g ->
                when (g.tag) {
                    "lb-selector" -> 0
                    "proxy" -> 1
                    "lb" -> 2
                    else -> 3
                }
            }
        val group = candidates.firstOrNull() ?: run {
            Log.w(TAG, "switchNode: no selectable group with >1 item")
            return
        }
        val items = group.items.map { NodeSwitcher.Item(it.tag, it.type != "urltest" && it.type != "selector") }
        val target = NodeSwitcher.nextTag(items, group.selected, forward)
        if (target == null) {
            Log.w(TAG, "switchNode: no target to switch to")
            return
        }
        Log.i(TAG, "switchNode: ${group.tag} ${group.selected} → $target (${if (forward) "next" else "prev"})")
        SbCommandClient.selectOutbound(group.tag, target)
    }

    private fun triggerResourceUpdate(context: Context) {
        Log.i(TAG, "RESOURCE_UPDATE")
        UpdateWorkScheduler.enqueueResourceUpdate(context)
    }

    private fun startVpn(context: Context, intent: Intent) {
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        Log.i(TAG, "START_VPN force=$force")
        if (!force) {
            val prep = VpnService.prepare(context)
            if (prep != null) {
                Log.w(TAG, "VPN permission not granted, need user interaction")
                return
            }
        }
        val service = Intent(context, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_START)
        ContextCompat.startForegroundService(context, service)
    }

    private fun stopVpn(context: Context) {
        Log.i(TAG, "STOP_VPN")
        // Stop an existing instance without creating a new foreground service.
        context.stopService(Intent(context, SbAiVpnService::class.java))
    }

    private fun triggerSubUpdate(context: Context) {
        Log.i(TAG, "SUB_UPDATE")
        UpdateWorkScheduler.enqueueSubscriptionUpdate(context)
    }
}
