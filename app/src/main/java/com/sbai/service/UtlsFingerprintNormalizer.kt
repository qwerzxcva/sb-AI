package com.sbai.service

/**
 * uTLS 指纹规范化（参考 LxBox §281 / utls_fingerprint.dart）。
 *
 * 问题：sing-box 内核的 uTLSClientHelloID 是大小写敏感的固定枚举（chrome、firefox、safari…）。
 * Clash/xray 生态的分享链接（vmess/vless/trojan）常用自己的别名：
 *   - hellochrome_120 / HelloChrome_110 / ... → chrome
 *   - hellofirefox_117 / ... → firefox
 *   - 其他 → chrome（兜底）
 * 若原样透传，内核在构造 outbound 阶段抛出「unknown uTLS fingerprint」→ fatal → VPN 起不来，
 * 用户感知为「连不上节点」。
 *
 * 采用「前缀匹配」而非精确表，因为 xray 指纹格式带下划线版本号（hellochrome_120_shuffle），
 * 用前缀 hellofirefox_ / hellochrome_ / hellosafari_ ... 更健壮。
 */
object UtlsFingerprintNormalizer {

    /** 已知 canonical 指纹（内核固定枚举，原样保留）。 */
    private val KNOWN_CANONICAL = setOf(
        "chrome", "firefox", "safari", "edge", "ios", "qq", "android",
        "random", "none",
    )

    /** 前缀 → canonical 指纹（长前缀优先，避免 hellochrome 被 hellochrome_x 截断歧义）。 */
    private val PREFIX_TO_CANONICAL: List<Pair<String, String>> = listOf(
        "hellochrome" to "chrome",
        "hellorandomizedalpn" to "chrome",
        "hellorandomized" to "chrome",
        "helloaggregated" to "chrome",
        "hello360" to "chrome",
        "helloquic" to "chrome",
        "hellofirefox" to "firefox",
        "hellosafari" to "safari",
        "helloedge" to "edge",
        "helloios" to "ios",
        "helloandroid" to "android",
        "helloqq" to "qq",
    )

    /**
     * 规范化 uTLS 指纹值。
     * @param raw 原始值（来自分享链接的 fp 参数，可能为空）
     * @return 规范化后的值（已知别名 → canonical；未知/空白 → chrome 兜底）
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "chrome"
        val key = trimmed.lowercase()
        if (key in KNOWN_CANONICAL) return key
        for ((prefix, canonical) in PREFIX_TO_CANONICAL) {
            if (key.startsWith(prefix)) return canonical
        }
        // 未知指纹降级为 chrome（内核约定：空/未知 → chrome）
        return "chrome"
    }

    /**
     * 原始值是否已偏离 canonical 形式（用于 warning 日志）。
     * @return raw 非空且 normalize(raw) != raw 时为 true
     */
    fun wasAltered(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        return normalize(raw) != raw
    }
}
