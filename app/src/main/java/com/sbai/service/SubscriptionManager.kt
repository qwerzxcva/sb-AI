package com.sbai.service

import android.util.Log
import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import com.sbai.data.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 订阅源管理：拉取订阅内容 → 解析分享链接 → 按关键字过滤 → 替换该订阅名下节点。
 *
 * 增强（参考 Throne/AsteriskBOX/NekoBoxPlus 的订阅处理）：
 *  - 自定义 User-Agent（机场常按 UA 返回不同格式）
 *  - 仅允许 https
 *  - 响应体 4MB 上限（防止 gzip 炸弹/超大订阅撑爆内存）
 *  - 解析 subscription-userinfo 响应头（upload/download/total/expire）
 */
class SubscriptionManager(private val store: RuleStore) {

    sealed interface Result {
        data class Success(val nodeCount: Int) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun refresh(subscription: Subscription): Result = withContext(Dispatchers.IO) {
        try {
            require(subscription.url.startsWith("https://")) {
                "订阅地址必须使用 https"
            }
            val settings = store.state.value.settings
            val (body, userinfo) = httpGet(subscription.url, subscription.userAgent, settings)

            var parsed = ShareLinkParser.parseSubscription(body)
            if (parsed.isEmpty()) {
                return@withContext fail(subscription, "订阅内容为空或不包含可识别的节点链接")
            }

            // 关键字过滤（include：命中才要；exclude：命中即弃）
            val include = subscription.includeKeyword.trim()
            val exclude = subscription.excludeKeyword.trim()
            if (include.isNotEmpty() || exclude.isNotEmpty()) {
                parsed = parsed.filter { n ->
                    val hitInclude = include.isEmpty() || include.split(Regex("\\s+"))
                        .filter { it.isNotBlank() }.any { n.name.contains(it, true) }
                    val hitExclude = exclude.isNotEmpty() && exclude.split(Regex("\\s+"))
                        .filter { it.isNotBlank() }.any { n.name.contains(it, true) }
                    hitInclude && !hitExclude
                }
            }

            // Throne SubscriptionOptions 基准：后处理
            if (subscription.removeInsecure) {
                parsed = parsed.filter { !isInsecureNode(it.outboundJson) }
            }
            if (parsed.isEmpty()) {
                return@withContext fail(subscription, "过滤后无剩余节点")
            }
            val nodes = (if (subscription.removeDuplicates) {
                // 去重：同名节点保留第一个
                parsed.distinctBy { it.name }
            } else {
                parsed
            }).map { p ->
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
                    trafficUpload = userinfo.upload,
                    trafficDownload = userinfo.download,
                    trafficTotal = userinfo.total,
                    trafficExpire = userinfo.expire,
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

    /** 判定不安全节点：无加密手段的明文代理（ss 无密码 / trojan 无 tls / http 明文） */
    private fun isInsecureNode(outboundJson: String): Boolean = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(outboundJson)
            .jsonObject
        when (obj["type"]?.jsonPrimitive?.content) {
            "trojan" -> obj["tls"]?.jsonObject?.get("enabled")?.jsonPrimitive?.content != "true"
            "shadowsocks" -> obj["password"]?.jsonPrimitive?.content.isNullOrBlank()
            "http" -> true   // 明文 http 代理视为不安全
            else -> false
        }
    }.getOrDefault(false)

    private data class TrafficInfo(
        val upload: Long = 0, val download: Long = 0,
        val total: Long = 0, val expire: Long = 0,
    )

    private fun httpGet(
        url: String,
        userAgent: String?,
        settings: com.sbai.data.AppSettings,
    ): Pair<String, TrafficInfo> {
        // 禁用自动重定向：https 订阅可被 302 降级到 http 明文（节点凭据泄露面）。
        // 手动跟随且只允许 https 目标。
        var current = url
        repeat(MAX_REDIRECTS) { hop ->
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                // UA：订阅级 override > 全局 override > 品牌 UA
                val ua = userAgent?.takeIf { it.isNotBlank() }
                    ?: settings.subscriptionUserAgent.takeIf { it.isNotBlank() }
                    ?: UA
                conn.setRequestProperty("User-Agent", ua)
                // HWID + device-meta（LxBox SubscriptionIdentity 基准，Remnawave 设备限制面板用）
                if (settings.subscriptionSendHwid) {
                    settings.subscriptionHwid.takeIf { it.isNotBlank() }
                        ?.let { conn.setRequestProperty("x-hwid", it) }
                    conn.setRequestProperty(
                        "x-device-os",
                        settings.subscriptionDeviceOs.takeIf { it.isNotBlank() } ?: "android",
                    )
                    settings.subscriptionVerOs.takeIf { it.isNotBlank() }
                        ?.let { conn.setRequestProperty("x-ver-os", it) }
                    settings.subscriptionDeviceModel.takeIf { it.isNotBlank() }
                        ?.let { conn.setRequestProperty("x-device-model", it) }
                }
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: error("重定向缺少 Location 头")
                    val next = URL(URL(current), location).toExternalForm()
                    if (!next.startsWith("https://")) error("重定向目标非 https，已中止")
                    current = next
                    return@repeat
                }
                if (code !in 200..299) error("HTTP $code")

                val userinfo = parseUserinfo(conn.getHeaderField("subscription-userinfo"))
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(8192)
                    var total = 0
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BODY_CHARS) error("订阅内容超过 4MB，已中止")
                        sb.append(buf, 0, n)
                    }
                    sb.toString()
                }
                return text to userinfo
            } finally {
                conn.disconnect()
            }
        }
        error("重定向次数过多（>$MAX_REDIRECTS）")
    }

    /** 解析 subscription-userinfo: upload=..; download=..; total=..; expire=.. */
    private fun parseUserinfo(header: String?): TrafficInfo {
        if (header.isNullOrBlank()) return TrafficInfo()
        fun field(name: String): Long =
            Regex("""$name\s*=\s*(\d+)""").find(header)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return TrafficInfo(
            upload = field("upload"),
            download = field("download"),
            total = field("total"),
            expire = field("expire"),
        )
    }

    private companion object {
        const val TAG = "SubscriptionManager"
        const val UA = "sb-AI/1.0 (sing-box)"
        const val MAX_BODY_CHARS = 4 * 1024 * 1024
        const val MAX_REDIRECTS = 5
    }
}
