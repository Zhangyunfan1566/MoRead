package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用去重。
 *
 * 三条边界是这轮审查揪出来的，必须钉住：
 * 1. **只有只读工具参与去重**——写入类工具的结果依赖当时的书库状态，缓存它会让"先读、再改、再读"拿到旧内容；
 * 2. **写入是屏障**——同轮里写入之后，之前缓存的读取结果一律作废；
 * 3. **失败不缓存**——失败往往是暂时的，模型重试同一次调用是合法的，不该被短路。
 */
class ToolCallDeduperTest {

    private fun call(name: String = "search_book", arguments: String = """{"query":"森林"}""", id: String = "c") =
        ToolCall(id = id, name = name, arguments = arguments)

    private fun success(text: String = "结果") = ToolResult.Success(text)
    private fun failure() = ToolResult.Failure("SOURCE_MISSING", "没有这本书")

    /** 只读名单用真实那份的一部分；写入类（add_annotation / generate_image…）不在里面。 */
    private val readOnly: (String) -> Boolean = { it in ConcurrencySafeTools.NAMES }

    private fun ToolCallDeduper.planReadOnly(calls: List<ToolCall>) = plan(calls, readOnly)

    // ---------- ① 无重复 ----------

    @Test
    fun `参数不同的调用全部照常执行`() {
        val deduper = ToolCallDeduper()
        val calls = listOf(
            call(arguments = """{"query":"森林"}""", id = "1"),
            call(arguments = """{"query":"沙漠"}""", id = "2")
        )
        val plan = deduper.planReadOnly(calls)
        assertEquals(listOf(0 to calls[0], 1 to calls[1]), plan.fresh)
        assertTrue(plan.reused.isEmpty())
        assertTrue(plan.duplicateOf.isEmpty())
    }

    @Test
    fun `工具名不同即使参数一样也不算重复`() {
        val deduper = ToolCallDeduper()
        val plan = deduper.planReadOnly(listOf(call(name = "search_book"), call(name = "grep_book")))
        assertEquals(2, plan.fresh.size)
    }

    // ---------- ② 跨轮复用 ----------

    @Test
    fun `上一轮跑过的相同只读调用直接复用`() {
        val deduper = ToolCallDeduper()
        val first = call(id = "1")
        deduper.planReadOnly(listOf(first))
        deduper.remember(first, success("上次的结果"))

        val plan = deduper.planReadOnly(listOf(call(id = "2")))
        assertTrue(plan.fresh.isEmpty())
        assertEquals("上次的结果", plan.reused[0]?.content)
    }

    @Test
    fun `失败结果不会被记住（重试是合法的）`() {
        val deduper = ToolCallDeduper()
        val first = call(id = "1")
        deduper.planReadOnly(listOf(first))
        deduper.remember(first, failure())

        val plan = deduper.planReadOnly(listOf(call(id = "2")))
        assertEquals(1, plan.fresh.size)
        assertTrue(plan.reused.isEmpty())
    }

    @Test
    fun `参数变了就不再复用`() {
        val deduper = ToolCallDeduper()
        val first = call(arguments = """{"query":"森林"}""")
        deduper.planReadOnly(listOf(first))
        deduper.remember(first, success())

        val plan = deduper.planReadOnly(listOf(call(arguments = """{"query":"森林 "}""")))
        assertEquals(1, plan.fresh.size)
    }

    // ---------- ③ 同一轮内重复 ----------

    @Test
    fun `同一轮里两条一样的只读调用只跑第一条`() {
        val deduper = ToolCallDeduper()
        val calls = listOf(call(id = "1"), call(id = "2"))
        val plan = deduper.planReadOnly(calls)
        assertEquals(listOf(0 to calls[0]), plan.fresh)
        assertEquals(0, plan.duplicateOf[1])
        assertTrue(plan.reused.isEmpty())
    }

    @Test
    fun `同一轮里的三条重复都指向第一条`() {
        val deduper = ToolCallDeduper()
        val plan = deduper.planReadOnly(listOf(call(id = "1"), call(id = "2"), call(id = "3")))
        assertEquals(1, plan.fresh.size)
        assertEquals(0, plan.duplicateOf[1])
        assertEquals(0, plan.duplicateOf[2])
    }

    @Test
    fun `同轮重复优先于跨轮复用（先看缓存）`() {
        val deduper = ToolCallDeduper()
        val cached = call(id = "old")
        deduper.planReadOnly(listOf(cached))
        deduper.remember(cached, success("缓存"))

        val plan = deduper.planReadOnly(listOf(call(id = "1"), call(id = "2")))
        assertTrue(plan.fresh.isEmpty())
        assertEquals("缓存", plan.reused[0]?.content)
        assertEquals("缓存", plan.reused[1]?.content)
    }

    // ---------- ④ 只读限定与写入屏障 ----------

    @Test
    fun `写入类工具从不参与去重`() {
        val deduper = ToolCallDeduper()
        val calls = listOf(
            call(name = "add_annotation", id = "1"),
            call(name = "add_annotation", id = "2")
        )
        val plan = deduper.planReadOnly(calls)
        assertEquals(2, plan.fresh.size)
        assertTrue(plan.reused.isEmpty())
        assertTrue(plan.duplicateOf.isEmpty())
    }

    @Test
    fun `写入是屏障：同轮里写入之后的读取要重新查`() {
        val deduper = ToolCallDeduper()
        val read = call(name = "read_book_section", arguments = """{"chapter":3}""", id = "r1")
        val write = call(name = "add_annotation", arguments = """{"text":"x"}""", id = "w")
        val readAgain = call(name = "read_book_section", arguments = """{"chapter":3}""", id = "r2")

        val plan = deduper.planReadOnly(listOf(read, write, readAgain))
        // 三条都要真跑：第二条读取不能被第一条的缓存短路
        assertEquals(3, plan.fresh.size)
        assertTrue(plan.reused.isEmpty())
        assertTrue(plan.duplicateOf.isEmpty())
    }

    @Test
    fun `写入之后同轮里再读同一条不会被当成重复`() {
        val deduper = ToolCallDeduper()
        val write = call(name = "generate_image", arguments = """{"prompt":"a"}""", id = "w")
        val read = call(name = "list_annotations", arguments = """{}""", id = "r1")
        val readAgain = call(name = "list_annotations", arguments = """{}""", id = "r2")

        val plan = deduper.planReadOnly(listOf(write, read, readAgain))
        // 写入只作废「写入之前」的缓存。写入之后的 read 与 readAgain 之间没有任何写入，
        // 它们仍然是同一轮里的重复 —— 只跑一次才是去重器该做的事，写两个断言钉住。
        assertEquals(2, plan.fresh.size)
        assertEquals(1, plan.duplicateOf[2])
        assertTrue(plan.reused.isEmpty())
    }

    @Test
    fun `invalidate 之后上一轮的读取缓存作废`() {
        val deduper = ToolCallDeduper()
        val read = call(id = "1")
        deduper.planReadOnly(listOf(read))
        deduper.remember(read, success("旧内容"))

        deduper.invalidate()

        val plan = deduper.planReadOnly(listOf(call(id = "2")))
        assertEquals(1, plan.fresh.size)
        assertTrue(plan.reused.isEmpty())
    }

    // ---------- ⑤ 参数规范化 ----------

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
