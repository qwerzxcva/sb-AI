package com.sbai.service

import kotlinx.serialization.Serializable

/**
 * 缓存的 Cloudflare WARP 账号（对齐 LxBox WarpAccount）。
 *
 * 私钥 X25519 在设备生成，永不上传；只上传公钥到 Cloudflare。
 * 一次注册可生成多个节点（复用缓存），Re-register 才重新注册。
 */
@Serializable
data class WarpAccount(
    /** base64 X25519 私钥（设备生成，SECRET，不落日志） */
    val privateKey: String = "",
    /** base64 Cloudflare peer 公钥（config.peers[0].public_key） */
    val peerPublicKey: String = "",
    /** 接口地址 v4（如 172.16.0.2） */
    val clientV4: String = "",
    /** 接口地址 v6 */
    val clientV6: String = "",
    /** base64 config.client_id（3 字节）→ WireGuard reserved */
    val clientId: String = "",
    val accountId: String = "",
    val deviceId: String = "",
    /** Bearer token（SECRET，用于 PATCH account） */
    val token: String = "",
    /** host:port，默认 engage.cloudflareclient.com:2408 */
    val endpoint: String = DEFAULT_ENDPOINT,
    val createdAt: String = "",
    /** WARP+ license key */
    val license: String? = null,
    val warpPlus: Boolean = false,
) {
    companion object {
        const val DEFAULT_ENDPOINT = "engage.cloudflareclient.com:2408"
    }

    /** client_id base64 → reserved 三个字节（0-255） */
    fun reservedBytes(): List<Int>? = runCatching {
        val decoded = java.util.Base64.getDecoder().decode(clientId)
        decoded.take(3).map { it.toInt() and 0xff }.takeIf { it.size == 3 }
    }.getOrNull()

    /** 生成 wireguard:// URI（对齐 LxBox toWireguardUri），用于导入节点 */
    fun toWireguardUri(persistentKeepalive: Int? = 25): String {
        val reserved = reservedBytes()
        val addrs = listOf(clientV4, clientV6).filter { it.isNotBlank() }.joinToString(",")
        val q = buildList {
            add("publickey=${peerPublicKey}")
            add("address=$addrs")
            add("allowedips=0.0.0.0/0,::/0")
            add("mtu=1280")
            if (reserved != null) add("reserved=${reserved.joinToString(",")}")
            if (persistentKeepalive != null && persistentKeepalive > 0) add("keepalive=$persistentKeepalive")
        }.joinToString("&")
        val tag = "WARP${if (warpPlus) "+" else ""}"
        return "wireguard://${java.net.URLEncoder.encode(privateKey, "UTF-8")}@$endpoint?$q#$tag"
    }
}
