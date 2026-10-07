package com.sbai.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sbai.data.RuleStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Resource refresh is owned by WorkManager, never by the broadcast/main thread. */
class ResourceUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            updateResources()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            // Exceptions may contain URLs or credentials; do not log their message/stack.
            Log.w(TAG, "resource update batch failed")
            Result.failure()
        }
    }

    private suspend fun updateResources(): Result {
        currentCoroutineContext().ensureActive()
        val store = RuleStore.get(applicationContext)
        val settings = store.state.value.settings
        val now = System.currentTimeMillis()
        val eligible = settings.resources
            .filter { it.enabled && it.url.isNotEmpty() }
            .filter { r ->
                r.lastUpdatedAt == 0L ||
                    now - r.lastUpdatedAt >= r.updateIntervalHours.coerceAtLeast(1) * 3600_000L
            }
            .take(10)
        if (eligible.isEmpty()) return Result.success()
        val ua = settings.subscriptionUserAgent.takeIf { it.isNotBlank() } ?: "sb-AI/1.0 (sing-box)"

        var failed = false
        for (res in eligible) {
            currentCoroutineContext().ensureActive()
            var connection: HttpURLConnection? = null
            var safeError = "资源更新失败"
            try {
                val conn = URL(res.url).openConnection() as HttpURLConnection
                connection = conn
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", ua)
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code !in 200..299) {
                    safeError = "HTTP $code"
                    throw IllegalStateException(safeError)
                }
                val jobContext = currentCoroutineContext()
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use {
                    it.readBoundedText(4 * 1024 * 1024) { jobContext.ensureActive() }
                }
                currentCoroutineContext().ensureActive()
                if (body.isBlank()) {
                    safeError = "空响应"
                    throw IllegalStateException(safeError)
                }
                store.update { s ->
                    s.copy(settings = s.settings.copy(
                        resources = s.settings.resources.map { r ->
                            if (r.id == res.id && r.url == res.url) r.copy(
                                content = body,
                                lastUpdatedAt = System.currentTimeMillis(),
                                lastError = null
                            ) else r
                        }
                    ))
                }
                // Resource names are user-controlled and may themselves contain secrets.
                Log.i(TAG, "resource updated (${body.length} chars)")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                failed = true
                Log.w(TAG, "resource update failed")
                store.update { s ->
                    s.copy(settings = s.settings.copy(
                        resources = s.settings.resources.map { r ->
                            if (r.id == res.id && r.url == res.url) r.copy(lastError = safeError) else r
                        }
                    ))
                }
            } finally {
                connection?.disconnect()
            }
        }
        currentCoroutineContext().ensureActive()
        // Do not report partial/total failure as success or retry forever on bad input.
        return if (failed) Result.failure() else Result.success()
    }

    private companion object {
        const val TAG = "ResourceUpdateWorker"
    }
}
