package com.mozhi.reader.ai.prompt

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书引擎。语义对齐酒馆 `world-info.js::checkWorldInfo`：
 * 激活顺序、四选一的选择逻辑、正则键、概率、order 降序、按位置分组、字符预算。
 */
class TavernWorldInfoEngineTest {

    private fun entry(
        id: String = "e",
        comment: String = "E",
        content: String = "内容",
        keys: List<String> = emptyList(),
        secondaryKeys: List<String> = emptyList(),
        selectiveLogic: Int = WorldInfoEntry.LOGIC_AND_ANY,
        constant: Boolean = false,
        disabled: Boolean = false,
        enabled: Boolean = true,
        position: Int = WorldInfoEntry.POSITION_BEFORE,
        depth: Int = WorldInfoEntry.DEFAULT_DEPTH,
        order: Int = WorldInfoEntry.DEFAULT_ORDER,
        caseSensitive: Boolean? = null,
        matchWholeWords: Boolean? = null,
        scanDepth: Int? = null,
        probability: Int = 100,
        useProbability: Boolean = true,
        role: Int = WorldInfoEntry.ROLE_SYSTEM
    ) = WorldInfoEntry(
        id = id, comment = comment, content = content, keys = keys, secondaryKeys = secondaryKeys,
        selectiveLogic = selectiveLogic, constant = constant, disabled = disabled, enabled = enabled,
        position = position, depth = depth, order = order, caseSensitive = caseSensitive,
        matchWholeWords = matchWholeWords, scanDepth = scanDepth, probability = probability,
        useProbability = useProbability, role = role
    )

    private fun scan(entries: List<WorldInfoEntry>, messages: List<String> = listOf("我在魔法森林里遇到了暗牙兽"),
        options: WorldInfoEngine.Options = WorldInfoEngine.Options()) =
        WorldInfoEngine.scan(entries, messages, options)

    // ---------- ① 关键词与激活顺序 ----------

    @Test
    fun `主键任一命中就激活`() {
        val result = scan(listOf(entry(keys = listOf("森林", "沙漠"))))
        assertEquals(1, result.size)
        assertEquals("森林", result.activations.first().matchedKey)
    }

    @Test
    fun `主键都不命中就不激活`() {
        assertTrue(scan(listOf(entry(keys = listOf("沙漠")))).isEmpty)
    }

    @Test
    fun `常驻条目不看关键词`() {
        val result = scan(listOf(entry(keys = emptyList(), constant = true)), messages = listOf("随便"))
        assertEquals(1, result.size)
    }

    @Test
    fun `没有主键又没有 constant 就不激活`() {
        assertTrue(scan(listOf(entry(keys = emptyList()))).isEmpty)
    }

    @Test
    fun `禁用条目与空内容都不激活`() {
        assertTrue(scan(listOf(entry(keys = listOf("森林"), disabled = true))).isEmpty)
        assertTrue(scan(listOf(entry(keys = listOf("森林"), enabled = false))).isEmpty)
        assertTrue(scan(listOf(entry(keys = listOf("森林"), content = "   "))).isEmpty)
    }

    // ---------- ② 副键与四种选择逻辑 ----------

    private fun withSecondary(logic: Int, secondary: List<String>) =
        scan(listOf(entry(keys = listOf("森林"), secondaryKeys = secondary, selectiveLogic = logic)))

    @Test
    fun `AND_ANY 副键任一命中`() {
        assertEquals(1, withSecondary(WorldInfoEntry.LOGIC_AND_ANY, listOf("暗牙兽", "石头")).size)
        assertTrue(withSecondary(WorldInfoEntry.LOGIC_AND_ANY, listOf("石头", "树木")).isEmpty)
    }

    @Test
    fun `NOT_ALL 副键任一未命中`() {
        assertEquals(1, withSecondary(WorldInfoEntry.LOGIC_NOT_ALL, listOf("暗牙兽", "石头")).size)
        assertTrue(withSecondary(WorldInfoEntry.LOGIC_NOT_ALL, listOf("暗牙兽", "森林")).isEmpty)
    }

