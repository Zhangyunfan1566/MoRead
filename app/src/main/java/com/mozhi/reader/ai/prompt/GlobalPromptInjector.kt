package com.mozhi.reader.ai.prompt

import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatPart
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.datastore.GlobalPromptInjectionPosition
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.core.datastore.PromptRole

/**
 * 宏替换上下文；缺省（null）时不替换任何宏。
 *
 * [lastUserMessage] / [lastCharMessage] 供酒馆的 `{{lastusermessage}}` / `{{lastcharmessage}}` 使用，
 * 由注入方从消息列表里就地取出（见 [GlobalPromptInjector.contextFor]）。
 */
data class MacroContext(
    val characterName: String = "",
    val userName: String = "",
    val persona: String = "",
    val lastUserMessage: String = "",
    val lastCharMessage: String = ""
)

/**
 * 把启用的全局预设注入**请求副本**；持久化会话与用户原文保持不变。
 *
 * 语义与 SillyTavern 的 Prompt Manager 对齐，分三组处理：
 *
 * 1. **绝对锚点 + role=SYSTEM**：合并进主 system 消息（`BEFORE_SYSTEM` / `AFTER_SYSTEM`），
 *    或拼接进最后一条 user 消息（`BEFORE_LAST_USER` / `AFTER_LAST_USER`）——这是旧行为，保持不变。
 * 2. **绝对锚点 + role=USER/ASSISTANT**，以及 `BEFORE_HISTORY` / `AFTER_HISTORY`：
 *    作为**独立消息**插入，角色由 [GlobalPromptPreset.role] 决定。
 * 3. **相对深度（`depth >= 0`）**：从对话末尾往上数 `depth` 条处插入独立消息
 *    （`depth = 0` 即最后一条之后 = 酒馆的**预填充位**）。
 *
 * 三组的插入点都在同一次调用里先算好、再**从后往前**落位，避免下标漂移；
 * 同一插入点的多条按 [GlobalPromptPreset.order] 保持先后。
 *
 * **宏求值顺序**：所有启用条目先按 `order` **完整渲染一遍**（而不是在落位时各渲染各的），
 * 再按锚点/深度落位。这样 `{{setvar}}` 的副作用对同一轮里靠后的 `{{getvar}}` 可见——
 * 真实酒馆预设正是靠这个机制把破限正文装进变量、再在别处取出来的。
 */
object GlobalPromptInjector {

    fun inject(
        messages: List<ChatMessage>,
        presets: List<GlobalPromptPreset>,
        label: String = "全局预设",
        macros: MacroContext? = null,
        variables: TavernVariables? = null
    ): List<ChatMessage> {
        val active = presets
            .filter { it.enabled && it.prompt.isNotBlank() && !it.marker }
            .sortedWith(compareBy({ it.order }, { it.name }))
        if (active.isEmpty()) return messages

        // 先按 order 顺序渲染全部条目：宏副作用（setvar）在这里产生，后面的条目能读到。
        val rendered = active.associate { preset ->
            preset.id to ("【$label·${preset.name}】\n" + TavernMacros.render(preset.prompt.trim(), macros, variables))
        }

        val output = messages.toMutableList()

        // ① 合并进 system 消息（旧行为）
        mergeSystem(
            output,
            active.filter {
                it.depth < 0 && it.role == PromptRole.SYSTEM &&
                    (it.position == GlobalPromptInjectionPosition.BEFORE_SYSTEM ||
                        it.position == GlobalPromptInjectionPosition.AFTER_SYSTEM)
            },
            rendered
        )

        // ② 拼接进最后一条 user 消息（旧行为）
        mergeLastUser(
            output,
            active.filter {
                it.depth < 0 && it.role == PromptRole.SYSTEM &&
                    (it.position == GlobalPromptInjectionPosition.BEFORE_LAST_USER ||
                        it.position == GlobalPromptInjectionPosition.AFTER_LAST_USER)
            },
            rendered
        )

        // ③ 其余一律作为独立消息插入（深度组 + 非 SYSTEM 角色 + 历史锚点）
        val standalone = active.filter {
            it.depth >= 0 || it.role != PromptRole.SYSTEM ||
                it.position == GlobalPromptInjectionPosition.BEFORE_HISTORY ||
                it.position == GlobalPromptInjectionPosition.AFTER_HISTORY
        }
        if (standalone.isEmpty()) return output

        val inserts = standalone.map { preset ->
            insertionIndex(output, preset) to ChatMessage(preset.role.toChatRole(), rendered[preset.id].orEmpty())
        }
        // 先算后插：按插入点从后往前落位，同一点内保持 order 先后
        inserts.groupBy({ it.first }, { it.second })
            .entries
            .sortedByDescending { it.key }
            .forEach { (index, group) ->
                group.asReversed().forEach { message -> output.add(index.coerceIn(0, output.size), message) }
            }
        return output
    }

