package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatDelta
import com.mozhi.reader.ai.client.ChatUsageAccumulator
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatOptions
import com.mozhi.reader.ai.client.ChatPart
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.prompt.TavernContextBudget
import com.mozhi.reader.ai.client.ToolCall
import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.ai.memory.RollingSummarizer
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.AttachmentStore
import com.mozhi.reader.core.library.MessageAttachment
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer

/** What the UI sees while the agent works. */
sealed interface AgentEvent {
    /** Identity allocated before the first token AND before the database insert. */
    data class RoundStarted(val roundId: String) : AgentEvent

    /** Incremental assistant text of the current round. */
    data class Text(val text: String) : AgentEvent

    /**
     * 思维链增量。只上屏与落库，永不回传给模型——各方言都不接受自己吐出的 reasoning
     * 再作为历史喂回去。不产出 reasoning 的模型永远不会发出这个事件。
     */
    data class Reasoning(val text: String) : AgentEvent

    /**
     * 当前工具轮次的文字已持久化；UI 应在此刻将实时气泡交接给消息列表。
     * 显式轮次边界避免同一段文字同时以「已落库 + 流式」渲染两次。
     */
    data class RoundCommitted(val message: MessageEntity) : AgentEvent

    /** 工具开始执行；[callId] 让界面能把开始/完成更新到同一条时间线。 */
    data class ToolRun(
        val callId: String,
        val toolName: String,
        val displayName: String,
        /** 模型给出的原始参数 JSON，供界面压成一行摘要（如「检索：主角的身世」）。 */
        val arguments: String = "{}"
    ) : AgentEvent

    /** 工具执行结束；只给界面安全的状态摘要，不暴露整段检索原文。 */
    data class ToolFinished(
        val callId: String,
        val toolName: String,
        val displayName: String,
        val succeeded: Boolean,
        val detail: String,
        /**
         * 结果预览，成功时也给。仅用于「过程」卡展开后本地显示，
         * 已在 AgentLoop 内按上限截断，且从不写日志。
         */
        val resultPreview: String = ""
    ) : AgentEvent
}

/**
 * The agent loop of DEVELOPMENT_PLAN §4.4: stream a turn, execute any requested tools locally,
 * append their results, and go again — at most [MAX_ROUNDS] rounds. Every assistant turn and tool
 * result is persisted immediately, so a cancelled loop leaves a consistent, resumable history.
 * With no tools this degenerates to plain streaming with persistence.
 */
