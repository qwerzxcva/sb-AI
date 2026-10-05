package com.sbai

import com.sbai.service.NodeSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeSwitcherTest {

    private fun items(vararg tags: String) = tags.map { NodeSwitcher.Item(it, true) }

    @Test
    fun `next cycles forward`() {
        val list = items("a", "b", "c")
        assertEquals("b", NodeSwitcher.nextTag(list, "a", forward = true))
        assertEquals("c", NodeSwitcher.nextTag(list, "b", forward = true))
        assertEquals("a", NodeSwitcher.nextTag(list, "c", forward = true))  // 循环
    }

    @Test
    fun `prev cycles backward`() {
        val list = items("a", "b", "c")
        assertEquals("c", NodeSwitcher.nextTag(list, "a", forward = false))
        assertEquals("a", NodeSwitcher.nextTag(list, "b", forward = false))
        assertEquals("b", NodeSwitcher.nextTag(list, "c", forward = false))
    }

    @Test
    fun `null selected picks first or last`() {
        val list = items("a", "b", "c")
        assertEquals("a", NodeSwitcher.nextTag(list, null, forward = true))
        assertEquals("c", NodeSwitcher.nextTag(list, null, forward = false))
    }

    @Test
    fun `skips non-selectable items`() {
        val list = listOf(
            NodeSwitcher.Item("a", true),
            NodeSwitcher.Item("group", false),  // 组，不可切
            NodeSwitcher.Item("b", true),
        )
        assertEquals("b", NodeSwitcher.nextTag(list, "a", forward = true))  // 跳过 group
        assertEquals("a", NodeSwitcher.nextTag(list, "b", forward = true))
    }

    @Test
    fun `selected not in selectable list falls back to first or last`() {
        val list = listOf(
            NodeSwitcher.Item("a", true),
            NodeSwitcher.Item("group", false),
        )
        // selected 是 "group"（不可切），idx<0 → 从头部/尾部
        assertEquals("a", NodeSwitcher.nextTag(list, "group", forward = true))
        assertEquals("a", NodeSwitcher.nextTag(list, "group", forward = false))
    }

    @Test
    fun `single selectable returns null`() {
        val list = items("a")
        assertNull(NodeSwitcher.nextTag(list, "a", forward = true))
    }

    @Test
    fun `empty returns null`() {
        assertNull(NodeSwitcher.nextTag(emptyList(), null, forward = true))
    }
}
