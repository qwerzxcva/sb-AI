package com.sbai.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Cloudflare WARP 注册客户端（对齐 LxBox warp_client.dart §025）。
 *
 * 设备端生成 X25519 密钥对，只上传公钥到 Cloudflare，换取可用的
 * WireGuard 节点参数（peer 公钥 / 接口地址 / client_id / endpoint）。
 *
 * API host 顺序（§418）：api.devices.cloudflare.com 优先（部分网络可达），
 * 其次 api.cloudflareclient.com。任一 HTTP 响应（含 4xx/5xx）即终局，不换 host。
 */
class WarpClient {

    private val json = Json { ignoreUnknownKeys = true }

    data class WarpException(override val message: String) : Exception(message)

    companion object {
        const val VERSION = "v0a2158"
        const val CLIENT_VERSION_HEADER = "a-7.21-0721"
        const val USER_AGENT = "okhttp/3.12.1"
        val API_HOSTS = listOf(
            "https://api.devices.cloudflare.com",
            "https://api.cloudflareclient.com",
        )
        const val TIMEOUT_MS = 5000
    }

    /** 注册设备，返回缓存的 WARP 账号。可复用缓存生成多个节点。 */
    fun register(nowIso8601: String = java.time.Instant.now().toString()): WarpAccount {
        val kp = X25519.generateKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(kp.publicKey)
        val privB64 = Base64.getEncoder().encodeToString(kp.privateKey)

        val body = buildString {
            append("{")
            append("\"key\":\"$pubB64\",")
            append("\"install_id\":\"\",")
            append("\"fcm_token\":\"\",")
            append("\"tos\":\"$nowIso8601\",")
            append("\"model\":\"PC\",")
            append("\"type\":\"Android\",")
            append("\"locale\":\"en_US\"")
            append("}")
        }

        val (respBody, _) = postReg(body)
        val parsed = parseReg(respBody, privB64)
        return parsed
    }

    /** POST /reg，带 host failover。返回 (响应体, 命中的 host)。 */
    private fun postReg(body: String): Pair<String, String> {
        val errors = mutableListOf<String>()
        for (host in API_HOSTS) {
            try {
                val url = URL("$host/$VERSION/reg")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("CF-Client-Version", CLIENT_VERSION_HEADER)
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }

                val code = conn.responseCode
                val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()

                // 任何 HTTP 响应（含 4xx/5xx）都算「host 活着」，不再换 host
                if (code != 200) {
                    throw WarpException(
                        "registration failed (HTTP $code). API version may have changed ($VERSION).",
                    )
                }
                return respBody to host
            } catch (e: WarpException) {
                throw e  // HTTP 响应是终局，不换 host
            } catch (e: Exception) {
                errors.add("$host: ${e.message}")
            }
        }
        throw WarpException("network error: ${errors.joinToString("; ")}")
    }

    /** 解析 /reg 响应为 WarpAccount。 */
    internal fun parseReg(body: String, privB64: String): WarpAccount {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw WarpException("bad response: not JSON") }

        val deviceId = root["id"]?.jsonPrimitive?.content.orEmpty()
        val token = root["token"]?.jsonPrimitive?.content.orEmpty()
        val accountId = root["account"]?.jsonObject?.get("id")?.jsonPrimitive?.content.orEmpty()

        val config = root["config"]?.jsonObject
            ?: throw WarpException("bad response: missing config")
        val clientId = config["client_id"]?.jsonPrimitive?.content.orEmpty()

        val peers = config["peers"]?.jsonArray
        val peer = peers?.firstOrNull()?.jsonObject
            ?: throw WarpException("bad response: missing peers")
        val peerPub = peer["public_key"]?.jsonPrimitive?.content.orEmpty()
        if (peerPub.isEmpty()) throw WarpException("bad response: missing peer public_key")

        // endpoint host（默认 engage.cloudflareclient.com:2408；用户自定义优先，这里用默认）
        val endpoint = peer["endpoint"]?.jsonObject?.get("host")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: WarpAccount.DEFAULT_ENDPOINT

        val iface = config["interface"]?.jsonObject
        val addrs = iface?.get("addresses")?.jsonObject
        val v4 = addrs?.get("v4")?.jsonPrimitive?.content.orEmpty()
        val v6 = addrs?.get("v6")?.jsonPrimitive?.content.orEmpty()
        if (v4.isEmpty()) throw WarpException("bad response: missing interface address")

        return WarpAccount(
            privateKey = privB64,
            peerPublicKey = peerPub,
            clientV4 = v4,
            clientV6 = v6,
            clientId = clientId,
            accountId = accountId,
            deviceId = deviceId,
            token = token,
            endpoint = endpoint,
        )
    }
}
