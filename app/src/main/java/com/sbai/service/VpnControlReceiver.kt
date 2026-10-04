package com.sbai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import com.sbai.data.RuleStore

/**
 * ADB/广播控制入口（参考 AsteriskBOX 广播控制）。
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
        const val EXTRA_FORCE = "extra_force"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_VPN -> startVpn(context, intent)
            ACTION_STOP_VPN -> stopVpn(context)
            ACTION_SUB_UPDATE -> triggerSubUpdate(context)
            ACTION_RESOURCE_UPDATE -> triggerResourceUpdate(context)
            ACTION_STATUS -> Log.i(TAG, "status query: running=${SbAiVpnService.status.value is SbAiVpnService.ServiceStatus.Running}")
            else -> Log.w(TAG, "unknown action: ${intent.action}")
        }
    }

    private fun triggerResourceUpdate(context: Context) {
        Log.i(TAG, "RESOURCE_UPDATE")
        val store = RuleStore.get(context)
        val resources = store.state.value.settings.resources.filter { it.enabled && it.url.isNotEmpty() }
        if (resources.isEmpty()) return
        val now = System.currentTimeMillis()
        val eligible = resources
            .filter { r ->
                val gap = now - r.lastUpdatedAt
                r.lastUpdatedAt == 0L || gap >= (r.updateIntervalHours.coerceAtLeast(1) * 3600_000L)
            }
            .take(10)
        if (eligible.isEmpty()) return
        val settings = store.state.value.settings
        val ua = settings.subscriptionUserAgent ?: "sb-AI/1.0 (sing-box)"
        @Suppress("UNCHECKED_CAST")
        val trustAll: javax.net.ssl.SSLSocketFactory = run {
            val trustAllTm = arrayOf(
                object : javax.net.ssl.X509TrustManager {
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                }
            ) as Array<javax.net.ssl.X509TrustManager>
            javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trustAllTm, java.security.SecureRandom()) }.socketFactory
        }
        eligible.forEach { res ->
            try {
                val conn = java.net.URL(res.url).openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", ua)
                if (conn is javax.net.ssl.HttpsURLConnection) {
                    conn.sslSocketFactory = trustAll
                    conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (body.isBlank()) throw IllegalStateException("空响应")
                store.update { s ->
                    s.copy(settings = s.settings.copy(
                        resources = s.settings.resources.map { r ->
                            if (r.id == res.id) r.copy(content = body, lastUpdatedAt = System.currentTimeMillis(), lastError = null) else r
                        }
                    ))
                }
                Log.i(TAG, "resource ${res.name}: updated (${body.length} chars)")
            } catch (e: Exception) {
                Log.w(TAG, "resource ${res.name} failed", e)
                store.update { s ->
                    s.copy(settings = s.settings.copy(
                        resources = s.settings.resources.map { r ->
                            if (r.id == res.id) r.copy(lastError = e.message) else r
                        }
                    ))
                }
            }
        }
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
        val service = Intent(context, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_STOP)
        ContextCompat.startForegroundService(context, service)
    }

    private fun triggerSubUpdate(context: Context) {
        Log.i(TAG, "SUB_UPDATE")
        val store = RuleStore.get(context)
        val subs = store.state.value.subscriptions.filter { it.enabled && it.autoUpdate }
        if (subs.isEmpty()) return
        val now = System.currentTimeMillis()
        val eligible = subs
            .filter { s ->
                val gap = now - s.lastUpdatedAt
                s.lastUpdatedAt == 0L || gap >= (s.updateIntervalHours.coerceAtLeast(1) * 3600_000L)
            }
            .sortedBy { it.lastUpdatedAt }
            .take(SubscriptionUpdateReceiver.MAX_PER_RUN)
        if (eligible.isEmpty()) return
        val manager = SubscriptionManager(store, context)
        eligible.forEach { sub ->
            try {
                val job = kotlinx.coroutines.runBlocking { manager.refresh(sub) }
                Log.i(TAG, "subscription ${sub.name}: ${if (job is SubscriptionManager.Result.Success) "ok (${job.nodeCount})" else "fail: ${(job as SubscriptionManager.Result.Failure).message}"}")
            } catch (e: Exception) {
                Log.w(TAG, "subscription ${sub.name} failed", e)
            }
        }
    }
}
