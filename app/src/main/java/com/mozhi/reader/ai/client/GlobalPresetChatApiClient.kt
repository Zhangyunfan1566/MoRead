package com.mozhi.reader.ai.client

import com.mozhi.reader.ai.prompt.GlobalPromptInjector
import com.mozhi.reader.ai.prompt.MacroContext
import com.mozhi.reader.ai.prompt.TavernRegexEngine
import com.mozhi.reader.ai.prompt.TavernRegexScript
import com.mozhi.reader.ai.prompt.TavernVariables
import com.mozhi.reader.ai.prompt.WorldInfoEngine
import com.mozhi.reader.ai.prompt.WorldInfoEntry
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.core.datastore.TavernRegexStore
import com.mozhi.reader.core.datastore.TavernVariableStore
import com.mozhi.reader.core.datastore.TavernWorldInfoStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * CHAT 角色的请求装饰器：让选词、伴读和段评共享同一套酒馆预设体系。
 *
 * 每一轮请求按**固定顺序**做三件事（顺序不能换）：
 *
 * 1. **正则清洗**：先按放置位把用户/AI 消息过一遍导入的正则脚本。
 *    放在注入之前是刻意的——用户的"八股抹除""破折号处理"是给正文写的，
 *    不该作用在预设注入出来的提示词条目上。
 * 2. **宏与注入**：读一次酒馆变量表，注入启用条目。
 *    **注入过程本身会执行 `{{setvar}}`**，所以必须在读之后、发请求之前；
 *    变量写过才回存，避免每轮都写 DataStore。
 * 3. 发请求。
 *
 * 正则只改**请求副本**，不动用户的会话存档（本应用没有"改写已存消息"这一趟）。
 */
internal class GlobalPresetChatApiClient(
    private val delegate: ChatApiClient,
    private val presets: List<GlobalPromptPreset>,
    private val variableStore: TavernVariableStore? = null,
    private val regexStore: TavernRegexStore? = null,
    private val worldInfoStore: TavernWorldInfoStore? = null,
    /** 身份宏（`{{char}}` / `{{user}}` / `{{persona}}`）的来源；缺省为空则这些宏原样保留。 */
    private val macroContext: () -> MacroContext = { MacroContext() }
) : ChatApiClient {

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions
    ): Flow<ChatDelta> = flow {
        emitAll(delegate.chatStream(prepare(messages), tools, options))
    }

    override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String =
        delegate.chat(prepare(messages), options)

    override suspend fun embed(texts: List<String>): List<FloatArray> = delegate.embed(texts)

    private suspend fun prepare(messages: List<ChatMessage>): List<ChatMessage> {
        val scripts = regexStore?.active().orEmpty()
        val cleaned = applyRegex(messages, scripts)
        val scope = variableStore?.let { TavernVariables(it.load()) }
        val injected = GlobalPromptInjector.inject(
            messages = cleaned,
            presets = presets,
            macros = GlobalPromptInjector.contextFor(cleaned, macroContext()),
            variables = scope
        )
        if (scope != null && scope.dirty) variableStore?.save(scope.snapshot())
        return applyWorldInfo(injected)
    }

    /**
     * 世界书：关键词命中后按位置注入。
     *
     * - `before` / `after` 分别**前置 / 后置**到 system 消息（对应酒馆的「↑Char / ↓Char」，
     *   角色定义就在 system 里）；
     * - `atDepth` 作为独立消息按各自深度插入，角色取条目上的 role。
     *
     * 扫描材料只用**非 system** 的文本消息——酒馆扫的是聊天历史，不是系统提示词。
     */
    private suspend fun applyWorldInfo(messages: List<ChatMessage>): List<ChatMessage> {
        val store = worldInfoStore ?: return messages
        val worldInfo = store.current()
        if (!worldInfo.enabled || worldInfo.entries.isEmpty()) return messages

        val scanMaterial = messages
            .filter { it.role != ChatRole.SYSTEM && it.parts.isEmpty() }
            .map { it.content }
        val result = WorldInfoEngine.scan(worldInfo.entries, scanMaterial, worldInfo.options)
        if (result.isEmpty) return messages

        val output = messages.toMutableList()
        val before = result.before.joinToString("\n\n") { it.content }
        val after = result.after.joinToString("\n\n") { it.content }
        if (before.isNotBlank() || after.isNotBlank()) {
            val systemIndex = output.indexOfFirst { it.role == ChatRole.SYSTEM }
            if (systemIndex >= 0) {
                val original = output[systemIndex]
                output[systemIndex] = original.copy(
                    content = listOf(before, original.content, after)
                        .filter(String::isNotBlank).joinToString("\n\n")
                )
            } else {
                output.add(0, ChatMessage(ChatRole.SYSTEM, listOf(before, after).filter(String::isNotBlank).joinToString("\n\n")))
            }
        }

        if (result.atDepth.isNotEmpty()) {
            val inserts = result.atDepth.map { entry ->
                (output.size - entry.depth).coerceIn(0, output.size) to
                    ChatMessage(entry.role.toChatRole(), entry.content)
            }
            inserts.groupBy({ it.first }, { it.second })
                .entries
                .sortedByDescending { it.key }
                .forEach { (index, group) ->
                    group.asReversed().forEach { message -> output.add(index.coerceIn(0, output.size), message) }
                }
        }
        return output
    }

    private fun Int.toChatRole(): ChatRole = when (this) {
        WorldInfoEntry.ROLE_USER -> ChatRole.USER
        WorldInfoEntry.ROLE_ASSISTANT -> ChatRole.ASSISTANT
        else -> ChatRole.SYSTEM
    }

    /**
     * 按角色决定放置位、按"距末尾的距离"决定深度。system 消息不属于这两类放置位，原样放过；
     * 多模态消息（带图片 parts）跳过——它的 content 只是文本摘要，改它没有意义。
     */
    private fun applyRegex(messages: List<ChatMessage>, scripts: List<TavernRegexScript>): List<ChatMessage> {
        if (scripts.isEmpty() || messages.isEmpty()) return messages
        return messages.mapIndexed { index, message ->
            if (message.parts.isNotEmpty()) return@mapIndexed message
            val placement = when (message.role) {
                ChatRole.USER -> TavernRegexScript.PLACEMENT_USER_INPUT
                ChatRole.ASSISTANT -> TavernRegexScript.PLACEMENT_AI_OUTPUT
                else -> return@mapIndexed message
            }
            val depth = messages.size - 1 - index
            val text = TavernRegexEngine.applyAll(message.content, scripts, placement, depth)
            if (text == message.content) message else message.copy(content = text)
        }
    }
}
