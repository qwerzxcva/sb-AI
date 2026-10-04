package com.sbai.service

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sbai.data.RuleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 周期性订阅更新 Worker（参考 sb-AI PeriodicSyncWorker）。
 *
 * 由 AppStartup 注册为 PeriodicWorkRequest（5h 周期，最小 15min 偏移），
 * 每次触发时向 SubscriptionUpdateReceiver 发广播，由 receiver 执行实际刷新。
 *
 * 设计选择：
 *  - 不在 Worker 里直接调 SubscriptionManager，保持 receiver 作为统一入口，
 *    方便后面扩展 ManualTrigger、BootReceiver 等场景复用。
 *  - 每轮最多刷新 10 个订阅（见 SubscriptionUpdateReceiver.MAX_PER_RUN）。
 */
class SubUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val intent = Intent(applicationContext, SubscriptionUpdateReceiver::class.java)
                .setAction(SubscriptionUpdateReceiver.ACTION_SUB_UPDATE)
            applicationContext.sendBroadcast(intent)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w(SubscriptionUpdateReceiver.TAG, "SubUpdateWorker failed", e)
            Result.retry()
        }
    }
}
