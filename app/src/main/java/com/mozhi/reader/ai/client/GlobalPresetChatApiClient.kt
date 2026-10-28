package com.mozhi.reader.ai.client

import com.mozhi.reader.ai.prompt.GlobalPromptInjector
import com.mozhi.reader.ai.prompt.MacroContext
import com.mozhi.reader.ai.prompt.TavernVariables
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.core.datastore.TavernVariableStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * CHAT 角色的轻量装饰器：让选词、伴读和段评共享同一套全局预设。
 *
 * 每轮请求做三件事（顺序不能换）：
 * 1. 读一次酒馆变量表，装进本轮的 [TavernVariables] 作用域；
 * 2. 注入——**注入过程本身会执行 `{{setvar}}`**，所以必须在读之后、发请求之前；
 * 3. 作用域发生过写入才回存，避免每轮都写 DataStore。
 */
internal class GlobalPresetChatApiClient(
    private val delegate: ChatApiClient,
    private val presets: List<GlobalPromptPreset>,
    private val variableStore: TavernVariableStore? = null,
    /** 身份宏（`{{char}}` / `{{user}}` / `{{persona}}`）的来源；缺省为空则这些宏原样保留。 */
    private val macroContext: () -> MacroContext = { MacroContext() }
) : ChatApiClient {

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions
    ): Flow<ChatDelta> = flow {
        val scope = variableStore?.let { TavernVariables(it.load()) }
        val injected = GlobalPromptInjector.inject(
            messages = messages,
            presets = presets,
            macros = GlobalPromptInjector.contextFor(messages, macroContext()),
            variables = scope
        )
        if (scope != null && scope.dirty) variableStore?.save(scope.snapshot())
        emitAll(delegate.chatStream(injected, tools, options))
    }

    override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String {
        val scope = variableStore?.let { TavernVariables(it.load()) }
        val injected = GlobalPromptInjector.inject(
            messages = messages,
            presets = presets,
            macros = GlobalPromptInjector.contextFor(messages, macroContext()),
            variables = scope
        )
        if (scope != null && scope.dirty) variableStore?.save(scope.snapshot())
        return delegate.chat(injected, options)
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = delegate.embed(texts)
}
