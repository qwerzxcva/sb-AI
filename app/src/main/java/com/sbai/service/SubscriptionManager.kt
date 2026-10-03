package com.sbai.service

import android.util.Log
import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import com.sbai.data.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 订阅源管理：拉取订阅内容 → 解析分享链接 → 替换该订阅名下节点。
 */
class SubscriptionManager(private val store: RuleStore) {

    sealed interface Result {
        data class Success(val nodeCount: Int) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun refresh(subscription: Subscription): Result = withContext(Dispatchers.IO) {
        try {
            require(subscription.url.startsWith("http://") || subscription.url.startsWith("https://")) {
                "订阅地址必须是 http/https"
            }
            val body = httpGet(subscription.url)
            val parsed = ShareLinkParser.parseSubscription(body)
            if (parsed.isEmpty()) {
                return@withContext fail(subscription, "订阅内容为空或不包含可识别的节点链接")
            }
            val nodes = parsed.map { p ->
                ProxyNode(
                    name = p.name,
                    outboundJson = p.outboundJson,
                    subscriptionId = subscription.id,
                )
            }
            store.replaceSubscriptionNodes(subscription.id, nodes)
            store.upsertSubscription(
                subscription.copy(
                    lastUpdatedAt = System.currentTimeMillis(),
                    lastError = null,
                    nodeCount = nodes.size,
                ),
            )
            Log.i(TAG, "subscription ${subscription.name}: ${nodes.size} nodes")
            Result.Success(nodes.size)
        } catch (t: Throwable) {
            Log.w(TAG, "subscription refresh failed: ${subscription.url}", t)
            fail(subscription, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun fail(subscription: Subscription, message: String): Result.Failure {
        store.upsertSubscription(subscription.copy(lastError = message))
        return Result.Failure(message)
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", UA)
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "SubscriptionManager"
        const val UA = "sb-AI/1.0 (sing-box)"
    }
}
