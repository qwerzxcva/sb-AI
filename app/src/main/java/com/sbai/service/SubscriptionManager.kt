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
class SubscriptionManager(
    private val store: RuleStore,
    private val appContext: android.content.Context,
) {

    sealed interface Result {
        data class Success(val nodeCount: Int) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun refresh(subscription: Subscription): Result = withContext(Dispatchers.IO) {
        try {
            // 允许 http（很多机场订阅是 http）；https 更安全但不强制
            require(subscription.url.startsWith("https://") || subscription.url.startsWith("http://")) {
                "订阅地址必须是 http/https"
            }
            val settings = store.state.value.settings
            val fetched = httpGet(subscription.url, subscription.userAgent, settings, subscription.detour, appContext)
            val body = fetched.body

            var parsed = ShareLinkParser.parseSubscription(body)
            if (parsed.isEmpty()) {
                // 给出可诊断的错误：说明拿到了什么格式
                val hint = when {
                    body.isBlank() -> "订阅返回空内容"
                    body.trimStart().startsWith("{") -> "订阅返回的是 JSON 配置（非分享链接），暂不支持"
                    body.contains("proxies:") || body.contains("proxy-groups:") ->
                        "订阅返回的是 Clash YAML 格式，暂不支持（请改用 sing-box/通用订阅链接）"
                    body.trimStart().startsWith("<") -> "订阅返回 HTML（可能是订阅过期/需要登录）"
                    else -> "订阅内容不包含可识别的节点链接（支持 vless/vmess/trojan/ss/hysteria2）"
                }
                return@withContext fail(subscription, hint)
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
                // 去重按「配置内容」而非名称：同名但不同服务器/端口/参数的节点都保留，
                // 只有完全相同的配置才去除（用户反馈：同名节点不应被误删）
                parsed.distinctBy { normalizeOutbound(it.outboundJson) }
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
            // #15：未填名称时，自动识别机场名（profile-title 头 > content-disposition > 域名）
            val autoName = subscription.name.takeIf { it.isNotBlank() }
                ?: detectAirportName(fetched.headers, subscription.url)
            store.upsertSubscription(
                subscription.copy(
                    name = autoName,
                    lastUpdatedAt = System.currentTimeMillis(),
                    lastError = null,
                    nodeCount = nodes.size,
                    trafficUpload = fetched.traffic.upload,
                    trafficDownload = fetched.traffic.download,
                    trafficTotal = fetched.traffic.total,
                    trafficExpire = fetched.traffic.expire,
                ),
            )
            Log.i(TAG, "subscription ${autoName}: ${nodes.size} nodes")
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

    /**
     * 识别机场名（#15）：
     * 1. `profile-title` 响应头（可能 base64，机场常用）
     * 2. `content-disposition` 里的 filename
     * 3. 回退到订阅域名
     */
    private fun detectAirportName(headers: Map<String, String>, url: String): String {
        headers["profile-title"]?.takeIf { it.isNotBlank() }?.let { raw ->
            // 部分机场把 profile-title 做 base64
            val decoded = ShareLinkParser.decodeB64(raw.trim())
                ?.let { String(it, Charsets.UTF_8) }
                ?.takeIf { it.isNotBlank() && it.none { c -> c.isISOControl() } }
            return (decoded ?: raw).trim().ifBlank { hostOf(url) }
        }
        headers["content-disposition"]?.let { cd ->
            Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""", RegexOption.IGNORE_CASE)
                .find(cd)?.groupValues?.get(1)?.trim()
                ?.let { if (it.isNotBlank()) return it }
        }
        return hostOf(url)
    }

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrDefault("订阅")

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

    /**
     * 归一化 outbound JSON 用于去重：
     * 解析后按 key 排序重新序列化，忽略 tag/name（节点名可不同但配置相同视为重复）。
     */
    private fun normalizeOutbound(outboundJson: String): String = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(outboundJson).jsonObject
        val filtered = obj.filterKeys { it != "tag" && it != "name" }
        // 按 key 排序序列化，保证相同配置产生相同字符串
        filtered.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
    }.getOrDefault(outboundJson)

    private data class TrafficInfo(
        val upload: Long = 0, val download: Long = 0,
        val total: Long = 0, val expire: Long = 0,
    )

    /** 抓取结果：正文 + 流量信息 + 响应头（机场名等） */
    private data class FetchResult(
        val body: String,
        val traffic: TrafficInfo,
        val headers: Map<String, String>,
    )

    private fun httpGet(
        url: String,
        userAgent: String?,
        settings: com.sbai.data.AppSettings,
        detour: String,
        context: android.content.Context,
    ): FetchResult {
        // #13：detour=direct 时把本进程临时绑定到底层非 VPN 网络，绕过隧道直连拉取。
        // 因为 VPN 服务运行在独立的 :core 进程，绑定 UI 进程不会影响内核转发。
        val boundNetwork = if (detour == "direct") bindToUnderlyingNetwork(context) else null
        try {
            return httpGetInternal(url, userAgent, settings)
        } finally {
            if (boundNetwork != null) {
                runCatching {
                    android.net.ConnectivityManager.setProcessDefaultNetwork(null)
                }
            }
        }
    }

    /** 找到当前非 VPN 的底层网络并绑定到本进程；成功返回该网络 */
    private fun bindToUnderlyingNetwork(context: android.content.Context): android.net.Network? {
        return runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
            val underlying = cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == false
            } ?: return null
            android.net.ConnectivityManager.setProcessDefaultNetwork(underlying)
            underlying
        }.getOrNull()
    }

    private fun httpGetInternal(
        url: String,
        userAgent: String?,
        settings: com.sbai.data.AppSettings,
    ): FetchResult {
        // 手动跟随重定向（默认 HttpURLConnection 不跨 http/https 跟随）。
        // 若原始 URL 是 https，则拒绝降级到 http（防凭据明文泄露）；http 订阅允许 http 跳转。
        val originIsHttps = url.startsWith("https://")
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 20_000
                // UA：订阅级 override > 全局 override > 品牌 UA
                val ua = userAgent?.takeIf { it.isNotBlank() }
                    ?: settings.subscriptionUserAgent.takeIf { it.isNotBlank() }
                    ?: UA
                conn.setRequestProperty("User-Agent", ua)
                // 机场常用：声明客户端类型以拿到通用订阅格式
                conn.setRequestProperty("Accept", "*/*")
                // HWID + device-meta（LxBox SubscriptionIdentity 基准）
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
                        ?: error("重定向缺少 Location 头（HTTP $code）")
                    val next = URL(URL(current), location).toExternalForm()
                    if (originIsHttps && next.startsWith("http://")) {
                        error("拒绝 https → http 降级重定向")
                    }
                    current = next
                    return@repeat
                }
                if (code !in 200..299) error("HTTP $code")

                val headers = mutableMapOf<String, String>()
                conn.headerFields?.forEach { (k, v) ->
                    if (k != null && v.isNotEmpty()) headers[k.lowercase()] = v.joinToString(",")
                }
                val traffic = parseUserinfo(headers["subscription-userinfo"])
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
                return FetchResult(text, traffic, headers)
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
