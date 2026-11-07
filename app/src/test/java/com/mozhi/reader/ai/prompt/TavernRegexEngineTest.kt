package com.mozhi.reader.ai.prompt

import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酒馆正则脚本引擎。
 *
 * 「实测预设」那几组参数是**照抄用户那份 GOLDEN 预设的真实值**；期望输出先用
 * JDK 21 的 `java.util.regex` 实跑一遍再写进来（见交接文件里的 oracle 记录），不是凭空推的。
 */
class TavernRegexEngineTest {

    private fun script(
        id: String = "s",
        name: String = "S",
        find: String = "a",
        replacement: String = "b",
        trimStrings: List<String> = emptyList(),
        placements: List<Int> = listOf(TavernRegexScript.PLACEMENT_AI_OUTPUT),
        enabled: Boolean = true,
        markdownOnly: Boolean = false,
        promptOnly: Boolean = false,
        minDepth: Int? = null,
        maxDepth: Int? = null
    ) = TavernRegexScript(
        id = id, name = name, find = find, replacement = replacement, trimStrings = trimStrings,
        placements = placements, enabled = enabled, markdownOnly = markdownOnly,
        promptOnly = promptOnly, minDepth = minDepth, maxDepth = maxDepth
    )

    // ---------- ① /pattern/flags 解析（复刻酒馆 regexFromString） ----------

    @Test
    fun `斜杠写法取出 pattern 与旗标`() {
        assertEquals("abc" to 0, TavernRegexEngine.parse("/abc/g"))
        assertEquals("abc" to 0, TavernRegexEngine.parse("/abc/"))
    }

    @Test
    fun `裸 pattern 整串当正则`() {
        assertEquals("^([\\s\\S]*)$" to 0, TavernRegexEngine.parse("^([\\s\\S]*)$"))
        assertEquals("abc" to 0, TavernRegexEngine.parse("abc"))
    }

    @Test
    fun `旗标非法时退回整串当 pattern（与酒馆一致）`() {
        assertEquals("/a/bc" to 0, TavernRegexEngine.parse("/a/bc"))
        assertEquals("/a/gg" to 0, TavernRegexEngine.parse("/a/gg"))
    }

    @Test
    fun `旗标映射到 Java 选项`() {
        val dotAll = TavernRegexEngine.parse("/x/s")!!.second
        assertTrue((dotAll and Pattern.DOTALL) != 0)
        val ignoreCase = TavernRegexEngine.parse("/x/i")!!.second
        assertTrue((ignoreCase and Pattern.CASE_INSENSITIVE) != 0)
        val multiline = TavernRegexEngine.parse("/x/m")!!.second
        assertTrue((multiline and Pattern.MULTILINE) != 0)
        // g 是"全局"，Java 的替换本来就是全局，不映射成选项
        assertEquals(0, TavernRegexEngine.parse("/x/g")!!.second)
    }

    // ---------- ② markdownOnly / promptOnly 的处置 ----------

    @Test
    fun `同时为 true 的脚本要执行（实测预设里 7 条都是这样）`() {
        assertTrue(TavernRegexEngine.shouldApply(script(markdownOnly = true, promptOnly = true)))
    }

    @Test
    fun `只给显示用的脚本不执行`() {
        assertFalse(TavernRegexEngine.shouldApply(script(markdownOnly = true, promptOnly = false)))
    }

    @Test
    fun `两个都 false 与只 promptOnly 都要执行`() {
        assertTrue(TavernRegexEngine.shouldApply(script()))
        assertTrue(TavernRegexEngine.shouldApply(script(promptOnly = true)))
    }

    // ---------- ③ 深度窗口 ----------

    @Test
    fun `maxDepth 之外不执行`() {
        val s = script(maxDepth = 9)
        assertTrue(TavernRegexEngine.depthAllowed(s, 0))
        assertTrue(TavernRegexEngine.depthAllowed(s, 9))
        assertFalse(TavernRegexEngine.depthAllowed(s, 10))
    }

