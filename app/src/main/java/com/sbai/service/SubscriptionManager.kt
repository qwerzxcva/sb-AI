package com.sbai.service

import java.security.cert.X509Certificate
import android.util.Log
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import com.sbai.data.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 订阅源管理：拉取订阅内容 → 解析分享链接 → 按关键字过滤 → 替换该订阅名下节点。
 *
 * 增强：
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
            val fetched = httpGet(
                subscription.url, subscription.userAgent, settings,
                subscription.detour, appContext, subscription.skipCertVerify,
            )
            val body = fetched.body

            // 多格式解析：分享链接 / Clash YAML / sing-box JSON
            val parsedResult = SubscriptionFormat.parse(body)
            if (parsedResult == null || parsedResult.nodes.isEmpty()) {
                // 给出可诊断的错误：说明拿到了什么格式
                val hint = when {
                    body.isBlank() -> "订阅返回空内容"
                    body.trimStart().startsWith("<") ->
                        "订阅返回 HTML 登录/授权页（该地址可能是登录端点而非订阅链接，" +
                            "请到机场后台复制「通用订阅链接 / base64 / Clash 订阅」后重试）"
                    else -> "订阅内容无法识别为任何支持的格式（分享链接 / Clash YAML / sing-box JSON）"
                }
                return@withContext fail(subscription, hint)
            }

            var parsed = parsedResult.nodes
            val subFormat = parsedResult.format

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

            // L7Filter：协议 / 地区关键字过滤（空 = 不过滤）
            val filterProto = subscription.filterProtocol.trim()
            val filterRegion = subscription.filterRegion.trim()
            if (filterProto.isNotEmpty()) {
                parsed = parsed.filter { n ->
                    val protoHit = filterProto.split(Regex("\\s+"))
                        .filter { it.isNotBlank() }.any { pattern ->
                            n.outboundJson.contains(pattern, ignoreCase = true)
                        }
                    protoHit
                }
            }
            if (filterRegion.isNotEmpty()) {
                parsed = parsed.filter { n ->
                    val regionHit = filterRegion.split(Regex("\\s+"))
                        .filter { it.isNotBlank() }.any { pattern ->
                            n.name.contains(pattern, ignoreCase = true)
                        }
                    regionHit
                }
            }

            // 节点后处理选项
            if (subscription.removeInsecure) {
                parsed = parsed.filter { !isInsecureNode(it.outboundJson) }
            }
            if (subscription.removeInfoNodes) {
                parsed = parsed.filter { !ShareLinkParser.isInfoNode(it.name) }
            }
            if (parsed.isEmpty()) {
                return@withContext fail(subscription, "过滤后无剩余节点")
            }
            val nodes = (if (subscription.removeDuplicates) {
                // 去重按「配置内容」而非名称：同名但不同服务器/端口/参数的节点都保留，
                // 只有完全相同的配置才去除（用户反馈：同名节点不应被误删）
                parsed.distinctBy { NodeDedup.normalize(it.outboundJson) }
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

            // 导入解析出的路由规则（Clash YAML / sing-box JSON 配置带规则时）
            val importedRules = parsedResult.routeRules
            if (importedRules.isNotEmpty()) {
                // 只保留启用的新规则，插入到现有规则最前（订阅规则优先级最高）
                importedRules.forEach { rule ->
                    store.upsertRouteRule(rule.copy(enabled = true))
                }
                Log.i(TAG, "subscription ${subscription.name}: +${importedRules.size} route rules ($subFormat)")
            }

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
            Log.i(TAG, "subscription ${autoName}: ${nodes.size} nodes ($subFormat)")
            Result.Success(nodes.size)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            Log.w(TAG, "subscription refresh failed (${t.javaClass.simpleName})")
            // 统一为中文错误提示，避免显示英文异常消息
            val msg = when {
                t.message?.contains("http") == true || t.message?.contains("HTTP") == true -> "网络请求失败（HTTP ${t.message?.let { Regex("""\d+""").find(it)?.value ?: "?"} }）"
                t.message?.contains("timeout") == true || t.message?.contains("Timeout") == true -> "连接超时，请检查网络或订阅地址"
                t.message?.contains("certificate") == true || t.message?.contains("Certificate") == true -> "SSL 证书校验失败，可在订阅设置中开启「跳过 TLS 证书校验」"
                else -> "订阅更新失败（${t.javaClass.simpleName}），请检查地址及内容格式"
            }
            fail(subscription, msg)
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
        skipCertVerify: Boolean = false,
    ): FetchResult {
        // #13：detour=direct 时把本进程临时绑定到底层非 VPN 网络，绕过隧道直连拉取。
        // 因为 VPN 服务运行在独立的 :core 进程，绑定 UI 进程不会影响内核转发。
        val boundNetwork = if (detour == "direct") bindToUnderlyingNetwork(context) else null
        try {
            return httpGetInternal(url, userAgent, settings, skipCertVerify)
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
        }.getOrElse {
            android.util.Log.w(TAG, "bindToUnderlyingNetwork failed", it)
            null
        }
    }

    /**
     * 让单个 HTTPS 连接信任所有证书并跳过主机名校验。
     * 用于机场 CDN 域名证书不匹配（如返回 mail.qq.com 证书）的场景。
     * 仅影响传入的连接对象，不全局修改 SSL 配置。
     */
    private fun applyTrustAll(conn: HttpsURLConnection) {
        runCatching {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                },
            )
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, trustAllCerts, java.security.SecureRandom())
            conn.sslSocketFactory = ctx.socketFactory
            conn.hostnameVerifier = object : HostnameVerifier {
                override fun verify(hostname: String?, session: javax.net.ssl.SSLSession?): Boolean = true
            }
        }.getOrElse { Log.w(TAG, "applyTrustAll failed", it) }
    }

    private fun httpGetInternal(
        url: String,
        userAgent: String?,
        settings: com.sbai.data.AppSettings,
        skipCertVerify: Boolean = false,
    ): FetchResult {
        // 手动跟随重定向（默认 HttpURLConnection 不跨 http/https 跟随）。
        // 若原始 URL 是 https，则拒绝降级到 http（防凭据明文泄露）；http 订阅允许 http 跳转。
        val originIsHttps = url.startsWith("https://")
        var current = url
        // UA：订阅级 override > 全局 override > 品牌 UA
        val ua = userAgent?.takeIf { it.isNotBlank() }
            ?: settings.subscriptionUserAgent.takeIf { it.isNotBlank() }
            ?: UA
        repeat(MAX_REDIRECTS) {
            val conn = URL(current).openConnection() as HttpURLConnection
            // 跳过证书校验（机场 CDN 域名证书不匹配时用）：仅本订阅生效
            if (skipCertVerify && conn is javax.net.ssl.HttpsURLConnection) {
                applyTrustAll(conn)
            }
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 20_000
                // UA：订阅级 override > 全局 override > 品牌 UA
                conn.setRequestProperty("User-Agent", ua)
                // 默认保留系统证书与主机名验证；只有显式 skipCertVerify 才允许放宽。
                // 机场常用：声明客户端类型以拿到通用订阅格式
                conn.setRequestProperty("Accept", "*/*")
                // HWID + device-meta 头部
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

                // 证书校验失败（hostname 不匹配 / CA 不信任）→ 容忍，继续拉取（机场 CDN 常见）
                val headers: MutableMap<String, String> = mutableMapOf()
                conn.headerFields?.forEach { (k, v) ->
                    if (k != null && v.isNotEmpty()) headers[k.lowercase()] = v.joinToString(",")
                }
                val textAndHeaders = conn.inputStream.bufferedReader(Charsets.UTF_8).use {
                    it.readBoundedText(MAX_BODY_CHARS) to headers
                }
                val traffic = parseUserinfo(textAndHeaders.second["subscription-userinfo"])
                return FetchResult(textAndHeaders.first, traffic, textAndHeaders.second)
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
