package com.sbai.service

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 密钥对生成（RFC 7748），用于 WARP 注册在设备端生成 WireGuard 密钥。
 *
 * 纯 Kotlin 实现（BigInteger field 运算），无外部加密依赖。
 * 私钥 32 字节随机，公钥 = X25519(私钥, basepoint 9) 标量乘法。
 *
 * 只有公钥会发给 Cloudflare，私钥永不离开设备（对齐 LxBox 安全原则 P1）。
 * 生成只发生一次，BigInteger 的性能足够。
 */
object X25519 {

    private val random = SecureRandom()
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19)) // 2^255 - 19
    private val BASE_U = BigInteger.valueOf(9)

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    /** 生成密钥对，返回原始 32 字节（调用方负责 base64 编码）。 */
    fun generateKeyPair(): KeyPair {
        val priv = ByteArray(32).also { random.nextBytes(it) }
        val pub = publicFromPrivate(priv)
        return KeyPair(priv, pub)
    }

    /** 从 32 字节私钥计算公钥。 */
    fun publicFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        val e = privateKey.copyOf()
        // RFC 7748 clamp
        e[0] = (e[0].toInt() and 248).toByte()
        e[31] = (e[31].toInt() and 127).toByte()
        e[31] = (e[31].toInt() or 64).toByte()
        return x25519(e, BASE_U)
    }

    /** 内部：直接 X25519 标量乘法（输入标量已 clamp），供测试向量验证。 */
    internal fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        val uCoord = decodeLittleEndian(u)
        return x25519(scalar, uCoord)
    }

    private fun decodeLittleEndian(b: ByteArray): BigInteger {
        // little-endian 32 字节 → BigInteger（注意 BigInteger 是 big-endian）
        val bigEndian = b.reversedArray()
        return BigInteger(1, bigEndian)
    }

    /** RFC 7748 X25519 核心：scalar × u-coordinate → u-coordinate。 */
    private fun x25519(scalar: ByteArray, u: BigInteger): ByteArray {
        var x1 = u.mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        // 小端 bit 遍历：RFC 7748 标量按 little-endian bit 从高到低处理
        for (t in 254 downTo 0) {
            val kt = (scalar[t / 8].toInt() ushr (t % 8)) and 1
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            // Montgomery ladder 一步
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e2 = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val x3new = da.add(cb).mod(P).multiply(da.add(cb).mod(P)).mod(P)
            val z3new = x1.multiply(da.subtract(cb).mod(P).multiply(da.subtract(cb).mod(P))).mod(P)
            val x2new = aa.multiply(bb).mod(P)
            val z2new = e2.multiply(aa.add(BigInteger.valueOf(121665).multiply(e2).mod(P))).mod(P)

            x2 = x2new; z2 = z2new; x3 = x3new; z3 = z3new
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }

        val result = x2.multiply(z2.modInverse(P)).mod(P)
        // 编码为 32 字节 little-endian
        val bytes = result.toByteArray()
        val out = ByteArray(32)
        // BigInteger 是 big-endian，转 little-endian 填满 32 字节
        val magnitude = bytes.copyOfRange(if (bytes.first() == 0.toByte() && bytes.size > 1) 1 else 0, bytes.size)
        for (i in magnitude.indices) {
            out[i] = magnitude[magnitude.size - 1 - i]
        }
        return out
    }
}
