package com.mozhi.reader.ai.prompt

import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatPart
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.datastore.GlobalPromptInjectionPosition
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.core.datastore.PromptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 酒馆语义的注入：角色（含 assistant 预填充）、相对深度、锚点、顺序、marker 与宏。 */
class GlobalPromptInjectorTest {

    private fun preset(
        id: String = "p",
        name: String = "P",
        prompt: String = "BLOCK",
        enabled: Boolean = true,
        position: GlobalPromptInjectionPosition = GlobalPromptInjectionPosition.AFTER_SYSTEM,
        role: PromptRole = PromptRole.SYSTEM,
        depth: Int = -1,
        order: Int = 0,
        marker: Boolean = false
    ) = GlobalPromptPreset(
        id = id, name = name, prompt = prompt, enabled = enabled,
        position = position, role = role, depth = depth, order = order, marker = marker
    )

    /** 典型对话：system + U1 + A1 + U2（U2 是最后一条 user）。 */
    private fun conversation(): List<ChatMessage> = listOf(
        ChatMessage(ChatRole.SYSTEM, "SYS"),
        ChatMessage(ChatRole.USER, "U1"),
        ChatMessage(ChatRole.ASSISTANT, "A1"),
        ChatMessage(ChatRole.USER, "U2")
    )

    private fun roles(messages: List<ChatMessage>) = messages.map { it.role }
    private fun contents(messages: List<ChatMessage>) = messages.map { it.content }

    // ---------- ① 绝对锚点 + SYSTEM：旧行为（合并） ----------

