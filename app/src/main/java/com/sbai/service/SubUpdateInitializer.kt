package com.sbai.service

import android.content.Context
import androidx.startup.Initializer
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import com.sbai.service.SubUpdateWorker
import java.util.concurrent.TimeUnit

/**
 * 用 AndroidX Startup 注册订阅自动更新 WorkManager task。
 *
 * 参考 sb-AI：App 启动时把周期性任务 enqueue 到 WorkManager，
 * 由系统按需调度（Doze / 重启后自动恢复）。
 *
 * 周期 5h，最小允许间隔 15min；初值延迟 1h，避开冷启动窗口。
 */
class SubUpdateInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        val work = PeriodicWorkRequestBuilder<SubUpdateWorker>(
            repeatInterval = 5,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 15,
            flexTimeIntervalUnit = TimeUnit.MINUTES,
        ).build()
        androidx.work.WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "sb-subscription-update",
                ExistingPeriodicWorkPolicy.KEEP,
                work,
            )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
