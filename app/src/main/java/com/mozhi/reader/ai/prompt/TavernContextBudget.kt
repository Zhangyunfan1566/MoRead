package com.mozhi.reader.ai.prompt

import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole

/**
 * 对话历史的窗口裁剪（纯逻辑，可直接单测）。
 *
 * 原来的窗口只按**条数**（`WINDOW_MESSAGES = 20`）切。这在长对话里不够用：
 * 20 条里只要有几条是长正文或工具结果，一次请求就可能几万字符——用户看到的是
 * "聊到一半突然报错 / 费用飙升"。这里补上**长度**维度，与条数取更严的那个。
 *
 * 两条硬约束（都是各家协议会拒绝的形态）：
 * 1. **窗口起点必须落在 user 消息上**——从中间切开会产生"有 tool 结果没 tool 调用"的历史；
 * 2. **至少带上最后一条消息**——预算再小也不能发出空历史。
 *
 * 酒馆用 token 计预算，本应用没有 tokenizer，按 [CHARS_PER_TOKEN] 近似换算（与项目既有启发式一致）。
 */
object TavernContextBudget {

    /** 4 字符 ≈ 1 token。中文会偏乐观，所以预算本身再留了余量（见 [DEFAULT_BUDGET_CHARS]）。 */
    const val CHARS_PER_TOKEN = 4

    /** 默认历史预算：约 6k token，绝大多数供应商都吃得下。 */
    const val DEFAULT_BUDGET_CHARS = 24_000

    /**
     * 把酒馆的 `openai_max_context`（token）折成字符预算。
     *
     * **只在预设值比默认更小时才采用**：实测那份预设写的是 `2000000`——
     * 那类"当作无限大"的填法很常见，直接照搬等于没有预算；
     * 而一个明确写小值的预设（例如 8192）表达的是"我这边窗口就这么大"，应当尊重。
     */
    fun budgetChars(presetMaxContextTokens: Int?, fallback: Int = DEFAULT_BUDGET_CHARS): Int {
        if (fallback <= 0) return 0
        val tokens = presetMaxContextTokens ?: return fallback
        if (tokens <= 0) return fallback
        val fromPreset = tokens * CHARS_PER_TOKEN
        return minOf(fallback, fromPreset)
    }

    /** 从窗口起点到末尾的字符数（含分隔符，够近似即可）。 */
    fun lengthOf(messages: List<ChatMessage>): Int =
        messages.sumOf { it.content.length + 1 }

    /**
     * 计算窗口起点。
     *
     * @param maxMessages 条数上限；`<= 0` 表示不限
     * @param budgetChars 字符预算；`<= 0` 表示不限
     * @return 起点下标；`0` 表示整段都带
     */
    fun windowStart(messages: List<ChatMessage>, maxMessages: Int, budgetChars: Int): Int {
        if (messages.isEmpty()) return 0
        val countLimited = if (maxMessages in 1 until messages.size) messages.size - maxMessages else 0
        val sizeLimited = if (budgetChars > 0) sizeStart(messages, budgetChars) else 0
        val desired = maxOf(countLimited, sizeLimited)
        if (desired <= 0) return 0
        if (desired >= messages.size) return messages.size - 1

        // 起点必须是 user。**优先往后找**：起点落在 user 上就已经不会产生"有 tool 结果没 tool 调用"
        // 的历史，而往后对齐比往前对齐更贴合预算——往前找会把那条"已经超出预算"的消息又拉回来，
        // 预算就形同虚设（实测：一条 20 万字符的历史消息会让整个预算失效）。
        // 后面没有 user 了（例如末尾是助手消息）才往前退，退到 0 就整段带上。
        for (index in desired until messages.size) if (messages[index].role == ChatRole.USER) return index
        for (index in desired - 1 downTo 0) if (messages[index].role == ChatRole.USER) return index
        return 0
    }

    /** 从末尾往前累加，直到超预算；返回应该从哪一条开始带。 */
    private fun sizeStart(messages: List<ChatMessage>, budgetChars: Int): Int {
        var used = 0
        var index = messages.size - 1
        while (index >= 0) {
            used += messages[index].content.length + 1
            if (used > budgetChars && index < messages.size - 1) return index + 1
            index -= 1
        }
        return 0
    }
}
