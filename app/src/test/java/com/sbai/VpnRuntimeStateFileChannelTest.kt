package com.sbai

import com.sbai.service.VpnRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 验证 VpnRuntimeState 的跨进程文件通道（P0 根因修复）：
 * - publish 写 filesDir/sb-ai-vpn-state.json（原子 .tmp+rename）
 * - refreshFromDisk 读取该文件：UI「新进程」总能拿到 :core 发布的最新值，
 *   不受 SharedPreferences 每进程内存缓存影响
 * - 文件不存在/损坏时回退 SharedPreferences 兼容通道
 */
class VpnRuntimeStateFileChannelTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "sbai-vpn-${System.nanoTime()}")
        if (dir.exists()) dir.deleteRecursively()
        assertTrue(dir.mkdirs())
        resetVpnState()
    }

    /** VpnRuntimeState 是进程级单例（object），其 _phase/_message 跨测试方法泄漏；
     *  每个测试前复位为「全新 UI 进程」的初始值，保证断言的 hermeticity。 */
    @Suppress("UNCHECKED_CAST")
    private fun resetVpnState() {
        val cls = VpnRuntimeState::class.java
        val phaseField = cls.getDeclaredField("_phase").apply { isAccessible = true }
        val messageField = cls.getDeclaredField("_message").apply { isAccessible = true }
        (phaseField.get(VpnRuntimeState) as MutableStateFlow<VpnRuntimeState.Phase>).value = VpnRuntimeState.Phase.Stopped
        (messageField.get(VpnRuntimeState) as MutableStateFlow<String?>).value = null
    }

    @Test
    fun `publishState writes file and a fresh reader sees the latest phase`() {
        val prefs = FakeSharedPreferences()

        VpnRuntimeState.publishState(dir, prefs, VpnRuntimeState.Phase.Error, "启动失败：内核无响应")

        // 模拟 UI 进程重启后首次刷新（内存镜像已被清空，只能靠读盘）
        VpnRuntimeState.refreshState(dir, null)
        assertEquals(VpnRuntimeState.Phase.Error, VpnRuntimeState.phase.value)
        assertEquals("启动失败：内核无响应", VpnRuntimeState.message.value)
        assertTrue(VpnRuntimeState.phase.value.name == "Error")
    }

    @Test
    fun `publishState with running clears error message`() {
        val prefs = FakeSharedPreferences()
        VpnRuntimeState.publishState(dir, prefs, VpnRuntimeState.Phase.Error, "启动失败")
        VpnRuntimeState.publishState(dir, prefs, VpnRuntimeState.Phase.Running)

        VpnRuntimeState.refreshState(dir, null)
        assertEquals(VpnRuntimeState.Phase.Running, VpnRuntimeState.phase.value)
        assertNull(VpnRuntimeState.message.value)
        assertTrue(VpnRuntimeState.isActive())
    }

    @Test
    fun `multiple publishes keep the newest value visible to a new reader`() {
        val prefs = FakeSharedPreferences()
        VpnRuntimeState.publishState(dir, prefs, VpnRuntimeState.Phase.Starting)
        VpnRuntimeState.publishState(dir, prefs, VpnRuntimeState.Phase.Error, "内核崩溃")
        VpnRuntimeState.publishState(dir, prefs, VpnRuntimeState.Phase.Stopped)

        VpnRuntimeState.refreshState(dir, null)
        assertEquals(VpnRuntimeState.Phase.Stopped, VpnRuntimeState.phase.value)
        assertNull(VpnRuntimeState.message.value)
    }

    @Test
    fun `file channel wins over stale prefs`() {
        // prefs 里是旧的 Running（旧版本 UI 的回退通道残留），文件是最新的 Error
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("phase", "Running").putString("last_error", "old").commit()

        VpnRuntimeState.publishState(dir, null, VpnRuntimeState.Phase.Error, "启动失败")

        VpnRuntimeState.refreshState(dir, prefs)
        assertEquals(VpnRuntimeState.Phase.Error, VpnRuntimeState.phase.value)
        assertEquals("启动失败", VpnRuntimeState.message.value)
    }

    @Test
    fun `missing file falls back to prefs`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("phase", "Running").putString("last_error", "ok").commit()

        // 一个没有状态文件的目录（模拟 :core 尚未发布过文件）
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "sbai-vpn-empty-${System.nanoTime()}")
        emptyDir.mkdirs()

        VpnRuntimeState.refreshState(emptyDir, prefs)
        assertEquals(VpnRuntimeState.Phase.Running, VpnRuntimeState.phase.value)
        assertEquals("ok", VpnRuntimeState.message.value)
    }

    @Test
    fun `corrupt file falls back to prefs`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("phase", "Starting").putString("last_error", null).commit()
        File(dir, "sb-ai-vpn-state.json").writeText("{ not json at all")

        VpnRuntimeState.refreshState(dir, prefs)
        assertEquals(VpnRuntimeState.Phase.Starting, VpnRuntimeState.phase.value)
    }

    @Test
    fun `no file and no prefs defaults to Stopped`() {
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "sbai-vpn-none-${System.nanoTime()}")
        emptyDir.mkdirs()

        VpnRuntimeState.refreshState(emptyDir, null)
        assertEquals(VpnRuntimeState.Phase.Stopped, VpnRuntimeState.phase.value)
    }
}
