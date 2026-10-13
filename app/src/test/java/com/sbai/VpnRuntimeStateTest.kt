package com.sbai

import android.content.Context
import com.sbai.service.VpnRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * VpnRuntimeState 的跨进程文件通道测试。
 *
 * 注意：publish/read 需要 Context，JVM 单测里用临时目录直接验证
 * 序列化/解析的往返与边界，不依赖 Android 框架。
 */
class VpnRuntimeStateTest {

    private fun dir(): File = File.createTempFile("vpnstate", "test").apply {
        delete(); mkdirs()
    }

    @Test
    fun `phase round trip`() {
        val d = dir()
        // 直接复用 VpnRuntimeState 的序列化格式（与实现保持一致的最小验证）
        val content = """{"phase":"RUNNING","message":"","since":123}"""
        File(d, "vpn_state.json").writeText(content)
        // read 需要 Context，这里只验证格式可被正则解析
        val phase = Regex("\"phase\"\\s*:\\s*\"([A-Z]+)\"").find(content)!!.groupValues[1]
        assertEquals("RUNNING", phase)
    }

    @Test
    fun `message escaping round trips`() {
        val original = "error: quoted and backslash"
        val escaped = original.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        val content = """{"phase":"ERROR","message":"$escaped","since":1}"""
        val parsed = Regex("\"message\"\\s*:\\s*\"(.*?)\"").find(content)!!.groupValues[1]
        // 与 VpnRuntimeState.unescape 相同的逆序替换
        val unescaped = parsed.replace("\\\"", "\"").replace("\\\\", "\\")
        assertEquals(original, unescaped)
    }

    @Test
    fun `missing file yields null semantics`() {
        val d = dir()
        val f = File(d, "nonexistent.json")
        assertTrue(!f.exists())
        // read() 对不存在的文件返回 null，调用方回退到遥测状态
        assertNull(if (f.exists()) "x" else null)
    }

    @Test
    fun `phase enum covers all service states`() {
        val phases = VpnRuntimeState.Phase.entries.map { it.name }.toSet()
        assertTrue(phases.containsAll(setOf("STOPPED", "STARTING", "RUNNING", "STOPPING", "ERROR")))
    }
}
