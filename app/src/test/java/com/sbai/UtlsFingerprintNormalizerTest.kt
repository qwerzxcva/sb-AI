package com.sbai.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** UtlsFingerprintNormalizer 单元测试（参考 LxBox §281）。 */
class UtlsFingerprintNormalizerTest {

    @Test
    fun `canonical fingerprints passthrough`() {
        assertEquals("chrome", UtlsFingerprintNormalizer.normalize("chrome"))
        assertEquals("firefox", UtlsFingerprintNormalizer.normalize("firefox"))
        assertEquals("safari", UtlsFingerprintNormalizer.normalize("safari"))
        assertEquals("edge", UtlsFingerprintNormalizer.normalize("edge"))
        assertEquals("ios", UtlsFingerprintNormalizer.normalize("ios"))
        assertEquals("android", UtlsFingerprintNormalizer.normalize("android"))
        assertEquals("qq", UtlsFingerprintNormalizer.normalize("qq"))
    }

    @Test
    fun `xray hellochrome aliases map to chrome`() {
        val variants = listOf(
            "hellochrome_120", "HelloChrome_110", "HELLOCHROME99",
            "hellochrome107", "hellorandomizedalpn",
            "hellochrome", "hellochrome_120_shuffle",
        )
        variants.forEach { raw ->
            assertEquals("expected $raw → chrome", "chrome", UtlsFingerprintNormalizer.normalize(raw))
        }
    }

    @Test
    fun `xray hellofirefox aliases map to firefox`() {
        val variants = listOf(
            "hellofirefox_120", "HelloFirefox_117", "HELLOFIREFOX96",
            "hellofirefox", "hellofirefox_105",
        )
        variants.forEach { raw ->
            assertEquals("expected $raw → firefox", "firefox", UtlsFingerprintNormalizer.normalize(raw))
        }
    }

    @Test
    fun `unknown fingerprint degrades to chrome`() {
        assertEquals("chrome", UtlsFingerprintNormalizer.normalize("unknown_fingerprint_x"))
        assertEquals("chrome", UtlsFingerprintNormalizer.normalize("garbage123"))
        assertEquals("chrome", UtlsFingerprintNormalizer.normalize(""))
        assertEquals("chrome", UtlsFingerprintNormalizer.normalize("   "))
    }

    @Test
    fun `wasAltered detects non-canonical input`() {
        assertEquals(false, UtlsFingerprintNormalizer.wasAltered("chrome"))
        assertEquals(false, UtlsFingerprintNormalizer.wasAltered("firefox"))
        assertEquals(true, UtlsFingerprintNormalizer.wasAltered("hellochrome_120"))
        assertEquals(true, UtlsFingerprintNormalizer.wasAltered("garbage123"))
        assertEquals(false, UtlsFingerprintNormalizer.wasAltered(""))
    }

    @Test
    fun `helloios helloandroid helloqq edge safari map correctly`() {
        assertEquals("ios", UtlsFingerprintNormalizer.normalize("helloios"))
        assertEquals("android", UtlsFingerprintNormalizer.normalize("helloandroid"))
        assertEquals("qq", UtlsFingerprintNormalizer.normalize("helloqq"))
        assertEquals("edge", UtlsFingerprintNormalizer.normalize("helloedge"))
        assertEquals("safari", UtlsFingerprintNormalizer.normalize("hellosafari"))
    }
}
