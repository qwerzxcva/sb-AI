package com.sbai.service

import java.net.HttpURLConnection
import java.net.URL

/**
 * 出口 IP 查询（best-effort）：通过 TUN 隧道回环一次 HTTP，返回当前 VPN 出口 IP。
 *
 * 设计：
 *  - 在 :core 进程（VPN 运行处）调用，请求经 sing-box TUN 转发 → 拿到的是节点出口 IP；
 *  - 多端点回退（ipify / ipinfo / ifconfig.me / 114.114.114.114），任一成功即缓存；
 *  - 严格超时（默认 5s），绝不阻塞主流程；失败 → 返回已缓存值或空串（UI 显示「—」）。
 *  - 结果缓存在内存，同一次 VPN 会话内不重复查（避免每 2.5s 发一次网络请求）。
 */
object PublicIpLookup {

    private var cached: String = ""
    private val lock = Any()

    /** VPN 会话重置时调用（停止/切换节点后出口 IP 变化）。 */
    fun reset() {
        synchronized(lock) { cached = "" }
    }

    /** 同步查询（调用方须保证在 IO 线程）；返回缓存或 ""（未取到）。 */
    fun lookupBlocking(timeoutMs: Long = 5_000L): String {
        synchronized(lock) {
            if (cached.isNotEmpty()) return cached
        }
        val endpoints = listOf(
            "https://api.ipify.org",
            "https://ipinfo.io/ip",
            "https://ifconfig.me/ip",
            "https://cloudflare-ip.com",
        )
        for (url in endpoints) {
            val raw = runCatching { fetchPlain(url, timeoutMs) }.getOrNull()
            if (raw.isNullOrBlank()) continue
            val ip = normalize(raw)
            if (ip.isNotEmpty()) {
                synchronized(lock) { cached = ip }
                return ip
            }
        }
        return ""
    }

    /** 已缓存的出口 IP（"" = 尚未取到）。UI 侧直接读，不触发网络。 */
    fun cached(): String = synchronized(lock) { cached }

    private fun fetchPlain(url: String, timeoutMs: Long): String? {
        val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
        conn.connectTimeout = timeoutMs.toInt()
        conn.readTimeout = timeoutMs.toInt()
        conn.addRequestProperty("Accept", "text/plain, application/json")
        conn.addRequestProperty("User-Agent", "sb-AI/1.0")
        conn.connect()
        return try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText().trim().take(500)
            } else ""
        } catch (e: Exception) { "" } finally { runCatching { conn.disconnect() } }
    }

    /** 把响应规范化为纯 IP：ipify/114 返回纯文本 IP；ipinfo 可能返回 JSON {"ip":"..."}。 */
    private fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("{")) {
            val m = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"").find(t)
            return m?.groupValues?.get(1)?.trim() ?: ""
        }
        return t
    }
}
