package com.sbai

import com.sbai.service.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class X25519Test {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `rfc 7748 alice public key`() {
        // RFC 7748 §6.1：Alice 私钥 → 公钥（这是 publicFromPrivate 的正确验证向量）
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val expected = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        assertArrayEquals(expected, X25519.publicFromPrivate(alicePriv))
    }

    @Test
    fun `rfc 7748 bob public key`() {
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val expected = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertArrayEquals(expected, X25519.publicFromPrivate(bobPriv))
    }

    @Test
    fun `generated keypair has 32 byte keys`() {
        val kp = X25519.generateKeyPair()
        assertEquals(32, kp.privateKey.size)
        assertEquals(32, kp.publicKey.size)
    }

    @Test
    fun `public key is deterministic from private key`() {
        val priv = ByteArray(32) { it.toByte() }
        val pub1 = X25519.publicFromPrivate(priv)
        val pub2 = X25519.publicFromPrivate(priv)
        assertArrayEquals(pub1, pub2)
    }

    @Test
    fun `base64 encoding roundtrips`() {
        val kp = X25519.generateKeyPair()
        val privB64 = Base64.getEncoder().encodeToString(kp.privateKey)
        val decoded = Base64.getDecoder().decode(privB64)
        assertArrayEquals(kp.privateKey, decoded)
        assertEquals(44, privB64.length)  // 32 字节 → 44 字符 base64（含 padding）
    }
}
