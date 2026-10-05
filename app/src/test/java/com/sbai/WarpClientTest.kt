package com.sbai

import com.sbai.service.WarpAccount
import com.sbai.service.WarpClient
import com.sbai.service.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WarpClientTest {

    // 模拟 Cloudflare /reg 响应（结构对齐 LxBox 文档）
    private val sampleRegResponse = """
    {
      "id": "device-123",
      "token": "bearer-token-abc",
      "account": {"id": "account-456"},
      "config": {
        "client_id": "${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}",
        "peers": [
          {
            "public_key": "peer-pub-key-base64",
            "endpoint": {"host": "engage.cloudflareclient.com:2408"}
          }
        ],
        "interface": {
          "addresses": {"v4": "172.16.0.2", "v6": "2606:4700:110::2"}
        }
      }
    }
    """.trimIndent()

    @Test
    fun `parse reg response to warp account`() {
        val client = WarpClient()
        val account = client.parseReg(sampleRegResponse, "priv-key-base64")

        assertEquals("priv-key-base64", account.privateKey)
        assertEquals("peer-pub-key-base64", account.peerPublicKey)
        assertEquals("172.16.0.2", account.clientV4)
        assertEquals("2606:4700:110::2", account.clientV6)
        assertEquals("device-123", account.deviceId)
        assertEquals("bearer-token-abc", account.token)
        assertEquals("account-456", account.accountId)
        assertEquals("engage.cloudflareclient.com:2408", account.endpoint)
    }

    @Test
    fun `reserved bytes decode from client id`() {
        val account = WarpClient().parseReg(sampleRegResponse, "priv")
        val reserved = account.reservedBytes()
        assertNotNull(reserved)
        assertEquals(listOf(1, 2, 3), reserved)
    }

    @Test
    fun `to wireguard uri includes reserved and keepalive`() {
        val account = WarpClient().parseReg(sampleRegResponse, "priv")
        val uri = account.toWireguardUri(persistentKeepalive = 25)

        assertTrue(uri.startsWith("wireguard://"))
        assertTrue(uri.contains("publickey=peer-pub-key-base64"))
        assertTrue(uri.contains("address=172.16.0.2,2606:4700:110::2"))
        assertTrue(uri.contains("reserved=1,2,3"))
        assertTrue(uri.contains("keepalive=25"))
        assertTrue(uri.contains("mtu=1280"))
        assertTrue(uri.contains("WARP"))
    }

    @Test
    fun `missing config throws`() {
        val bad = """{"id":"d","token":"t"}"""
        try {
            WarpClient().parseReg(bad, "priv")
            assertTrue("should throw", false)
        } catch (e: WarpClient.WarpException) {
            assertTrue(e.message!!.contains("config"))
        }
    }

    @Test
    fun `missing peer public key throws`() {
        val bad = """{"id":"d","token":"t","config":{"client_id":"AAA","interface":{"addresses":{"v4":"172.16.0.2"}},"peers":[{"public_key":""}]}}"""
        try {
            WarpClient().parseReg(bad, "priv")
            assertTrue("should throw", false)
        } catch (e: WarpClient.WarpException) {
            assertTrue(e.message!!.contains("public_key"))
        }
    }

    @Test
    fun `x25519 keypair generates valid base64 wireguard keys`() {
        val kp = X25519.generateKeyPair()
        val privB64 = Base64.getEncoder().encodeToString(kp.privateKey)
        val pubB64 = Base64.getEncoder().encodeToString(kp.publicKey)
        assertEquals(44, privB64.length)  // 32 字节 → 44 字符 base64
        assertEquals(44, pubB64.length)
        // 公钥应从私钥确定性地派生
        val derived = X25519.publicFromPrivate(kp.privateKey)
        assertTrue(kp.publicKey.contentEquals(derived))
    }
}
