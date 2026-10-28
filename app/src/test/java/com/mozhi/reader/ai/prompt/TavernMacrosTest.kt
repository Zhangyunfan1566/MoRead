package com.mozhi.reader.ai.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酒馆宏引擎。用例覆盖实测预设里出现过的每一个宏，外加条件块与随机宏的边界。
 *
 * 注意两条刻意保留的旧行为：
 * - 没有作用域（variables = null）时变量 / 随机 / trim 宏**原样保留**，不产生副作用；
 * - 未识别的宏一律原样保留，不静默删除。
 */
class TavernMacrosTest {

    private fun scope(vararg pairs: Pair<String, String>) = TavernVariables(mapOf(*pairs))

    private fun render(text: String, context: MacroContext? = null, variables: TavernVariables? = null) =
        TavernMacros.render(text, context, variables)

    // ---------- 身份与消息宏 ----------

    @Test
    fun `身份宏被替换`() {
        val context = MacroContext(characterName = "小雪", userName = "我", persona = "冷面剑客")
        assertEquals("小雪/我/冷面剑客", render("{{char}}/{{user}}/{{persona}}", context))
    }

    @Test
    fun `没有上下文时身份宏原样保留`() {
        assertEquals("{{char}} {{user}}", render("{{char}} {{user}}", null))
    }

    @Test
    fun `消息宏从上下文取最后一条正文`() {
        val context = MacroContext(lastUserMessage = "我推门进去", lastCharMessage = "她抬起头")
        assertEquals("我推门进去 | 她抬起头", render("{{lastusermessage}} | {{lastcharmessage}}", context))
    }

    // ---------- 变量宏 ----------

    @Test
    fun `setvar 之后 getvar 取得到`() {
        val variables = scope()
        val out = render("{{setvar::wordsCloud::不少于1500}}字数：{{getvar::wordsCloud}}", null, variables)
        assertEquals("字数：不少于1500", out)
        assertTrue(variables.dirty)
    }

    @Test
    fun `getvar 未定义时为空串`() {
        assertEquals("前[]后", render("前[{{getvar::nope}}]后", null, scope()))
    }

    @Test
    fun `没有作用域时变量宏原样保留`() {
        val text = "{{setvar::k::v}}{{getvar::k}}{{roll 1d6}}{{trim}}"
        assertEquals(text, render(text, null, null))
    }

    @Test
    fun `setvar 的值里可以带冒号分隔`() {
        val variables = scope()
        render("{{setvar::fmt::a::b::c}}", null, variables)
        assertEquals("a::b::c", variables.get("fmt"))
    }

    @Test
    fun `addvar 缺省加一且保持整数外观`() {
        val variables = scope("hp" to "3")
        render("{{addvar::hp}}", null, variables)
        assertEquals("4", variables.get("hp"))
        render("{{addvar::hp::2.5}}", null, variables)
        assertEquals("6.5", variables.get("hp"))
    }

    @Test
    fun `incvar 与 decvar`() {
        val variables = scope("n" to "10")
        render("{{incvar::n}}{{decvar::n}}{{decvar::n}}", null, variables)
        assertEquals("9", variables.get("n"))
    }

    @Test
    fun `未写过的变量做 incvar 从零开始`() {
        val variables = scope()
        render("{{incvar::fresh}}", null, variables)
        assertEquals("1", variables.get("fresh"))
    }

    // ---------- 随机宏 ----------

    @Test
    fun `roll 单骰落在范围内且 1d1 恒为一`() {
        repeat(20) {
            val value = render("{{roll 1d6}}", null, scope()).toInt()
            assertTrue("1d6 应为 1..6，实际 $value", value in 1..6)
        }
        assertEquals("1", render("{{roll:1d1}}", null, scope()))
    }

    @Test
    fun `roll 多骰与纯数字写法`() {
        repeat(20) {
            val value = render("{{roll 3d6}}", null, scope()).toInt()
            assertTrue("3d6 应为 3..18，实际 $value", value in 3..18)
        }
        val flat = render("{{roll 4}}", null, scope()).toInt()
        assertTrue(flat in 1..4)
    }

    @Test
    fun `roll 缺参时退回 1d100 而不是崩掉`() {
        val value = render("{{roll}}", null, scope()).toInt()
        assertTrue(value in 1..100)
    }

    @Test
    fun `random 与 pick 只从候选里取`() {
        val options = setOf("大幅深化当前的基调", "引入脑洞清奇的新剧情", "完全反转当前的基调")
        repeat(30) {
            assertTrue(render("{{random::大幅深化当前的基调::引入脑洞清奇的新剧情::完全反转当前的基调}}", null, scope()) in options)
            assertTrue(render("{{pick::a::b}}", null, scope()) in setOf("a", "b"))
        }
    }

    // ---------- 杂项 ----------

    @Test
    fun `trim 裁掉整段首尾空白`() {
        assertEquals("正文", render("  \n{{trim}}正文  \n", null, scope()))
    }

