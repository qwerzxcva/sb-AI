package com.sbai

import com.sbai.service.readBoundedText
import java.io.StringReader
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedTextTest {
    @Test fun emptyBody() { assertEquals("", StringReader("").readBoundedText(0)) }
    @Test fun exactLimit() { assertEquals("测试", StringReader("测试").readBoundedText(2)) }
    @Test(expected = IllegalArgumentException::class)
    fun oversizedBody() { StringReader("abc").readBoundedText(2) }
    @Test(expected = IllegalArgumentException::class)
    fun negativeLimit() { StringReader("").readBoundedText(-1) }
    @Test fun multibufferBody() {
        val text = "中".repeat(20000)
        assertEquals(text, StringReader(text).readBoundedText(text.length))
    }
    @Test(expected = CancellationException::class)
    fun cancellationPropagates() {
        StringReader("abc").readBoundedText(20) { throw CancellationException() }
    }
}
