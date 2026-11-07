package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一轮对话内的工具调用去重（纯逻辑，可直接单测）。
 *
 * **为什么需要**：模型在原地打转时会把同一个工具、同样的参数反复调——
 * 实测过的形态是"查了没看到想要的东西 → 换个说法再查一次 → 还是同一批结果"，
 * 每一圈都要花一次钱、占一跳预算，最后还可能撞上跳数上限草草收口。
 * 同一个 (工具名, 规范化参数) 在一轮对话里只需要真跑一次。
 *
 * 两种重复都要管：
 * - **跨轮重复**：上一轮已经跑过 → 直接复用上次结果；
 * - **同一轮内重复**：模型一次给了两条一样的调用 → 只跑第一条，第二条复用它的结果。
 *   （不能简单地让两条并发跑：那样"第二条是否命中缓存"取决于调度顺序。）
 *
 * 参数按 JSON 规范化后比较：`{"a":1,"b":2}` 与 `{"b":2,"a":1}` 视为同一次调用。
 */
internal class ToolCallDeduper {

    private val done = LinkedHashMap<String, ToolResult>()

    /** 一次调度的计划：哪些真要跑、哪些复用缓存、哪些是本轮内的重复。 */
    data class Plan(
        /** 原始下标 → 需要真正执行的调用（保持模型给出的先后）。 */
        val fresh: List<Pair<Int, ToolCall>>,
        /** 原始下标 → 跨轮命中的缓存结果。 */
        val reused: Map<Int, ToolResult>,
        /** 原始下标 → 本轮里它重复的那条调用的原始下标。 */
        val duplicateOf: Map<Int, Int>
    )

    /**
     * @param readOnly 该工具名是不是只读的。**只有只读调用参与去重**：
     *   写入类工具（加批注、写笔记、生图、整理书库…）的结果依赖当时的书库状态，
     *   缓存它会让"先读、再改、再读"拿到改动前的旧内容。
     *   只读名单直接复用并发调度那份 [ConcurrencySafeTools.NAMES]。
     */
    fun plan(calls: List<ToolCall>, readOnly: (String) -> Boolean): Plan {
        val fresh = mutableListOf<Pair<Int, ToolCall>>()
        val reused = LinkedHashMap<Int, ToolResult>()
        val duplicateOf = LinkedHashMap<Int, Int>()
        val firstIndexForKey = HashMap<String, Int>()
        calls.forEachIndexed { index, call ->
            if (!readOnly(call.name)) {
                // 写入是**屏障**：它之前缓存的读取结果不再可信，同轮里后面再读必须重新查。
                done.clear()
                firstIndexForKey.clear()
                fresh += index to call
                return@forEachIndexed
            }
            val key = key(call)
            val cached = done[key]
            if (cached != null) {
                reused[index] = cached
                return@forEachIndexed
            }
            val first = firstIndexForKey[key]
            if (first != null) {
                duplicateOf[index] = first
                return@forEachIndexed
            }
            firstIndexForKey[key] = index
            fresh += index to call
        }
        return Plan(fresh, reused, duplicateOf)
    }

    /**
     * 执行完一条就记下来。**只记成功结果**：失败往往是暂时的（网络、限流、暂时读不到），
     * 模型换个时机重试同一次调用是合法的，不该被去重短路成同一条失败。
     */
    fun remember(call: ToolCall, result: ToolResult) {
        if (result is ToolResult.Success) done[key(call)] = result
    }

    /** 一轮里跑过写入类工具之后调用：此前缓存的读取结果全部作废。 */
    fun invalidate() {
        done.clear()
    }

    internal fun key(call: ToolCall): String = call.name + '\u0000' + normalize(call.arguments)

    /**
     * 参数规范化：先当 JSON 解析、按键排序重新序列化；解析不了就退回原文（去掉首尾空白）。
     * 参数本身不合法时不去重——那种情况要让它照常走到 INVALID_ARGUMENT，模型才知道要改格式。
     */
    internal fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val element = runCatching { AiJson.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
        return canonical(element)
    }

    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { it.key + ":" + canonical(it.value) }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> element.toString()
        else -> element.toString()
    }
}
