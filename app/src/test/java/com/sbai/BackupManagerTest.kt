package com.sbai

import com.sbai.data.AppSettings
import com.sbai.data.AppState
import com.sbai.data.ProxyNode
import com.sbai.data.RouteRule
import com.sbai.data.RuleAction
import com.sbai.data.ThemeMode
import com.sbai.service.BackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    @Test
    fun `merge appends non-overlapping lists`() {
        val current = AppState(
            routeRules = listOf(RouteRule(id = "a", name = "rule-a", domains = listOf("a.com"))),
        )
        val incoming = AppState(
            routeRules = listOf(RouteRule(id = "b", name = "rule-b", domains = listOf("b.com"))),
        )
        val merged = BackupManager.merge(current, incoming)
        assertEquals(2, merged.routeRules.size)
        assertEquals(listOf("a", "b"), merged.routeRules.map { it.id })
    }

    @Test
    fun `merge keeps existing on id conflict`() {
        val current = AppState(
            routeRules = listOf(RouteRule(id = "a", name = "mine", domains = listOf("mine.com"))),
        )
        val incoming = AppState(
            routeRules = listOf(RouteRule(id = "a", name = "theirs", domains = listOf("theirs.com"))),
        )
        val merged = BackupManager.merge(current, incoming)
        assertEquals(1, merged.routeRules.size)
        assertEquals("mine", merged.routeRules[0].name)  // 现有优先
    }

    @Test
    fun `merge deletes nothing`() {
        val current = AppState(
            proxyNodes = listOf(
                ProxyNode(id = "n1", name = "n1"),
                ProxyNode(id = "n2", name = "n2"),
            ),
        )
        val incoming = AppState(proxyNodes = listOf(ProxyNode(id = "n3", name = "n3")))
        val merged = BackupManager.merge(current, incoming)
        assertEquals(3, merged.proxyNodes.size)  // n1/n2 保留 + n3 追加
    }

    @Test
    fun `merge settings only overrides non-default values`() {
        val current = AppSettings(mtu = 9000, themeMode = ThemeMode.DARK)
        val incoming = AppSettings(mtu = 1400)  // 只改了 mtu
        val merged = BackupManager.merge(AppState(settings = current), AppState(settings = incoming))
        assertEquals(1400, merged.settings.mtu)           // 导入的非默认值覆盖
        assertEquals(ThemeMode.DARK, merged.settings.themeMode)  // 导入默认值不覆盖现有
    }

    @Test
    fun `merge load balance defaults to current when incoming is default`() {
        val current = AppState(
            loadBalance = com.sbai.data.LoadBalanceConfig(enabled = true, checkUrl = "https://x.com"),
        )
        val merged = BackupManager.merge(current, AppState())
        assertTrue(merged.loadBalance.enabled)
        assertEquals("https://x.com", merged.loadBalance.checkUrl)
    }

    @Test
    fun `merge keeps active profile id from incoming when set`() {
        val current = AppState(activeProfileId = "")
        val incoming = AppState(activeProfileId = "p1")
        val merged = BackupManager.merge(current, incoming)
        assertEquals("p1", merged.activeProfileId)
    }

    @Test
    fun `merge incoming active profile empty keeps current`() {
        val current = AppState(activeProfileId = "p1")
        val merged = BackupManager.merge(current, AppState())
        assertEquals("p1", merged.activeProfileId)
    }
}
