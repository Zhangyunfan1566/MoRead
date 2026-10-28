package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用去重。要钉住三种情况：跨轮复用、同轮重复、参数规范化（键序不同的同一份参数算同一次调用）。
 */
class ToolCallDeduperTest {

    private fun call(name: String = "search_book", arguments: String = """{"query":"森林"}""", id: String = "c") =
        ToolCall(id = id, name = name, arguments = arguments)

    private fun success(text: String = "结果") = ToolResult.Success(text)

    // ---------- ① 无重复 ----------

    @Test
    fun `参数不同的调用全部照常执行`() {
        val deduper = ToolCallDeduper()
        val calls = listOf(
            call(arguments = """{"query":"森林"}""", id = "1"),
            call(arguments = """{"query":"沙漠"}""", id = "2")
        )
        val plan = deduper.plan(calls)
        assertEquals(listOf(0 to calls[0], 1 to calls[1]), plan.fresh)
        assertTrue(plan.reused.isEmpty())
        assertTrue(plan.duplicateOf.isEmpty())
    }

    @Test
    fun `工具名不同即使参数一样也不算重复`() {
        val deduper = ToolCallDeduper()
        val plan = deduper.plan(listOf(call(name = "a"), call(name = "b")))
        assertEquals(2, plan.fresh.size)
    }

    // ---------- ② 跨轮复用 ----------

    @Test
    fun `上一轮跑过的相同调用直接复用`() {
        val deduper = ToolCallDeduper()
        val first = call(id = "1")
        deduper.plan(listOf(first))
        deduper.remember(first, success("上次的结果"))

        val second = call(id = "2")
        val plan = deduper.plan(listOf(second))
        assertTrue(plan.fresh.isEmpty())
        assertEquals("上次的结果", plan.reused[0]?.content)
    }

    @Test
    fun `失败结果也会被记住（重复问同一个失败调用没有意义）`() {
        val deduper = ToolCallDeduper()
        val first = call(id = "1")
        deduper.plan(listOf(first))
        deduper.remember(first, ToolResult.Failure("SOURCE_MISSING", "没有这本书"))

        val plan = deduper.plan(listOf(call(id = "2")))
        assertTrue(plan.fresh.isEmpty())
        assertTrue(plan.reused.containsKey(0))
    }

    @Test
    fun `参数变了就不再复用`() {
        val deduper = ToolCallDeduper()
        val first = call(arguments = """{"query":"森林"}""")
        deduper.plan(listOf(first))
        deduper.remember(first, success())

        val plan = deduper.plan(listOf(call(arguments = """{"query":"森林 "}""")))
        assertEquals(1, plan.fresh.size)
    }

    // ---------- ③ 同一轮内重复 ----------

    @Test
    fun `同一轮里两条一样的调用只跑第一条`() {
        val deduper = ToolCallDeduper()
        val calls = listOf(call(id = "1"), call(id = "2"))
        val plan = deduper.plan(calls)
        assertEquals(listOf(0 to calls[0]), plan.fresh)
        assertEquals(0, plan.duplicateOf[1])
        assertTrue(plan.reused.isEmpty())
    }

    @Test
    fun `同一轮里的三条重复都指向第一条`() {
        val deduper = ToolCallDeduper()
        val plan = deduper.plan(listOf(call(id = "1"), call(id = "2"), call(id = "3")))
        assertEquals(1, plan.fresh.size)
        assertEquals(0, plan.duplicateOf[1])
        assertEquals(0, plan.duplicateOf[2])
    }

    @Test
    fun `同轮重复优先于跨轮复用（先看缓存）`() {
        val deduper = ToolCallDeduper()
        val cached = call(id = "old")
        deduper.plan(listOf(cached))
        deduper.remember(cached, success("缓存"))

        val plan = deduper.plan(listOf(call(id = "1"), call(id = "2")))
        assertTrue(plan.fresh.isEmpty())
        assertEquals("缓存", plan.reused[0]?.content)
        assertEquals("缓存", plan.reused[1]?.content)
    }

    // ---------- ④ 参数规范化 ----------

    @Test
    fun `键序不同的同一份参数算同一次调用`() {
        val deduper = ToolCallDeduper()
        assertEquals(
            deduper.normalize("""{"a":1,"b":2}"""),
            deduper.normalize("""{"b":2,"a":1}""")
        )
    }

    @Test
    fun `嵌套对象的键也会被排序`() {
        val deduper = ToolCallDeduper()
        assertEquals(
            deduper.normalize("""{"outer":{"x":1,"y":2},"n":[1,2,3]}"""),
            deduper.normalize("""{"n":[1,2,3],"outer":{"y":2,"x":1}}""")
        )
    }

    @Test
    fun `数组顺序有意义，不排序`() {
        val deduper = ToolCallDeduper()
        assertFalse(deduper.normalize("""{"n":[1,2]}""") == deduper.normalize("""{"n":[2,1]}"""))
    }

    @Test
    fun `首尾空白不影响判定`() {
        val deduper = ToolCallDeduper()
        assertEquals(deduper.normalize("""{"a":1}"""), deduper.normalize("  {\"a\":1}  "))
    }

    @Test
    fun `非法 JSON 退回原文比较而不是当成同一件事`() {
        val deduper = ToolCallDeduper()
        assertEquals("{不是 json}", deduper.normalize("{不是 json}"))
        assertFalse(deduper.normalize("{不是 json}") == deduper.normalize("{另一种坏 json}"))
    }

    @Test
    fun `空参数与纯空白等价`() {
        val deduper = ToolCallDeduper()
        assertEquals("", deduper.normalize(""))
        assertEquals(deduper.normalize(""), deduper.normalize("   "))
    }

    @Test
    fun `键里带上工具名，不同工具的参数相同也不会串`() {
        val deduper = ToolCallDeduper()
        assertFalse(deduper.key(call(name = "a")) == deduper.key(call(name = "b")))
    }
}
