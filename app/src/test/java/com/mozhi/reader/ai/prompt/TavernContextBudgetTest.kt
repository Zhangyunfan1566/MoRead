package com.mozhi.reader.ai.prompt

import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史窗口：条数与长度双约束。
 *
 * 两条硬约束都要在这里钉住——**起点必须落在 user 上**（否则会产生"有 tool 结果没 tool 调用"
 * 的历史，各家协议都拒绝）、**至少带上最后一条**（预算再小也不能发空历史）。
 */
class TavernContextBudgetTest {

    private fun user(text: String) = ChatMessage(ChatRole.USER, text)
    private fun assistant(text: String) = ChatMessage(ChatRole.ASSISTANT, text)

    // ---------- 预算换算 ----------

    @Test
    fun `预设写的上下文比默认大时用默认值`() {
        // 实测那份预设写的是 2000000——"当作无限大"的填法很常见，照搬等于没有预算
        assertEquals(TavernContextBudget.DEFAULT_BUDGET_CHARS, TavernContextBudget.budgetChars(2_000_000))
    }

    @Test
    fun `预设写的上下文更小时尊重预设`() {
        // 4000 token × 4 字符 = 16000 字符，比默认的 24000 小 → 采用预设值
        assertEquals(4_000 * TavernContextBudget.CHARS_PER_TOKEN, TavernContextBudget.budgetChars(4_000))
        // 8192 token 换算成 32768 字符，比默认大 → 仍然用默认
        assertEquals(TavernContextBudget.DEFAULT_BUDGET_CHARS, TavernContextBudget.budgetChars(8_192))
    }

    @Test
    fun `没写上下文或写了非法值时用默认值`() {
        assertEquals(TavernContextBudget.DEFAULT_BUDGET_CHARS, TavernContextBudget.budgetChars(null))
        assertEquals(TavernContextBudget.DEFAULT_BUDGET_CHARS, TavernContextBudget.budgetChars(0))
        assertEquals(TavernContextBudget.DEFAULT_BUDGET_CHARS, TavernContextBudget.budgetChars(-5))
    }

    @Test
    fun `回退值为零表示不限`() {
        assertEquals(0, TavernContextBudget.budgetChars(1_000, fallback = 0))
    }

    @Test
    fun `长度统计把每条加一个分隔符`() {
        assertEquals(12, TavernContextBudget.lengthOf(listOf(user("12345"), user("12345"))))
    }

    // ---------- 窗口起点 ----------

    @Test
    fun `空列表返回零`() {
        assertEquals(0, TavernContextBudget.windowStart(emptyList(), 20, 24_000))
    }

    @Test
    fun `都没超时整段带上`() {
        val messages = listOf(user("a"), assistant("b"), user("c"))
        assertEquals(0, TavernContextBudget.windowStart(messages, 20, 24_000))
    }

    @Test
    fun `条数限制生效且起点落在 user 上`() {
        val messages = (0 until 30).map { if (it % 2 == 0) user("m" + it) else assistant("m" + it) }
        val start = TavernContextBudget.windowStart(messages, 20, 0)
        assertEquals(10, start)
        assertEquals(ChatRole.USER, messages[start].role)
        assertEquals(20, messages.size - start)
    }

    @Test
    fun `长度限制生效`() {
        val messages = (0 until 5).map { user("x".repeat(100)) }
        // 每条 101 字符，预算 250 → 从末尾数只能装 2 条，起点 3
        assertEquals(3, TavernContextBudget.windowStart(messages, 100, 250))
    }

    @Test
    fun `预算再小也至少带上最后一条`() {
        val messages = (0 until 3).map { user("x".repeat(100)) }
        val start = TavernContextBudget.windowStart(messages, 100, 50)
        assertEquals(2, start)
        assertTrue(messages.size - start >= 1)
    }

    @Test
    fun `条数与长度取更严的那个`() {
        val messages = (0 until 30).map { if (it % 2 == 0) user("x".repeat(100)) else assistant("x".repeat(100)) }
        val byCount = TavernContextBudget.windowStart(messages, 20, 0)
        val byBoth = TavernContextBudget.windowStart(messages, 20, 500)
        assertTrue(byBoth >= byCount)
        assertTrue(messages.size - byBoth <= 5)
    }

    @Test
    fun `预算为零表示不限长度`() {
        val messages = (0 until 5).map { user("x".repeat(10_000)) }
        assertEquals(0, TavernContextBudget.windowStart(messages, 20, 0))
    }

    @Test
    fun `完全没有 user 消息时宁可整段带上`() {
        val messages = (0 until 30).map { assistant("m" + it) }
        assertEquals(0, TavernContextBudget.windowStart(messages, 20, 0))
    }

    @Test
    fun `起点优先往后对齐到最近的 user`() {
        // 条数算出来是 1（assistant）→ 往后找到 2 的 user：既满足条数，又不把开头那条白带上
        val messages = listOf(user("a"), assistant("b"), user("c"), assistant("d"))
        val start = TavernContextBudget.windowStart(messages, 3, 0)
        assertEquals(2, start)
        assertEquals(ChatRole.USER, messages[start].role)
        assertEquals(2, messages.size - start)
    }

    @Test
    fun `后面没有 user 了才往前退`() {
        val messages = listOf(user("a"), user("b"), assistant("c"), assistant("d"))
        // 条数算出来是 2（assistant）→ 往后没有 user，往前退到 1
        val start = TavernContextBudget.windowStart(messages, 2, 0)
        assertEquals(1, start)
        assertEquals(ChatRole.USER, messages[start].role)
    }

    @Test
    fun `一条超长历史消息不会让预算形同虚设`() {
        // 往前对齐会把这条 20 万字符的消息又拉回来；往后对齐只带最后一条 user
        val messages = listOf(user("x".repeat(200_000)), assistant("y"), user("z"))
        val start = TavernContextBudget.windowStart(messages, 20, 24_000)
        assertEquals(2, start)
        assertTrue(TavernContextBudget.lengthOf(messages.drop(start)) <= 24_000)
    }
}
