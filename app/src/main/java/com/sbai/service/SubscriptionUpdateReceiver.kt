package com.sbai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 定时/重复性订阅更新入口。
 *
 * 广播只调度 unique work；实际刷新继续由 SubUpdateWork 在后台执行。
 * 与 VPN 控制广播共用 KEEP 去重策略，避免同时刷新同一批订阅。
 */
class SubscriptionUpdateReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "SubUpdateReceiver"
        const val ACTION_SUB_UPDATE = "com.sbai.action.SUB_UPDATE"
        const val MAX_PER_RUN = 10
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SUB_UPDATE) return
        Log.i(TAG, "subscription update tick")
        UpdateWorkScheduler.enqueueSubscriptionUpdate(context)
    }
}