    @Test
    fun `NOT_ANY 副键全不命中`() {
        assertEquals(1, withSecondary(WorldInfoEntry.LOGIC_NOT_ANY, listOf("石头", "树木")).size)
        assertTrue(withSecondary(WorldInfoEntry.LOGIC_NOT_ANY, listOf("暗牙兽", "石头")).isEmpty)
    }

    @Test
    fun `AND_ALL 副键全命中`() {
        assertEquals(1, withSecondary(WorldInfoEntry.LOGIC_AND_ALL, listOf("暗牙兽", "森林")).size)
        assertTrue(withSecondary(WorldInfoEntry.LOGIC_AND_ALL, listOf("暗牙兽", "石头")).isEmpty)
    }

    @Test
    fun `副键为空时主键命中即激活`() {
        assertEquals(1, withSecondary(WorldInfoEntry.LOGIC_AND_ALL, emptyList()).size)
    }

    // ---------- ③ 匹配细节 ----------

    @Test
    fun `默认忽略大小写`() {
        assertEquals(1, scan(listOf(entry(keys = listOf("eldoria"))), listOf("Welcome to Eldoria")).size)
    }

    @Test
    fun `caseSensitive 开启后区分大小写`() {
        val e = entry(keys = listOf("eldoria"), caseSensitive = true)
        assertTrue(scan(listOf(e), listOf("Welcome to Eldoria")).isEmpty)
        assertEquals(1, scan(listOf(e), listOf("welcome to eldoria")).size)
    }

    @Test
    fun `整词匹配不会命中词内片段`() {
        val e = entry(keys = listOf("wood"), matchWholeWords = true)
        assertTrue(scan(listOf(e), listOf("a woodelf appeared")).isEmpty)
        assertEquals(1, scan(listOf(e), listOf("a wood elf appeared")).size)
    }

    @Test
    fun `整词匹配对多词键退化为包含`() {
        val e = entry(keys = listOf("magical forest"), matchWholeWords = true)
        assertEquals(1, scan(listOf(e), listOf("inside the magical forest now")).size)
    }

    @Test
    fun `斜杠写法的键走正则`() {
        val e = entry(keys = listOf("/暗牙(兽|群)/"))
        assertEquals(1, scan(listOf(e), listOf("远处出现暗牙群")).size)
        assertTrue(scan(listOf(e), listOf("远处出现野狼")).isEmpty)
    }

    @Test
    fun `编不过的正则键退化成普通文本而不是废掉条目`() {
        val e = entry(keys = listOf("/([unclosed"))
        assertEquals(1, scan(listOf(e), listOf("看到 ([unclosed 这串")).size)
    }

    // ---------- ④ 概率 ----------

    @Test
    fun `概率一百与关闭概率开关都必定激活`() {
        assertEquals(1, scan(listOf(entry(keys = listOf("森林"), probability = 100))).size)
        assertEquals(1, scan(listOf(entry(keys = listOf("森林"), probability = 1, useProbability = false))).size)
    }

    @Test
    fun `概率为零永不激活`() {
        assertTrue(scan(listOf(entry(keys = listOf("森林"), probability = 0))).isEmpty)
    }

    @Test
    fun `概率判定用可注入的随机源`() {
        val e = entry(keys = listOf("森林"), probability = 50)
        // nextDouble() 固定返回 0.4 → 40 <= 50 通过
        val pass = WorldInfoEngine.scan(listOf(e), listOf("森林"), WorldInfoEngine.Options(), FixedRandom(0.4))
        assertEquals(1, pass.size)
        // 0.6 → 60 <= 50 不通过
        val fail = WorldInfoEngine.scan(listOf(e), listOf("森林"), WorldInfoEngine.Options(), FixedRandom(0.6))
        assertTrue(fail.isEmpty)
    }

    // ---------- ⑤ 顺序、位置、深度 ----------

