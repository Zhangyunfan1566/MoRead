package com.mozhi.reader.ai.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书导入。兼容酒馆 v1 导出（`entries` 是 **uid 为键的对象**）、数组形式、根就是数组，
 * 以及 v2 / 角色卡的字段别名。**字段不认识就忽略，但不丢条目。**
 */
class TavernWorldInfoImporterTest {

    /** 酒馆 v1 的真实形状：entries 是对象。 */
    private val v1 = """
    {
      "entries": {
        "0": {"uid": 0, "key": ["eldoria", "forest"], "keysecondary": [], "comment": "eldoria",
              "content": "Eldoria 是一片魔法森林。", "constant": false, "selective": true, "order": 100,
              "position": 0, "disable": false, "depth": 4, "probability": 100, "useProbability": true},
        "1": {"uid": 1, "key": ["shadowfang"], "keysecondary": ["night"], "comment": "shadowfang",
              "content": "暗牙兽夜里出没。", "constant": false, "selectiveLogic": 3, "order": 50,
              "position": 4, "depth": 2, "role": 1, "disable": false},
        "2": {"uid": 2, "key": [], "comment": "常驻设定", "content": "这个世界有两轮月亮。",
              "constant": true, "order": 10, "position": 1, "disable": false}
      }
    }
    """.trimIndent()

    @Test
    fun `v1 的对象形状被正确解析`() {
        val result = TavernWorldInfoImporter.import(v1)
        assertEquals(3, result.entries.size)
        val eldoria = result.entries.first { it.id == "0" }
        assertEquals(listOf("eldoria", "forest"), eldoria.keys)
        assertEquals(WorldInfoEntry.POSITION_BEFORE, eldoria.position)
        assertEquals(100, eldoria.order)
        assertFalse(eldoria.constant)
    }

    @Test
    fun `副键与选择逻辑被保留`() {
        val shadowfang = TavernWorldInfoImporter.import(v1).entries.first { it.id == "1" }
        assertEquals(listOf("night"), shadowfang.secondaryKeys)
        assertEquals(WorldInfoEntry.LOGIC_AND_ALL, shadowfang.selectiveLogic)
        assertEquals(WorldInfoEntry.POSITION_AT_DEPTH, shadowfang.position)
        assertEquals(2, shadowfang.depth)
        assertEquals(WorldInfoEntry.ROLE_USER, shadowfang.role)
    }

    @Test
    fun `常驻条目与位置被保留`() {
        val constant = TavernWorldInfoImporter.import(v1).entries.first { it.id == "2" }
        assertTrue(constant.constant)
        assertEquals(WorldInfoEntry.POSITION_AFTER, constant.position)
    }

    @Test
    fun `数组形式与根数组都能解析`() {
        val array = """{"name":"小世界书","entries":[{"uid":7,"key":["a"],"content":"A","position":1}]}"""
        val fromArray = TavernWorldInfoImporter.import(array)
        assertEquals(1, fromArray.entries.size)
        assertEquals("小世界书", fromArray.sourceName)
        assertEquals(WorldInfoEntry.POSITION_AFTER, fromArray.entries.first().position)

        val root = """[{"uid":8,"key":["b"],"content":"B"}]"""
        assertEquals(1, TavernWorldInfoImporter.import(root).entries.size)
    }

    @Test
    fun `v2 与角色卡的字段别名也认`() {
        val aliased = """
        {"entries":[
          {"id":"x1","keys":["魔法"],"secondary_keys":["夜"],"content":"别名条目",
           "insertion_order":7,"enabled":false,"name":"备注名","match_whole_words":true}
        ]}
        """.trimIndent()
        val entry = TavernWorldInfoImporter.import(aliased).entries.single()
        assertEquals(listOf("魔法"), entry.keys)
        assertEquals(listOf("夜"), entry.secondaryKeys)
        assertEquals(7, entry.order)
        assertFalse(entry.enabled)
        assertEquals("备注名", entry.comment)
    }

    @Test
    fun `disable 与 enabled 都算关`() {
        val text = """{"entries":[
          {"uid":1,"key":["a"],"content":"A","disable":true},
          {"uid":2,"key":["b"],"content":"B","enabled":false}
        ]}"""
        val entries = TavernWorldInfoImporter.import(text).entries
        assertTrue(entries.all { it.disabled || !it.enabled })
    }

    @Test
    fun `空 content 的条目被跳过并给出原因`() {
        val text = """{"entries":[{"uid":1,"key":["a"],"content":"   ","comment":"空的"}]}"""
        val error = runCatching { TavernWorldInfoImporter.import(text) }.exceptionOrNull()
        assertTrue(error is TavernWorldInfoImporter.ImportException)
    }

    @Test
    fun `不是 JSON 或没有 entries 都给明确错误`() {
        val e1 = runCatching { TavernWorldInfoImporter.import("{不是 json") }.exceptionOrNull()
        assertTrue(e1 is TavernWorldInfoImporter.ImportException)
        val e2 = runCatching { TavernWorldInfoImporter.import("""{"foo":1}""") }.exceptionOrNull()
        assertTrue(e2 is TavernWorldInfoImporter.ImportException)
        assertTrue(e2!!.message!!.contains("entries"))
        val e3 = runCatching { TavernWorldInfoImporter.import("  ") }.exceptionOrNull()
        assertTrue(e3 is TavernWorldInfoImporter.ImportException)
    }

    @Test
    fun `缺省字段落到酒馆默认值`() {
        val entry = TavernWorldInfoImporter.import("""{"entries":[{"uid":1,"key":["a"],"content":"A"}]}""").entries.single()
        assertEquals(WorldInfoEntry.DEFAULT_ORDER, entry.order)
        assertEquals(WorldInfoEntry.DEFAULT_DEPTH, entry.depth)
        assertEquals(WorldInfoEntry.POSITION_BEFORE, entry.position)
        assertEquals(100, entry.probability)
        assertTrue(entry.useProbability)
        assertNull(entry.caseSensitive)
        assertNull(entry.scanDepth)
    }
}
