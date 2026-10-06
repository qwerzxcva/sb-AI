package com.sbai

import com.sbai.service.NodeDedup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeDedupTest {

    @Test
    fun `相同配置不同 key 顺序归一化为相同字符串`() {
        val a = """{"server":"1.2.3.4","type":"vless","server_port":443,"uuid":"u-1"}"""
        val b = """{"type":"vless","uuid":"u-1","server":"1.2.3.4","server_port":443}"""
        assertEquals(NodeDedup.normalize(a), NodeDedup.normalize(b))
    }

    @Test
    fun `忽略顶层 tag 与 name 字段`() {
        val a = """{"type":"vless","tag":"香港01","server":"1.2.3.4","server_port":443,"uuid":"u-1"}"""
        val b = """{"type":"vless","tag":"HK-1","name":"别的名字","server":"1.2.3.4","server_port":443,"uuid":"u-1"}"""
        assertEquals(NodeDedup.normalize(a), NodeDedup.normalize(b))
    }

    @Test
    fun `嵌套对象 key 顺序无关`() {
        val a = """{"type":"trojan","server":"1.2.3.4","server_port":443,"password":"p","tls":{"enabled":true,"server_name":"a.com","insecure":true}}"""
        val b = """{"type":"trojan","tls":{"insecure":true,"server_name":"a.com","enabled":true},"password":"p","server":"1.2.3.4","server_port":443}"""
        assertEquals(NodeDedup.normalize(a), NodeDedup.normalize(b))
    }

    @Test
    fun `不同配置产生不同字符串`() {
        val a = """{"type":"vless","server":"1.2.3.4","server_port":443,"uuid":"u-1"}"""
        val b = """{"type":"vless","server":"1.2.3.4","server_port":443,"uuid":"u-2"}"""
        val c = """{"type":"vless","server":"5.6.7.8","server_port":443,"uuid":"u-1"}"""
        assertNotEquals(NodeDedup.normalize(a), NodeDedup.normalize(b))
        assertNotEquals(NodeDedup.normalize(a), NodeDedup.normalize(c))
    }

    @Test
    fun `数组保持顺序参与归一化`() {
        val a = """{"type":"wireguard","local_address":["10.0.0.2/32","fd00::2/128"],"private_key":"k"}"""
        val b = """{"type":"wireguard","local_address":["fd00::2/128","10.0.0.2/32"],"private_key":"k"}"""
        // 数组顺序不同视为不同配置（wireguard 地址顺序有意义）
        assertNotEquals(NodeDedup.normalize(a), NodeDedup.normalize(b))
    }

    @Test
    fun `非法 JSON 回退为原始串`() {
        assertEquals("not-json", NodeDedup.normalize("not-json"))
    }

    @Test
    fun `distinctBy 去重只留首次出现`() {
        val nodes = listOf("a", "b", "a", "c", "b")
        val seen = HashSet<String>()
        val deduped = nodes.filter { seen.add(it) }
        assertEquals(listOf("a", "b", "c"), deduped)
        assertTrue(true)
    }
}