    @Test
    fun `minDepth 之内不执行`() {
        val s = script(minDepth = 10)
        assertFalse(TavernRegexEngine.depthAllowed(s, 9))
        assertTrue(TavernRegexEngine.depthAllowed(s, 10))
    }

    @Test
    fun `不知道深度时不按深度过滤`() {
        assertTrue(TavernRegexEngine.depthAllowed(script(minDepth = 10, maxDepth = 10), null))
    }

    // ---------- ④ 替换串求值 ----------

    @Test
    fun `编号捕获组`() {
        val s = script(find = "(\\w+)-(\\w+)", replacement = "$2/$1")
        assertEquals("b/a", TavernRegexEngine.apply("a-b", s))
    }

    @Test
    fun `match 与美元符零与与号都是整个匹配`() {
        assertEquals("[a]", TavernRegexEngine.apply("a", script(find = "a", replacement = "[{{match}}]")))
        assertEquals("[a]", TavernRegexEngine.apply("a", script(find = "a", replacement = "[$0]")))
        assertEquals("[a]", TavernRegexEngine.apply("a", script(find = "a", replacement = "[$&]")))
    }

    @Test
    fun `命名捕获组两种写法`() {
        val s = script(find = "(?<year>\\d+)", replacement = "$<year>")
        assertEquals("2026", TavernRegexEngine.apply("2026", s))
        // ${year} 才是第二种写法；原来写成 "${'$'}year}"（少了左花括号）等于 $year}，
        // 引擎按规则原样放过，断言自然不可能通过 —— 是测试笔误，不是引擎缺陷。
        val brace = script(find = "(?<year>\\d+)", replacement = "${'$'}{year}")
        assertEquals("2026", TavernRegexEngine.apply("2026", brace))
    }

    @Test
    fun `双美元符是字面量`() {
        val s = script(find = "格", replacement = "${'$'}${'$'}格")
        assertEquals("价${'$'}格", TavernRegexEngine.apply("价格", s))
    }

    @Test
    fun `匹配前后的文本`() {
        // `$` + 反引号 = 匹配之前的文本；`$` + 单引号 = 匹配之后的文本
        val s = script(find = "X", replacement = "<${'$'}`|${'$'}'>")
        assertEquals("前<前|后>后", TavernRegexEngine.apply("前X后", s))
    }

    @Test
    fun `trimStrings 作用在被替换进去的捕获组上（酒馆行为）`() {
        val s = script(find = "《(.+?)》", replacement = "书名：$1", trimStrings = listOf("的"))
        assertEquals("书名：剑来", TavernRegexEngine.apply("《剑来的》", s))
    }

    @Test
    fun `没有匹配时原样返回`() {
        val text = "毫无关系"
        assertEquals(text, TavernRegexEngine.apply(text, script(find = "zzz")))
    }

    @Test
    fun `零宽匹配不会死循环`() {
        assertEquals("X好你X好", TavernRegexEngine.apply("好你好", script(find = "(?=好)", replacement = "X")))
    }

    // ---------- ⑤ 开关与容错 ----------

    @Test
    fun `禁用与纯显示的脚本不执行`() {
        assertEquals("a", TavernRegexEngine.apply("a", script(enabled = false)))
        assertEquals("a", TavernRegexEngine.apply("a", script(markdownOnly = true, promptOnly = false)))
    }

    @Test
    fun `深度窗口外不执行`() {
        assertEquals("a", TavernRegexEngine.apply("a", script(maxDepth = 1), depth = 5))
        assertEquals("b", TavernRegexEngine.apply("a", script(maxDepth = 5), depth = 5))
    }

    @Test
    fun `Java 编不过的正则只跳过这一条，不抛异常`() {
        val broken = script(find = "([unclosed")
        assertEquals("a", TavernRegexEngine.apply("a", broken))
        assertNull(TavernRegexEngine.compile("([unclosed"))
        assertNotNull(TavernRegexEngine.unsupportedReason("([unclosed"))
        assertNull(TavernRegexEngine.unsupportedReason("/ok/g"))
        assertNotNull(TavernRegexEngine.unsupportedReason(""))
    }

    // ---------- ⑥ 按放置位批量执行 ----------

