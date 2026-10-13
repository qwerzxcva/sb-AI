package com.sbai

import com.sbai.data.AppState
import com.sbai.data.AppSettings
import com.sbai.data.RouteMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppState 序列化健壮性：新增枚举字段后，旧配置必须仍能解析。
 *
 * 背景：RuleStore.load() 解析失败会回退 AppState()，等于用户全部配置静默丢失。
 * ignoreUnknownKeys 只处理未知 key，不处理未知枚举值；必须靠 coerceInputValues。
 */
class AppStateSerializationTest {

    private val lenient = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test
    fun `unknown enum value falls back to default instead of failing`() {
        // 旧版本配置里 routeMode 可能存着不存在的值（或未来删掉某个枚举值）
        val raw = """{"settings":{"routeMode":"BOGUS_MODE","mtu":1500}}"""
        val state = lenient.decodeFromString(AppState.serializer(), raw)
        assertEquals(RouteMode.RULES, state.settings.routeMode)
        assertEquals(1500, state.settings.mtu)
    }

    @Test
    fun `missing enum field falls back to default`() {
        val raw = """{"settings":{"mtu":9000}}"""
        val state = lenient.decodeFromString(AppState.serializer(), raw)
        assertEquals(RouteMode.RULES, state.settings.routeMode)
    }

    @Test
    fun `unknown keys are ignored and known fields survive`() {
        val raw = """{"settings":{"mtu":1420,"someRemovedField":123}}"""
        val state = lenient.decodeFromString(AppState.serializer(), raw)
        assertEquals(1420, state.settings.mtu)
    }

    @Test
    fun `route mode round trip preserves value`() {
        RouteMode.entries.forEach { mode ->
            val encoded = lenient.encodeToString(AppSettings.serializer(), AppSettings(routeMode = mode))
            val decoded = lenient.decodeFromString(AppSettings.serializer(), encoded)
            assertEquals(mode, decoded.routeMode)
        }
    }

    @Test
    fun `default state encodes and decodes identically`() {
        val original = AppState()
        val encoded = lenient.encodeToString(AppState.serializer(), original)
        val decoded = lenient.decodeFromString(AppState.serializer(), encoded)
        assertEquals(original.settings.routeMode, decoded.settings.routeMode)
        assertTrue(decoded.proxyNodes.isEmpty())
    }
}
