package com.sbai.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import kotlinx.coroutines.CancellationException
import androidx.work.WorkerParameters
import com.sbai.data.RuleStore

/**
 * 订阅后台更新 Worker。
 *
 * 用于替代 BroadcastReceiver 中的 runBlocking，避免阻塞主线程。
 * 由 SubscriptionUpdateReceiver 或 VpnControlReceiver 触发。
 */
class SubUpdateWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "SubUpdateWork"
        const val MAX_PER_RUN = 10
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "starting subscription update work")
        val store = RuleStore.get(applicationContext)
        val subs = store.state.value.subscriptions.filter { it.enabled && it.autoUpdate }
        if (subs.isEmpty()) {
            Log.i(TAG, "no subscriptions to update")
            return Result.success()
        }
        
        val now = System.currentTimeMillis()
        val eligible = subs
            .filter { s ->
                val gap = now - s.lastUpdatedAt
                s.lastUpdatedAt == 0L || gap >= (s.updateIntervalHours.coerceAtLeast(1) * 3600_000L)
            }
            .sortedBy { it.lastUpdatedAt }
            .take(MAX_PER_RUN)
        
        if (eligible.isEmpty()) {
            Log.i(TAG, "no eligible subscriptions")
            return Result.success()
        }
        
        val manager = SubscriptionManager(store, applicationContext)
        var successCount = 0
        var failCount = 0
        
        eligible.forEach { sub ->
            try {
                val job = manager.refresh(sub)
                when (job) {
                    is SubscriptionManager.Result.Success -> {
                        Log.i(TAG, "subscription ${sub.name}: ok (${job.nodeCount} nodes)")
                        successCount++
                    }
                    is SubscriptionManager.Result.Failure -> {
                        Log.w(TAG, "subscription ${sub.name}: fail - ${job.message}")
                        failCount++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "subscription update failed (${e.javaClass.simpleName})")
                failCount++
            }
        }
        
        Log.i(TAG, "update work completed: $successCount success, $failCount failed")
        return if (failCount == eligible.size) Result.failure() else Result.success()
    }
}
