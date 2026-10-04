package com.sbai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sbai.data.RuleStore
import kotlinx.coroutines.runBlocking

/**
 * 定时/重复性订阅更新入口。
 *
 * 由 WorkManager 在后台触发（WorkManager 本身由 app startup 初始化）。
 * 每次触发时遍历 RuleStore 中所有 enabled + autoUpdate 的订阅，
 * 按 updateIntervalHours 冷却期过滤后逐个刷新。
 *
 * 与 sb-AI 的 PeriodicSyncWorker 对标：
 *  - 最短冷却 15 min，防止频繁请求触发机场限流；
 *  - 每轮最多 10 个，避免一次性打爆网络；
 *  - 失败只写 lastError，不影响其他订阅。
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
        val store = RuleStore.get(context)
        val subs = store.state.value.subscriptions.filter { it.enabled && it.autoUpdate }
        if (subs.isEmpty()) return
        val now = System.currentTimeMillis()
        val eligible = subs
            .filter { s ->
                val gap = now - s.lastUpdatedAt
                s.lastUpdatedAt == 0L || gap >= (s.updateIntervalHours.coerceAtLeast(1) * 3600_000L)
            }
            .sortedBy { it.lastUpdatedAt } // 先刷最老的
            .take(MAX_PER_RUN)
        if (eligible.isEmpty()) return
        val manager = SubscriptionManager(store, context)
        eligible.forEach { sub ->
            val job = try { runBlocking { manager.refresh(sub) } } catch (e: Exception) {
                SubscriptionManager.Result.Failure(e.message ?: "refresh failed")
            }
            Log.i(TAG, "subscription ${sub.name}: ${if (job is SubscriptionManager.Result.Success) "ok (${job.nodeCount})" else "fail: ${(job as SubscriptionManager.Result.Failure).message}"}")
        }
    }
}