    @Test
    fun `order 大的先插入`() {
        val result = scan(
            listOf(
                entry(id = "low", content = "低", keys = listOf("森林"), order = 10),
                entry(id = "high", content = "高", keys = listOf("森林"), order = 200)
            )
        )
        assertEquals(listOf("高", "低"), result.before.map { it.content })
    }

    @Test
    fun `按位置分成 before after atDepth`() {
        val result = scan(
            listOf(
                entry(id = "b", content = "前", keys = listOf("森林"), position = WorldInfoEntry.POSITION_BEFORE),
                entry(id = "a", content = "后", keys = listOf("森林"), position = WorldInfoEntry.POSITION_AFTER),
                entry(id = "d", content = "深", keys = listOf("森林"), position = WorldInfoEntry.POSITION_AT_DEPTH, depth = 3)
            )
        )
        assertEquals(listOf("前"), result.before.map { it.content })
        assertEquals(listOf("后"), result.after.map { it.content })
        assertEquals(listOf("深"), result.atDepth.map { it.content })
        assertEquals(3, result.atDepth.first().depth)
    }

    @Test
    fun `作者注位置按之前处理并给出提示`() {
        val result = scan(listOf(entry(keys = listOf("森林"), position = WorldInfoEntry.POSITION_AN_TOP)))
        assertEquals(1, result.before.size)
        assertTrue(result.notes.any { it.contains("插入位置") })
    }

    @Test
    fun `outlet 位置不注入并记进提示`() {
        val result = scan(listOf(entry(keys = listOf("森林"), position = WorldInfoEntry.POSITION_OUTLET)))
        assertTrue(result.isEmpty)
        assertTrue(result.notes.any { it.contains("outlet") || it.contains("插入位置") })
    }

    // ---------- ⑥ 扫描深度 ----------

    @Test
    fun `只扫最近 scanDepth 条消息`() {
        val messages = listOf("森林", "平原", "沙漠")
        // 全局深度 1 → 只看最后一条「沙漠」，森林不该命中
        assertTrue(scan(listOf(entry(keys = listOf("森林"))), messages, WorldInfoEngine.Options(scanDepth = 1)).isEmpty)
        assertEquals(1, scan(listOf(entry(keys = listOf("森林"))), messages, WorldInfoEngine.Options(scanDepth = 3)).size)
    }

    @Test
    fun `条目自己的 scanDepth 覆盖全局`() {
        val messages = listOf("森林", "平原", "沙漠")
        val e = entry(keys = listOf("森林"), scanDepth = 3)
        assertEquals(1, scan(listOf(e), messages, WorldInfoEngine.Options(scanDepth = 1)).size)
    }

    @Test
    fun `深度为零时不扫任何消息`() {
        assertTrue(scan(listOf(entry(keys = listOf("森林"))), listOf("森林"), WorldInfoEngine.Options(scanDepth = 0)).isEmpty)
    }

    // ---------- ⑦ 预算 ----------

    @Test
    fun `超出字符预算后不再注入并给出提示`() {
        val entries = (1..5).map { index ->
            entry(id = "e" + index, content = "x".repeat(100), keys = listOf("森林"), order = 100 - index)
        }
        val result = WorldInfoEngine.scan(entries, listOf("森林"), WorldInfoEngine.Options(maxChars = 250))
        assertEquals(2, result.size)
        assertTrue(result.budgetStopped)
        assertTrue(result.notes.any { it.contains("预算") })
    }

    // ---------- ⑧ 预览入口 ----------

    @Test
    fun `activationFor 可以单独判定一条`() {
        val hit = WorldInfoEngine.activationFor(entry(keys = listOf("森林")), "走进森林", WorldInfoEngine.Options())
        assertEquals("森林", hit?.matchedKey)
        assertNull(WorldInfoEngine.activationFor(entry(keys = listOf("沙漠")), "走进森林", WorldInfoEngine.Options()))
    }

    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }
}