    @Test
    fun `newline 与 noop`() {
        assertEquals("a\nb", render("a{{newline}}b", null, scope()))
        assertEquals("ab", render("a{{noop}}b", null, scope()))
    }

    @Test
    fun `注释宏被移除`() {
        assertEquals("前后", render("前{{// 自己可以改成第二人称}}{{//⟦P-ALL⟧}}后", null, scope()))
    }

    // ---------- 条件块 ----------

    @Test
    fun `if 按变量真值走分支`() {
        val variables = scope("flag" to "1")
        assertEquals("开了", render("{{#if flag}}开了{{else}}关了{{/if}}", null, variables))
        variables.set("flag", "0")
        assertEquals("关了", render("{{#if flag}}开了{{else}}关了{{/if}}", null, variables))
        variables.set("flag", "")
        assertEquals("关了", render("{{#if flag}}开了{{else}}关了{{/if}}", null, variables))
    }

    @Test
    fun `if 支持取反与比较`() {
        val variables = scope("mode" to "dark", "off" to "false")
        assertEquals("亮", render("{{#if !off}}亮{{/if}}", null, variables))
        assertEquals("暗", render("{{#if mode==dark}}暗{{/if}}", null, variables))
        assertEquals("不是", render("{{#if mode!=dark}}是{{else}}不是{{/if}}", null, variables))
    }

    @Test
    fun `if 支持嵌套`() {
        val variables = scope("a" to "1", "b" to "0")
        val text = "{{#if a}}A{{#if b}}B{{else}}b{{/if}}{{else}}x{{/if}}"
        assertEquals("Ab", render(text, null, variables))
    }

    @Test
    fun `没有作用域时条件块原样保留`() {
        val text = "{{#if flag}}A{{else}}B{{/if}}"
        assertEquals(text, render(text, null, null))
    }

    @Test
    fun `条件块内的宏照常展开`() {
        val variables = scope("flag" to "1")
        val context = MacroContext(characterName = "小雪")
        assertEquals("小雪", render("{{#if flag}}{{char}}{{/if}}", context, variables))
    }

    // ---------- 诊断辅助 ----------

    @Test
    fun `未知宏只列真正的未知项`() {
        val text = "{{getvar::a}} {{setvar::b::c}} {{roll 1d6}} {{#if f}} {{/if}} {{char}} {{user}} {{// 注释}} {{weirdmacro}}"
        assertEquals(listOf("weirdmacro"), TavernMacros.unknownMacros(text))
    }

    @Test
    fun `没有未知宏时返回空表`() {
        assertTrue(TavernMacros.unknownMacros("{{getvar::a}}{{roll 1d6}}{{trim}}").isEmpty())
    }

    @Test
    fun `列出没赋值的变量名`() {
        val variables = scope("known" to "1")
        val text = "{{getvar::known}} {{getvar::missing}} {{getvar::alsoMissing}} {{getvar::missing}}"
        assertEquals(listOf("missing", "alsoMissing"), TavernMacros.unresolvedVariables(text, variables))
    }

    @Test
    fun `宏名归一化`() {
        assertEquals("if", TavernMacros.macroName("#if flag"))
        assertEquals("roll", TavernMacros.macroName("roll 1d6"))
        assertEquals("getvar", TavernMacros.macroName("getvar::hp"))
        assertEquals("//", TavernMacros.macroName("// 注释"))
        assertEquals("if", TavernMacros.macroName("/if"))
    }

    @Test
    fun `参数切分同时支持双冒号与空格`() {
        assertEquals(listOf("k"), TavernMacros.argumentsOf("getvar::k"))
        assertEquals(listOf("k", "v"), TavernMacros.argumentsOf("setvar::k::v"))
        assertEquals(listOf("1d6"), TavernMacros.argumentsOf("roll 1d6"))
        assertEquals(listOf("1d6"), TavernMacros.argumentsOf("roll:1d6"))
        assertTrue(TavernMacros.argumentsOf("trim").isEmpty())
    }

    // ---------- 实测预设的真实形态 ----------

    @Test
    fun `破限正文装进变量再取出（实测预设的核心机制）`() {
        val variables = scope()
        // 第 1 条（order 1）：变量装载条目
        val loader = "{{setvar::JailbreakPrompt::[破限正文]}}{{setvar::cotBegin::好的，让我先理解一下这个任务。}}"
        // 第 2 条（order 2）：真正用它的条目
        val consumer = "{{getvar::cotBegin}}\n{{getvar::JailbreakPrompt}}"
        assertEquals("", render(loader, null, variables))
        assertEquals("好的，让我先理解一下这个任务。\n[破限正文]", render(consumer, null, variables))
        assertFalse(render(consumer, null, variables).contains("{{"))
    }

    @Test
    fun `未替换的宏绝不被静默删除`() {
        assertEquals("{{somethingNew}}", render("{{somethingNew}}", null, scope()))
    }
}