    /** 从消息列表里就地取出 `{{lastusermessage}}` / `{{lastcharmessage}}` 需要的两条正文。 */
    fun contextFor(messages: List<ChatMessage>, base: MacroContext? = null): MacroContext {
        val lastUser = messages.lastOrNull { it.role == ChatRole.USER }?.content.orEmpty()
        val lastChar = messages.lastOrNull { it.role == ChatRole.ASSISTANT }?.content.orEmpty()
        val context = base ?: MacroContext()
        return context.copy(lastUserMessage = lastUser, lastCharMessage = lastChar)
    }

    private fun mergeSystem(
        messages: MutableList<ChatMessage>,
        presets: List<GlobalPromptPreset>,
        rendered: Map<String, String>
    ) {
        if (presets.isEmpty()) return
        val before = presets.filter { it.position == GlobalPromptInjectionPosition.BEFORE_SYSTEM }
            .joinToString("\n") { rendered[it.id].orEmpty() }
        val after = presets.filter { it.position == GlobalPromptInjectionPosition.AFTER_SYSTEM }
            .joinToString("\n") { rendered[it.id].orEmpty() }
        if (before.isBlank() && after.isBlank()) return
        val systemIndex = messages.indexOfFirst { it.role == ChatRole.SYSTEM }
        if (systemIndex >= 0) {
            val original = messages[systemIndex]
            messages[systemIndex] = original.copy(
                content = listOf(before, original.content, after).filter(String::isNotBlank).joinToString("\n\n")
            )
        } else {
            messages.add(0, ChatMessage(ChatRole.SYSTEM, listOf(before, after).filter(String::isNotBlank).joinToString("\n\n")))
        }
    }

    private fun mergeLastUser(
        messages: MutableList<ChatMessage>,
        presets: List<GlobalPromptPreset>,
        rendered: Map<String, String>
    ) {
        if (presets.isEmpty()) return
        val before = presets.filter { it.position == GlobalPromptInjectionPosition.BEFORE_LAST_USER }
            .joinToString("\n") { rendered[it.id].orEmpty() }
        val after = presets.filter { it.position == GlobalPromptInjectionPosition.AFTER_LAST_USER }
            .joinToString("\n") { rendered[it.id].orEmpty() }
        if (before.isBlank() && after.isBlank()) return
        val userIndex = messages.indexOfLast { it.role == ChatRole.USER }
        if (userIndex < 0) return
        messages[userIndex] = messages[userIndex].withUserInjection(before, after)
    }

    /** 独立消息的落点。深度优先；否则按锚点。全部基于**插入前**的消息列表计算。 */
    internal fun insertionIndex(messages: List<ChatMessage>, preset: GlobalPromptPreset): Int {
        if (preset.depth >= 0) return (messages.size - preset.depth).coerceIn(0, messages.size)
        val firstHistory = messages.indexOfFirst { it.role != ChatRole.SYSTEM }.takeIf { it >= 0 } ?: messages.size
        val lastUser = messages.indexOfLast { it.role == ChatRole.USER }
        return when (preset.position) {
            GlobalPromptInjectionPosition.BEFORE_SYSTEM -> 0
            GlobalPromptInjectionPosition.AFTER_SYSTEM -> firstHistory
            GlobalPromptInjectionPosition.BEFORE_HISTORY -> firstHistory
            GlobalPromptInjectionPosition.AFTER_HISTORY -> messages.size
            GlobalPromptInjectionPosition.BEFORE_LAST_USER -> if (lastUser >= 0) lastUser else messages.size
            GlobalPromptInjectionPosition.AFTER_LAST_USER -> if (lastUser >= 0) lastUser + 1 else messages.size
        }
    }

    /** 不传作用域的基础宏替换：身份宏 + 注释 + 消息宏；变量 / 随机 / 条件宏原样保留。 */
    internal fun applyMacros(text: String, macros: MacroContext?): String =
        TavernMacros.render(text, macros, null)

    /** 带作用域的完整替换（变量、随机、条件块都生效）。 */
    internal fun applyMacros(text: String, macros: MacroContext?, variables: TavernVariables?): String =
        TavernMacros.render(text, macros, variables)

    /** 列出文本里**不被执行**的宏名（供导入预览提示；不修改内容）。 */
    fun unsupportedMacros(text: String): List<String> = TavernMacros.unknownMacros(text)

    /** 文本里引用、但作用域里没有值的变量名；空作用域返回空表。 */
    fun unresolvedVariables(text: String, variables: TavernVariables?): List<String> =
        TavernMacros.unresolvedVariables(text, variables)

    private fun PromptRole.toChatRole(): ChatRole = when (this) {
        PromptRole.SYSTEM -> ChatRole.SYSTEM
        PromptRole.USER -> ChatRole.USER
        PromptRole.ASSISTANT -> ChatRole.ASSISTANT
    }

    private fun ChatMessage.withUserInjection(before: String, after: String): ChatMessage {
        val content = listOf(before, content, after).filter(String::isNotBlank).joinToString("\n\n")
        if (parts.isEmpty()) return copy(content = content)
        val nextParts = buildList {
            if (before.isNotBlank()) add(ChatPart.Text(before + "\n\n"))
            addAll(parts)
            if (after.isNotBlank()) add(ChatPart.Text("\n\n" + after))
        }
        return copy(content = content, parts = nextParts)
    }
}
