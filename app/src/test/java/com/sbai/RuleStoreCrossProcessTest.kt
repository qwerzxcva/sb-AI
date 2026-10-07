package com.sbai

import com.sbai.data.AppState
import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import com.sbai.data.RouteRule
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 验证 RuleStore 跨进程「原子文件 + 写前 rebase」设计（P0 根因修复）：
 *
 * 旧实现（SharedPreferences）下：:core 自动禁用坏节点后，UI 进程内存缓存仍是旧值，
 * UI 的下次写入会用旧内存整份覆盖 :core 的变更 → 自动禁用被静默撤销、VPN 反复起不来。
 *
 * 新实现：两个 RuleStore 实例（writerTag="ui" / "core"）共享同一目录 = 模拟两个进程。
 * 每次写入前 rebaseOnDisk 检查文件 mtime，外部有更新则先载入磁盘态，保证不丢更新。
 */
class RuleStoreCrossProcessTest {

    private lateinit var dir: File
    private lateinit var ui: RuleStore
    private lateinit var core: RuleStore

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "sbai-rules-${System.nanoTime()}")
        if (dir.exists()) dir.deleteRecursively()
        assertTrue(dir.mkdirs())
        ui = RuleStore.forTesting(dir, "ui", FakeSharedPreferences())
        core = RuleStore.forTesting(dir, "core", FakeSharedPreferences())
    }

    private fun node(name: String, tag: String) = ProxyNode(
        name = name,
        outboundJson = """{"type":"vless","tag":"$tag","server":"1.2.3.4","server_port":443,"uuid":"x"}""",
    )

    /** 等待两个连续写入的 updatedAt 严格递增（System.currentTimeMillis 分辨率 1ms）。 */
    private fun settle() = Thread.sleep(5)

    @Test
    fun `ui update rebases on core external write (no lost update)`() {
        // 1) UI 侧添加一个启用节点（同步落盘）
        val bad = node("bad", "bad-node")
        ui.upsertProxyNode(bad)
        settle()

        // 2) :core 启动 VPN 时重载配置并自动禁用该坏节点（模拟 NodeAutoDisabler 路径）
        val reloaded = core.reload()
        val badInCore = reloaded.proxyNodes.first { it.id == bad.id }
        core.updateCommitted { s ->
            s.copy(
                proxyNodes = s.proxyNodes.map { n ->
                    if (n.id == badInCore.id) n.copy(enabled = false, disabledReason = "dial: connection refused") else n
                },
            )
        }

        // 3) UI 内存里仍是「bad 启用」的旧值；用户再添加一个好节点（触发写前 rebase）
        settle()
        ui.upsertProxyNode(node("good", "good-node"))

        val final = ui.state.value
        val finalBad = final.proxyNodes.first { it.id == bad.id }
        assertFalse("rebase 必须保留 :core 的禁用变更", finalBad.enabled)
        assertEquals("dial: connection refused", finalBad.disabledReason)
        assertTrue("UI 自身写入不能丢", final.proxyNodes.any { it.name == "good" })
    }

    @Test
    fun `refreshFromDisk picks up external write exactly once`() {
        ui.upsertProxyNode(node("a", "node-a"))
        settle()

        core.updateCommitted { s ->
            s.copy(proxyNodes = s.proxyNodes.map { if (it.name == "a") it.copy(enabled = false, disabledReason = "refused") else it })
        }

        val first = ui.refreshFromDisk()
        val second = ui.refreshFromDisk()
        assertTrue("外部写入必须被感知", first)
        assertFalse("同一外部写入只能刷新一次", second)

        val a = ui.state.value.proxyNodes.first { it.name == "a" }
        assertFalse(a.enabled)
        assertNotNull(a.disabledReason)
    }

    @Test
    fun `reload always reads latest disk state`() {
        ui.upsertProxyNode(node("a", "node-a"))
        ui.upsertProxyNode(node("b", "node-b"))
        settle()

        // :core 冷启动：全量读盘必须拿到 UI 刚写的两个节点
        val state = core.reload()
        assertEquals(2, state.proxyNodes.size)
        assertTrue(state.proxyNodes.all { it.name in setOf("a", "b") })
    }

    @Test
    fun `core write after ui write does not clobber ui state`() {
        // UI 写一条规则
        val rule = RouteRule(name = "r1")
        ui.upsertRouteRule(rule)
        settle()

        // :core 只改节点（rebase 基于最新磁盘态），规则必须保留
        core.reload()
        core.updateCommitted { s ->
            s.copy(proxyNodes = s.proxyNodes + node("core-node", "core-node"))
        }

        // UI 再读回，规则与节点都在
        assertTrue(ui.refreshFromDisk())
        val merged = ui.state.value
        assertTrue("rebase 不能丢 UI 的规则", merged.routeRules.any { it.id == rule.id })
        assertTrue("core 的节点必须合并进来", merged.proxyNodes.any { it.name == "core-node" })
    }

    @Test
    fun `legacy prefs migration writes envelope file once`() {
        val prefs = FakeSharedPreferences()
        val legacy = AppState(proxyNodes = listOf(node("legacy", "legacy-node")))
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        prefs.edit().putString("app_state", json.encodeToString(AppState.serializer(), legacy)).commit()

        val store = RuleStore.forTesting(dir, "ui", prefs)
        val state = store.state.value
        assertEquals(1, state.proxyNodes.size)
        assertEquals("legacy", state.proxyNodes.first().name)

        // 迁移后磁盘信封文件必须存在，且后续写入仍有效
        val file = File(dir, "sb-ai-state.json")
        assertTrue("迁移必须生成新状态文件", file.exists())
        store.upsertProxyNode(node("new", "new-node"))
        settle()
        val reread = RuleStore.forTesting(dir, "core", FakeSharedPreferences()).reload()
        assertEquals(2, reread.proxyNodes.size)
    }

    @Test
    fun `corrupt envelope is quarantined and falls back to legacy prefs`() {
        val prefs = FakeSharedPreferences()
        val legacy = AppState(proxyNodes = listOf(node("legacy", "legacy-node")))
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        prefs.edit().putString("app_state", json.encodeToString(AppState.serializer(), legacy)).commit()

        File(dir, "sb-ai-state.json").writeText("{ broken json !!!")

        val store = RuleStore.forTesting(dir, "ui", prefs)
        val state = store.state.value
        assertEquals("损坏文件不能覆盖为空白（会丢用户数据）", 1, state.proxyNodes.size)
        assertEquals("legacy", state.proxyNodes.first().name)

        // 隔离备份文件已生成（不覆盖原始内容）
        val quarantined = dir.listFiles { f -> f.name.startsWith("sb-ai-state.json.corrupt.") }
        assertTrue("损坏文件必须被隔离备份", quarantined != null && quarantined.isNotEmpty())
    }
}