    @Test
    fun `system 锚点合并进主 system 消息且保持前后顺序`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(
                preset(id = "a", prompt = "前", position = GlobalPromptInjectionPosition.BEFORE_SYSTEM),
                preset(id = "b", prompt = "后", position = GlobalPromptInjectionPosition.AFTER_SYSTEM)
            )
        )
        assertEquals(4, out.size)
        val system = out.first().content
        // 每个注入块都带【label·name】前缀（与酒馆一致，也是上游既有行为），
        // 所以要断"前后顺序"而不是 startsWith 原文。
        assertTrue(system.indexOf("前") < system.indexOf("SYS"))
        assertTrue(system.indexOf("SYS") < system.indexOf("后"))
        assertTrue(system.contains("SYS"))
        assertTrue(system.endsWith("后"))
    }

    @Test
    fun `没有 system 消息时新建一条`() {
        val out = GlobalPromptInjector.inject(
            listOf(ChatMessage(ChatRole.USER, "U1")),
            listOf(preset(prompt = "X", position = GlobalPromptInjectionPosition.AFTER_SYSTEM))
        )
        assertEquals(ChatRole.SYSTEM, out.first().role)
        assertTrue(out.first().content.contains("X"))
        assertEquals(2, out.size)
    }

    @Test
    fun `旧的最近用户消息锚点仍是拼接进那条 user（保持兼容）`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(
                preset(id = "a", prompt = "前", position = GlobalPromptInjectionPosition.BEFORE_LAST_USER),
                preset(id = "b", prompt = "后", position = GlobalPromptInjectionPosition.AFTER_LAST_USER)
            )
        )
        assertEquals(4, out.size)
        val lastUser = out.last()
        assertEquals(ChatRole.USER, lastUser.role)
        // 同 system 那条：带【label·name】前缀，按出现顺序断言。
        assertTrue(lastUser.content.indexOf("前") < lastUser.content.indexOf("U2"))
        assertTrue(lastUser.content.indexOf("U2") < lastUser.content.indexOf("后"))
        assertTrue(lastUser.content.endsWith("后"))
        assertTrue(lastUser.content.contains("U2"))
    }

    @Test
    fun `多模态的最后一条 user 被注入时保留图片并追加文本片段`() {
        val history = listOf(
            ChatMessage(ChatRole.USER, "old"),
            ChatMessage(ChatRole.ASSISTANT, "answer"),
            ChatMessage(ChatRole.USER, "photo", parts = listOf(ChatPart.Image("data", "image/jpeg")))
        )
        val out = GlobalPromptInjector.inject(
            history,
            listOf(preset(prompt = "rule", position = GlobalPromptInjectionPosition.AFTER_LAST_USER))
        )
        assertEquals("old", out.first().content)
        assertTrue(out.last().content.endsWith("rule"))
        assertTrue(out.last().parts.last() is ChatPart.Text)
        assertTrue(out.last().parts.any { it is ChatPart.Image })
    }

    // ---------- ② 角色：assistant = 预填充 ----------

    @Test
    fun `assistant 深度 0 以助手身份插在最后一条之后（预填充位）`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(preset(prompt = "PREFILL", role = PromptRole.ASSISTANT, depth = 0))
        )
        assertEquals(5, out.size)
        assertEquals(ChatRole.ASSISTANT, out.last().role)
        assertTrue(out.last().content.contains("PREFILL"))
    }

    @Test
    fun `user 角色以独立用户消息插入而不是改写原文`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(preset(prompt = "USERBLOCK", role = PromptRole.USER, depth = 0))
        )
        assertEquals(ChatRole.USER, out.last().role)
        assertTrue(out.last().content.contains("USERBLOCK"))
        // 原来的最后一条 user 未被改写
        assertEquals("U2", out[3].content)
    }

    @Test
    fun `绝对锚点配非 system 角色成为独立消息`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(
                preset(id = "a", prompt = "SYS前用户", role = PromptRole.USER,
                    position = GlobalPromptInjectionPosition.BEFORE_SYSTEM),
                preset(id = "b", prompt = "系统后助手", role = PromptRole.ASSISTANT,
                    position = GlobalPromptInjectionPosition.AFTER_SYSTEM)
            )
        )
        assertEquals(ChatRole.USER, out[0].role)
        assertTrue(out[0].content.contains("SYS前用户"))
        assertEquals(ChatRole.SYSTEM, out[1].role)
        assertEquals(ChatRole.ASSISTANT, out[2].role)
        assertTrue(out[2].content.contains("系统后助手"))
    }

    // ---------- ③ 相对深度 ----------

    @Test
    fun `深度 1 插在最后一条消息之前`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(preset(prompt = "D1", depth = 1, role = PromptRole.SYSTEM))
        )
        assertEquals(5, out.size)
        assertTrue(out[3].content.contains("D1"))
        assertEquals("U2", out[4].content)
    }

    @Test
    fun `深度大于消息数时夹到开头`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(preset(prompt = "D99", depth = 99, role = PromptRole.SYSTEM))
        )
        assertEquals(5, out.size)
        assertTrue(out.first().content.contains("D99"))
    }

    @Test
    fun `同深度多条按 order 保持先后`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(
                preset(id = "b", name = "B", prompt = "SECOND", depth = 0, order = 2),
                preset(id = "a", name = "A", prompt = "FIRST", depth = 0, order = 1)
            )
        )
        assertEquals(6, out.size)
        assertTrue(out[4].content.contains("FIRST"))
        assertTrue(out[5].content.contains("SECOND"))
    }

    // ---------- ④ 历史锚点 ----------

    @Test
    fun `聊天历史前后两个锚点`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(
                preset(id = "a", prompt = "史前", position = GlobalPromptInjectionPosition.BEFORE_HISTORY),
                preset(id = "b", prompt = "史后", position = GlobalPromptInjectionPosition.AFTER_HISTORY)
            )
        )
        assertEquals(6, out.size)
        assertTrue(out[1].content.contains("史前"))
        assertEquals(ChatRole.SYSTEM, out[0].role)
        assertTrue(out.last().content.contains("史后"))
    }

    // ---------- ⑤ marker / 禁用 ----------

    @Test
    fun `marker 与禁用条目不注入`() {
        val base = conversation()
        assertEquals(base, GlobalPromptInjector.inject(base, listOf(preset(marker = true))))
        assertEquals(base, GlobalPromptInjector.inject(base, listOf(preset(enabled = false))))
        assertEquals(base, GlobalPromptInjector.inject(base, listOf(preset(prompt = "   "))))
        assertEquals(base, GlobalPromptInjector.inject(base, emptyList()))
    }

    @Test
    fun `不污染传入的消息列表`() {
        val base = conversation()
        val snapshot = base.map { it.role to it.content }
        GlobalPromptInjector.inject(base, listOf(preset(depth = 0, role = PromptRole.ASSISTANT)))
        assertEquals(snapshot, base.map { it.role to it.content })
    }

    // ---------- ⑥ 宏 ----------

    @Test
    fun `基础宏被替换而高级宏原样保留`() {
        val text = "a {{char}} b {{user}} c {{persona}} d {{// 注释}} e {{getvar::x}}"
        val applied = GlobalPromptInjector.applyMacros(text, MacroContext("小雪", "我", "面具"))
        assertTrue(applied.contains("a 小雪 b 我 c 面具"))
        assertTrue(applied.contains("{{getvar::x}}"))
        assertTrue(!applied.contains("注释"))
    }

    @Test
    fun `没有宏上下文时不替换`() {
        val applied = GlobalPromptInjector.applyMacros("x {{char}} y", null)
        assertEquals("x {{char}} y", applied)
    }

    @Test
    fun `已实现的宏不再算未支持，只剩真正的未知宏`() {
        val macros = GlobalPromptInjector.unsupportedMacros(
            "{{getvar::hp}} {{roll 1d20}} {{#if flag}} {{char}} {{user}} {{// c}}"
        )
        assertEquals(emptyList<String>(), macros)
        assertEquals(
            listOf("weirdmacro"),
            GlobalPromptInjector.unsupportedMacros("{{getvar::hp}} {{weirdmacro}}")
        )
    }

    @Test
    fun `无作用域时变量宏原样保留`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(preset(prompt = "{{setvar::k::v}}{{getvar::k}}", depth = 0)),
            macros = MacroContext()
        )
        assertTrue(out.last().content.contains("{{setvar::k::v}}{{getvar::k}}"))
    }

    @Test
    fun `注入时宏会被替换进最终文本`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(preset(prompt = "称呼 {{char}}", role = PromptRole.ASSISTANT, depth = 0)),
            macros = MacroContext(characterName = "小雪")
        )
        assertTrue(out.last().content.contains("称呼 小雪"))
    }

    // ---------- ⑥b 变量作用域：实测预设赖以生效的机制 ----------

    @Test
    fun `同轮内先 setvar 后 getvar 生效（按 order 求值）`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(
                preset(id = "loader", name = "装载", prompt = "{{setvar::jb::[破限正文]}}", depth = 0, order = 1),
                preset(id = "user", name = "使用", prompt = "正文：{{getvar::jb}}", depth = 0, order = 2)
            ),
            variables = TavernVariables()
        )
        assertEquals(6, out.size)
        // 两条都在 depth=0（预填充位，即原列表末尾之后）。同一点内按 order 落位：
        // order=1 的装载在前、order=2 的使用在后 → out[4] 装载、out[5] 使用。
        // setvar 自身渲染成空串，装载条目只剩【预设·装载】这行标题，正文落在使用条目里。
        assertTrue(out[4].content.contains("装载"))
        assertTrue(out[5].content.contains("正文：[破限正文]"))
        assertFalse(out[5].content.contains("{{"))
    }

    @Test
    fun `宏求值按 order 而不是按落位顺序`() {
        // 装载条目落在 system 锚点（靠前落位），使用条目落在深度 0（靠后落位）。
        // 若实现是"边落位边渲染"，使用条目会先于装载条目求值，取到空值。
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(
                preset(id = "loader", name = "装载", prompt = "{{setvar::k::值}}",
                    position = GlobalPromptInjectionPosition.AFTER_SYSTEM, order = 1),
                preset(id = "use", name = "使用", prompt = "取到：{{getvar::k}}", depth = 0, order = 2)
            ),
            variables = TavernVariables()
        )
        assertTrue(out.last().content.contains("取到：值"))
    }

    @Test
    fun `作用域写入可被调用方回读落库`() {
        val variables = TavernVariables(mapOf("seed" to "旧值"))
        GlobalPromptInjector.inject(
            conversation(),
            listOf(preset(prompt = "{{setvar::seed::新值}}", depth = 0)),
            variables = variables
        )
        assertTrue(variables.dirty)
        assertEquals("新值", variables.snapshot()["seed"])
    }

    @Test
    fun `条件块按变量走分支`() {
        val variables = TavernVariables(mapOf("nsfw" to "1"))
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(preset(prompt = "{{#if nsfw}}成人条款{{else}}普通条款{{/if}}", depth = 0)),
            variables = variables
        )
        assertTrue(out.last().content.contains("成人条款"))
        assertFalse(out.last().content.contains("普通条款"))
    }

    @Test
    fun `contextFor 取出最后一条用户与助手正文`() {
        val context = GlobalPromptInjector.contextFor(conversation(), MacroContext(characterName = "小雪"))
        assertEquals("小雪", context.characterName)
        assertEquals("U2", context.lastUserMessage)
        assertEquals("A1", context.lastCharMessage)
    }

    @Test
    fun `注入时消息宏直接可用`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(preset(prompt = "上一句：{{lastusermessage}}", depth = 0)),
            macros = GlobalPromptInjector.contextFor(conversation())
        )
        assertTrue(out.last().content.contains("上一句：U2"))
    }

    // ---------- ⑦ 组合：酒馆式布局 ----------

    @Test
    fun `酒馆式布局：主提示词后契约 + 深度0强化 + 预填充`() {
        val out = GlobalPromptInjector.inject(
            conversation(),
            listOf(
                preset(id = "main", prompt = "契约", position = GlobalPromptInjectionPosition.AFTER_SYSTEM, order = 0),
                preset(id = "jb", prompt = "强化", depth = 0, role = PromptRole.SYSTEM, order = 1),
                preset(id = "prefill", prompt = "思考已结束", depth = 0, role = PromptRole.ASSISTANT, order = 2)
            )
        )
        assertEquals(6, out.size)
        assertTrue(out.first().content.contains("契约"))
        assertEquals(ChatRole.SYSTEM, out[4].role)
        assertTrue(out[4].content.contains("强化"))
        assertEquals(ChatRole.ASSISTANT, out[5].role)
        assertTrue(out[5].content.contains("思考已结束"))
        assertEquals(
            listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.SYSTEM, ChatRole.ASSISTANT),
            roles(out)
        )
        assertTrue(contents(out)[3] == "U2")
    }
}
