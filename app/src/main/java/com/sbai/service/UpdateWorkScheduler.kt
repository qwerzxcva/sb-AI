package com.sbai.service

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager

/** Shared broadcast scheduling policy; subscription and resource work have separate identities. */
object UpdateWorkScheduler {
    private const val SUBSCRIPTION_WORK_NAME = "sbai.subscription.update"
    private const val RESOURCE_WORK_NAME = "sbai.resource.update"

    fun enqueueSubscriptionUpdate(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            SUBSCRIPTION_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(SubUpdateWork::class.java).build()
        )
    }

    fun enqueueResourceUpdate(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RESOURCE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(ResourceUpdateWorker::class.java).build()
        )
    }
}