    @Test
    fun `只执行匹配放置位的脚本`() {
        val scripts = listOf(
            script(id = "user", find = "a", replacement = "U", placements = listOf(TavernRegexScript.PLACEMENT_USER_INPUT)),
            script(id = "ai", find = "a", replacement = "A", placements = listOf(TavernRegexScript.PLACEMENT_AI_OUTPUT))
        )
        assertEquals("U", TavernRegexEngine.applyAll("a", scripts, TavernRegexScript.PLACEMENT_USER_INPUT))
        assertEquals("A", TavernRegexEngine.applyAll("a", scripts, TavernRegexScript.PLACEMENT_AI_OUTPUT))
    }

    @Test
    fun `脚本按给定顺序叠加（顺序不同结果不同）`() {
        val first = script(id = "1", find = "a", replacement = "b")
        val second = script(id = "2", find = "b", replacement = "c")
        assertEquals("c", TavernRegexEngine.applyAll("a", listOf(first, second), TavernRegexScript.PLACEMENT_AI_OUTPUT))
        assertEquals("b", TavernRegexEngine.applyAll("a", listOf(second, first), TavernRegexScript.PLACEMENT_AI_OUTPUT))
    }

    @Test
    fun `统计某放置位下真正会跑的条数`() {
        val scripts = listOf(
            script(id = "on", placements = listOf(2)),
            script(id = "off", enabled = false, placements = listOf(2)),
            script(id = "display", markdownOnly = true, placements = listOf(2)),
            script(id = "other", placements = listOf(1))
        )
        assertEquals(1, TavernRegexEngine.activeCount(scripts, TavernRegexScript.PLACEMENT_AI_OUTPUT))
        assertEquals(1, TavernRegexEngine.activeCount(scripts, TavernRegexScript.PLACEMENT_USER_INPUT))
    }

    // ---------- ⑦ 实测预设里的真实脚本 ----------

    /** 【夏瑾】破折号处理：把中文之间的破折号换成逗号（JDK 21 实跑输出「他走过去，然后停下。」）。 */
    @Test
    fun `实测预设的破折号处理`() {
        val s = script(
            name = "破折号处理",
            find = """/(?<=[\u4e00-\u9fa5])——(?=[\u4e00-\u9fa5])/g""",
            replacement = "，",
            placements = listOf(2),
            markdownOnly = true,
            promptOnly = true
        )
        assertEquals("他走过去，然后停下。", TavernRegexEngine.apply("他走过去——然后停下。", s))
        // 只作用于 AI 输出，用户输入原样
        assertEquals(
            "他走过去——然后停下。",
            TavernRegexEngine.applyAll("他走过去——然后停下。", listOf(s), TavernRegexScript.PLACEMENT_USER_INPUT)
        )
    }

    /** 【云瑾】包裹最新指示：只在最近的用户消息（depth <= 1）外面包一层标签。 */
    @Test
    fun `实测预设的包裹最新指示受深度限制`() {
        val s = script(
            name = "包裹最新指示",
            find = """^([\s\S]*)$""",
            replacement = "<最新互动>\n$1\n</最新互动>",
            placements = listOf(1),
            maxDepth = 1
        )
        assertEquals("<最新互动>\n让我看看\n</最新互动>", TavernRegexEngine.apply("让我看看", s, depth = 1))
        // depth=2 超出 maxDepth=1 → 不动
        assertEquals("让我看看", TavernRegexEngine.apply("让我看看", s, depth = 2))
    }

    /** 【夏瑾】行动选项1 是纯显示用途（markdownOnly && !promptOnly），提示词里必须原样放过。 */
    @Test
    fun `实测预设里那条 HTML 脚本不进提示词`() {
        val s = script(
            name = "行动选项1",
            find = """(<SUOT\b[^>]*>[\s\S]*?</SUOT>)""",
            replacement = "<!DOCTYPE html>…",
            placements = listOf(2),
            markdownOnly = true,
            promptOnly = false
        )
        val text = "<SUOT>1. 上前</SUOT>"
        assertEquals(text, TavernRegexEngine.apply(text, s))
    }
}