@Singleton
class AgentLoop @Inject constructor(
    private val chatDao: ChatDao,
    private val clientFactory: dagger.Lazy<AiClientFactory>,
    private val attachmentStore: dagger.Lazy<AttachmentStore>,
    private val rollingSummarizer: dagger.Lazy<RollingSummarizer>,
    private val requestSettingsStore: com.mozhi.reader.core.datastore.TavernRequestSettingsStore,
    private val toolExecutor: AgentToolExecutor = AgentToolExecutor()
) {
    private val toolRunner = ParallelToolRunner()

    /**
     * Streams one agent turn for the conversation's current history.
     *
     * [systemPrompt] 非空时在本轮用它替换历史里的 system 消息（不落库）：
     * 伴读会话每轮由 ContextBuilder 按最新进度/记忆重建系统提示词，
     * 落库的那份只是会话创建时的快照。
     */
    fun run(
        conversationId: Long,
        tools: List<AgentTool>,
        systemPrompt: String? = null,
        modelRole: ModelRole = ModelRole.CHAT,
        maxRounds: Int = MAX_ROUNDS,
        validateContext: suspend () -> Unit = {}
    ): Flow<AgentEvent> = runWith(conversationId, tools, systemPrompt, maxRounds, validateContext) {
        val resolved = clientFactory.get().forRole(modelRole)
        Streamer { messages, specs ->
            resolved.client.chatStream(messages, specs, resolved.options)
        }
    }

    /** One resolved model turn; injectable for tests. */
    fun interface Streamer {
        fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<ChatDelta>
    }

    /**
     * 段评讨论串等轻量场景的独立循环：历史由调用方内存组装，任何消息都不落
     * conversations/messages（讨论内容有自己的 annotation_replies 归宿，也不该
     * 污染统计口径与记忆固化）。工具执行与事件流和 [run] 同构。
     */
    fun runDetached(
        history: List<ChatMessage>,
        tools: List<AgentTool>,
        maxRounds: Int = DETACHED_MAX_ROUNDS,
        modelRole: ModelRole = ModelRole.CHAT
    ): Flow<AgentEvent> = runDetachedWith(history, tools, maxRounds) {
        val resolved = clientFactory.get().forRole(modelRole)
        Streamer { messages, specs ->
            resolved.client.chatStream(messages, specs, resolved.options)
        }
    }

    internal fun runDetachedWith(
        history: List<ChatMessage>,
        tools: List<AgentTool>,
        maxRounds: Int,
        resolve: suspend () -> Streamer
    ): Flow<AgentEvent> = flow {
        if (history.none { it.role == ChatRole.USER }) throw AiClientException.Empty()
        val working = history.toMutableList()
        val streamer = resolve()
        val specs = tools.map { it.spec }
        val byName = tools.associateBy { it.spec.name }
        var producedText = false

        repeat(maxRounds.coerceAtLeast(1)) { round ->
            val text = StringBuilder()
            var requested: List<ToolCall> = emptyList()
            streamer.stream(working, specs).collect { delta ->
                when (delta) {
                    is ChatDelta.Text -> {
                        text.append(delta.text)
                        emit(AgentEvent.Text(delta.text))
                    }
                    is ChatDelta.Reasoning -> emit(AgentEvent.Reasoning(delta.text))
                    is ChatDelta.ToolCalls -> requested = delta.calls
                    is ChatDelta.Usage -> Unit
                }
            }

            if (requested.isEmpty()) {
                if (text.isBlank() && !producedText) throw AiClientException.Empty()
                return@flow
            }

            if (text.isNotBlank()) producedText = true
            working.add(ChatMessage(ChatRole.ASSISTANT, text.toString(), toolCalls = requested))
            executeTools(requested, byName).forEachIndexed { index, result ->
                working.add(ChatMessage(ChatRole.TOOL, result.content, toolCallId = requested[index].id))
            }

            if (round == maxRounds - 1 && !producedText) {
                val notice = "（已达到工具调用上限，回复基于目前掌握的信息）"
                emit(AgentEvent.Text(notice))
            }
        }
    }

    internal fun runWith(
        conversationId: Long,
        tools: List<AgentTool>,
        systemPrompt: String? = null,
        maxRounds: Int = MAX_ROUNDS,
        validateContext: suspend () -> Unit = {},
        resolve: suspend () -> Streamer
    ): Flow<AgentEvent> = flow {
        val entities = chatDao.getMessages(conversationId)
        val conversation = chatDao.getConversation(conversationId)
        val consolidatedThrough = conversation?.memoryConsolidatedThroughMessageId ?: 0L
        val persistedSystem = entities
            .takeWhile { it.role == ChatRole.SYSTEM.wire }
            .map(::toChatMessage)
        // 只有最新一条 user 消息重发真实图片/文件内容，历史轮降级为占位标记控成本
        val lastUserId = entities.lastOrNull { it.role == ChatRole.USER.wire }?.id
        // 互动回答：最近几条保留原始界面源码（用户说「把滑块上限改成 30」时模型能照着改），
        // 更早的换成纯文字投影，几十 KB 的数据表不再每轮计费。
        val rawWidgetIds = entities
            .filter { it.role == ChatRole.ASSISTANT.wire && it.id > consolidatedThrough && isInteractiveReply(it.content) }
            .takeLast(KEEP_RAW_WIDGET_REPLIES)
            .mapTo(hashSetOf()) { it.id }
        val persisted = entities
            .filter { it.id > consolidatedThrough && it.role != ChatRole.SYSTEM.wire }
            .map { entity ->
                if (entity.id == lastUserId) {
                    toChatMessage(entity).copy(parts = hydrateParts(entity))
                } else {
                    toChatMessage(entity, compactWidgets = entity.id !in rawWidgetIds)
                }
            }
        val system = systemPrompt?.let { listOf(ChatMessage(ChatRole.SYSTEM, it)) }
            ?: persistedSystem
        // 前情提要补的是「已滑出 20 条窗口、又没攒够 30 条去固化」那段的裂缝。
        // 与 systemPrompt 覆写同一纪律：只在组装请求时注入，永不作为消息落库。
        val summary = rollingSummarizer.get()
            .block(conversation?.rollingSummary.orEmpty())
            ?.let { listOf(ChatMessage(ChatRole.SYSTEM, it)) }
            .orEmpty()
        val rest = pairToolResults(persisted)
        val history = (system + summary + rest.drop(windowStart(rest))).toMutableList()
        if (history.none { it.role == ChatRole.USER }) throw AiClientException.Empty()
        val streamer = resolve()
        val specs = tools.map { it.spec }
        val byName = tools.associateBy { it.spec.name }
        var producedText = false

        val roundLimit = maxRounds.coerceIn(1, MAX_ROUNDS)
        // 去重器按**整轮对话**存活：跨轮重复（模型原地打转）才是主要形态。
        val deduper = ToolCallDeduper()
        repeat(roundLimit) { round ->
            // Multi-book conversations must recheck frozen consent before sending ANY history.
            validateContext()
            val roundId = UUID.randomUUID().toString()
            emit(AgentEvent.RoundStarted(roundId))
            val text = StringBuilder()
            // 思维链按轮累积：它属于「这一条回复是怎么想出来的」，跟着该轮的 assistant 消息落库。
            val reasoning = StringBuilder()
            val usage = ChatUsageAccumulator()
            var requested: List<ToolCall> = emptyList()
            streamer.stream(history, specs).collect { delta ->
                when (delta) {
                    is ChatDelta.Text -> {
                        text.append(delta.text)
                        emit(AgentEvent.Text(delta.text))
                    }
                    is ChatDelta.Reasoning -> {
                        reasoning.append(delta.text)
                        emit(AgentEvent.Reasoning(delta.text))
                    }
                    is ChatDelta.ToolCalls -> requested = delta.calls
                    is ChatDelta.Usage -> usage.accept(delta)
                }
            }

            validateContext()

            if (requested.isEmpty()) {
                val reply = text.toString()
                if (reply.isBlank() && !producedText) throw AiClientException.Empty()
                if (reply.isNotBlank()) {
                    persistAssistant(
                        conversationId = conversationId,
                        content = reply,
                        toolCalls = emptyList(),
                        reasoning = reasoning.toString(),
                        clientRoundId = roundId,
                        usage = usage
                    )?.let { message ->
                        emit(AgentEvent.RoundCommitted(message))
                    }
                }
                return@flow
            }

            if (text.isNotBlank()) producedText = true
            val committedMessage = persistAssistant(
                conversationId = conversationId,
                content = text.toString(),
                toolCalls = requested,
                reasoning = reasoning.toString(),
                clientRoundId = roundId,
                usage = usage
            )
            if (committedMessage != null) {
                emit(AgentEvent.RoundCommitted(committedMessage))
            }
            history.add(ChatMessage(ChatRole.ASSISTANT, text.toString(), toolCalls = requested))

            // 互不依赖的只读调用并发执行；落库与写回历史仍按模型给出的调用顺序。
            val results = executeTools(requested, byName, deduper)
            requested.forEachIndexed { index, call ->
                val result = results[index]
                val toolCompletedAt = System.currentTimeMillis()
                chatDao.insertMessage(
                    MessageEntity(
                        conversationId = conversationId,
                        role = ChatRole.TOOL.wire,
                        content = result.content,
                        toolCallId = call.id,
                        createdAt = toolCompletedAt
                    )
                )
                chatDao.touchConversation(conversationId, toolCompletedAt)
                history.add(ChatMessage(ChatRole.TOOL, result.content, toolCallId = call.id))
            }

            if (round == roundLimit - 1) {
                val notice = "（已达到单轮工具调用上限，回复基于目前掌握的信息）"
                val noticeRoundId = UUID.randomUUID().toString()
                emit(AgentEvent.RoundStarted(noticeRoundId))
                emit(AgentEvent.Text(notice))
                persistAssistant(conversationId, notice, emptyList(), clientRoundId = noticeRoundId)?.let { message ->
                    emit(AgentEvent.RoundCommitted(message))
                }
            }
        }
    }

    /**
     * 执行这一轮的工具调用，并做**同轮去重**（见 [ToolCallDeduper]）。
     *
     * 去重在调 [ParallelToolRunner] **之前**完成，不放进 `execute` 回调里：
     * 回调是并发跑的，"第二条有没有命中缓存"会取决于调度顺序，同一轮里的重复调用就漏掉了。
     */
    private suspend fun FlowCollector<AgentEvent>.executeTools(
        calls: List<ToolCall>,
        byName: Map<String, AgentTool>,
        deduper: ToolCallDeduper
    ): List<ToolResult> {
        val plan = deduper.plan(calls) { name -> name in ConcurrencySafeTools.NAMES }

        // 复用的调用也播一次"完成"，界面上看得出这条没有真跑。
        plan.reused.forEach { (index, result) ->
            val call = calls[index]
            emit(AgentEvent.ToolRun(call.id, call.name, displayNameOf(call, byName), call.arguments))
            emit(toolFinishedEvent(call, byName, result, detail = "复用上次结果"))
        }

        val freshResults = toolRunner.run(
            calls = plan.fresh.map { it.second },
            concurrencySafe = { call -> byName[call.name]?.concurrencySafe == true },
            execute = { call -> toolExecutor.execute(call, byName[call.name]) }
        ) { progress ->
            val call = plan.fresh[progress.index].second
            val displayName = displayNameOf(call, byName)
            when (progress) {
                is ParallelToolRunner.Progress.Started ->
                    emit(AgentEvent.ToolRun(call.id, call.name, displayName, call.arguments))
                is ParallelToolRunner.Progress.Finished ->
                    emit(toolFinishedEvent(call, byName, progress.result))
            }
        }

        val byIndex = HashMap<Int, ToolResult>()
        plan.fresh.forEachIndexed { index, entry ->
            val (original, call) = entry
            byIndex[original] = freshResults[index]
            deduper.remember(call, freshResults[index])
        }
        // 这一轮动过写入类工具 → 缓存的读取结果全部作废，下一轮再读要重新查。
        if (plan.fresh.any { (_, call) -> call.name !in ConcurrencySafeTools.NAMES }) deduper.invalidate()
        // 本轮内的重复：结果就是它重复的那条的结果。
        plan.duplicateOf.forEach { (index, first) ->
            val source = byIndex[first]
            byIndex[index] = source?.withReuseNote()
                ?: ToolResult.Failure("DUPLICATE_CALL", "这次调用与同一轮里的另一次完全相同，已跳过。")
        }
        plan.reused.forEach { (index, result) -> byIndex[index] = result.withReuseNote() }

        return calls.indices.map { index ->
            byIndex[index] ?: ToolResult.Failure("DEDUP_MISS", "这次调用的结果缺失，请重新发起。")
        }
    }

    private fun displayNameOf(call: ToolCall, byName: Map<String, AgentTool>): String =
        byName[call.name]?.displayName ?: "调用 ${call.name}"

    private fun toolFinishedEvent(
        call: ToolCall,
        byName: Map<String, AgentTool>,
        result: ToolResult,
        detail: String? = null
    ): AgentEvent.ToolFinished = AgentEvent.ToolFinished(
        callId = call.id,
        toolName = call.name,
        displayName = displayNameOf(call, byName),
        succeeded = result is ToolResult.Success,
        detail = detail ?: when (result) {
            is ToolResult.Success -> if (result.partial) "部分完成" else "已完成"
            is ToolResult.Failure -> result.content.take(MAX_TOOL_STATUS_CHARS)
        },
        resultPreview = result.content.take(MAX_TOOL_PREVIEW_CHARS)
    )

    /** 复用结果时把"这条没真跑"写进工具结果本身——模型看得到，不会误以为是新查到的东西。 */
    private fun ToolResult.withReuseNote(): ToolResult = mapContent { content ->
        content + "\n\n（本次没有重复执行：这一轮已经用相同参数调用过这个工具，以上是上次的结果。）"
    }

    private suspend fun persistAssistant(
        conversationId: Long,
        content: String,
        toolCalls: List<ToolCall>,
        reasoning: String = "",
        clientRoundId: String,
        usage: ChatUsageAccumulator? = null
    ): MessageEntity? {
        if (content.isBlank() && toolCalls.isEmpty()) return null
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            conversationId = conversationId,
            role = ChatRole.ASSISTANT.wire,
            content = content,
            toolCallsJson = toolCalls.takeIf { it.isNotEmpty() }
                ?.let { AiJson.encodeToString(ListSerializer(ToolCall.serializer()), it) },
            // 空串存 null：界面据「是否为 null」决定整条思维链条要不要出现。
            reasoningContent = reasoning.takeIf(String::isNotBlank)?.take(MAX_REASONING_CHARS),
            createdAt = now,
            clientRoundId = clientRoundId,
            tokenUsage = usage?.totalTokens,
            inputTokens = usage?.inputTokens,
            outputTokens = usage?.outputTokens,
            generationTimeMs = usage?.elapsedMillis()
        )
        val messageId = chatDao.insertMessage(message)
        chatDao.touchConversation(conversationId, now)
        return message.copy(id = messageId)
    }

    /**
     * 停止生成可能发生在一轮工具执行到一半：assistant 的调用已落库，部分结果还没有。
     * 各家协议都拒绝「有调用没结果」或「有结果没调用」的历史，这里补上中断说明、
     * 丢掉找不到调用的孤立结果，让会话还能继续。
     */
    internal fun pairToolResults(messages: List<ChatMessage>): List<ChatMessage> {
        val repaired = ArrayList<ChatMessage>(messages.size)
        var pending = LinkedHashSet<String>()
        fun flushPending() {
            pending.forEach { id -> repaired += ChatMessage(ChatRole.TOOL, INTERRUPTED_TOOL_RESULT, toolCallId = id) }
            pending = LinkedHashSet()
        }
        messages.forEach { message ->
            if (message.role == ChatRole.TOOL) {
                if (message.toolCallId != null && pending.remove(message.toolCallId)) repaired += message
                return@forEach
            }
            flushPending()
            repaired += message
            if (message.role == ChatRole.ASSISTANT) message.toolCalls.mapTo(pending) { it.id }
        }
        flushPending()
        return repaired
    }

    /**
     * §4.5 的对话窗口：条数与**长度**双约束（见 [TavernContextBudget]）。
     *
     * 只按条数切在长对话里不够用：20 条里只要有几条长正文或工具结果，一次请求就可能几万字符。
     * 长度预算来自预设请求参数（用户可关），起点必须落在 user 消息上——从中间切开会产生
     * 没有对应调用的 tool 结果，各方言都会拒绝。找不到合适起点时宁可整段带上。
     */
    private suspend fun windowStart(rest: List<ChatMessage>): Int {
        val settings = runCatching { requestSettingsStore.current() }.getOrNull()
        val budget = if (settings == null || !settings.trimHistory) {
            0
        } else {
            TavernContextBudget.budgetChars(settings.maxContextTokens, settings.historyBudgetChars)
        }
        return TavernContextBudget.windowStart(rest, WINDOW_MESSAGES, budget)
    }

    private fun toChatMessage(entity: MessageEntity, compactWidgets: Boolean = false): ChatMessage = ChatMessage(
        role = ChatRole.fromWire(entity.role),
        content = contentWithAttachmentMarks(entity, compactWidgets),
        toolCalls = entity.toolCallsJson?.let { json ->
            runCatching {
                AiJson.decodeFromString(ListSerializer(ToolCall.serializer()), json)
            }.getOrNull()
        }.orEmpty(),
        toolCallId = entity.toolCallId
    )

    private fun isInteractiveReply(content: String): Boolean =
        com.mozhi.reader.ai.genui.GenUiDocument.mightContainDil(content) &&
            com.mozhi.reader.ai.genui.GenUiDocument.parse(content).isInteractive

    /**
     * 历史消息的附件降级为占位标记，避免每轮重发 base64。
     * 用户消息若带着发送时的界面状态摘要（uiContextJson），拼在正文前交给模型——只在组装请求时注入，
     * 聊天里看不到。较早的互动回复换成纯文字投影 + 一行占位说明。
     */
    private fun contentWithAttachmentMarks(entity: MessageEntity, compactWidgets: Boolean = false): String {
        val uiContext = if (entity.role == ChatRole.USER.wire) {
            com.mozhi.reader.ai.genui.GenUiStateDigest.decodeContext(entity.uiContextJson)
        } else null
        val body = if (compactWidgets && entity.role == ChatRole.ASSISTANT.wire && isInteractiveReply(entity.content)) {
            com.mozhi.reader.ai.genui.GenUiFallback.projectSource(entity.content) + "\n[此处原有互动卡片，已按文字概括]"
        } else {
            entity.content
        }
        val attachments = MessageAttachment.decode(entity.attachmentsJson)
        if (attachments.isEmpty()) return listOfNotNull(uiContext, body).filter(String::isNotBlank).joinToString("\n")
        val marks = attachments.joinToString(" ") { attachment ->
            if (attachment.type == MessageAttachment.TYPE_IMAGE) {
                "[图片]"
            } else {
                "[文件：${attachment.name ?: "附件"}]"
            }
        }
        return listOfNotNull(uiContext, marks, body).filter(String::isNotBlank).joinToString("\n")
    }

    /** 最新 user 轮：图片转 base64 part，文本文件内容并入 text part。 */
    private suspend fun hydrateParts(entity: MessageEntity): List<ChatPart> {
        val attachments = MessageAttachment.decode(entity.attachmentsJson)
        if (attachments.isEmpty()) return emptyList()
        val parts = mutableListOf<ChatPart>()
        attachments.filter { it.type == MessageAttachment.TYPE_IMAGE }.forEach { attachment ->
            attachmentStore.get().loadImageBase64(attachment)?.let { base64 ->
                parts += ChatPart.Image(base64 = base64, mimeType = attachment.mime)
            }
        }
        val fileTexts = attachments
            .filter { it.type == MessageAttachment.TYPE_TEXT_FILE }
            .mapNotNull { attachment ->
                attachmentStore.get().loadTextContent(attachment)?.let { text ->
                    "【附件 ${attachment.name ?: "文件"}】\n$text"
                }
            }
        val uiContext = com.mozhi.reader.ai.genui.GenUiStateDigest.decodeContext(entity.uiContextJson)
        val text = (listOfNotNull(uiContext) + fileTexts + entity.content).filter(String::isNotBlank).joinToString("\n\n")
        parts += ChatPart.Text(text.ifBlank { entity.content })
        return parts
    }

    private companion object {
        /** 单轮对话的工具跳数上限。有了 [ToolCallDeduper]，多出来的跳数不会被原地打转吃掉。 */
        const val MAX_ROUNDS = 12
        const val INTERRUPTED_TOOL_RESULT = "（这次工具调用被中断，没有结果）"
        const val DETACHED_MAX_ROUNDS = 3
        const val WINDOW_MESSAGES = 20
        /** 历史里保留原始界面源码的互动回复条数；更早的换成纯文字投影。 */
        const val KEEP_RAW_WIDGET_REPLIES = 2
        const val MAX_TOOL_STATUS_CHARS = 120
        /** 「过程」卡展开后显示的结果预览上限；只进内存与界面，不进日志。 */
        const val MAX_TOOL_PREVIEW_CHARS = 600
        /** 思维链落库上限：它只服务展示，没必要为一次长推理撑大消息表。 */
        const val MAX_REASONING_CHARS = 20_000
    }
}
