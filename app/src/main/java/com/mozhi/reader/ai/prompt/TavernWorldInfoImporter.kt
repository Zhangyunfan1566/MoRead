package com.mozhi.reader.ai.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 导入酒馆的**世界书**（World Info / Lorebook）JSON。
 *
 * 为什么单独一个导入器：酒馆预设里的 `worldInfoBefore` / `worldInfoAfter` 只是**占位标记**，
 * 世界书正文根本不在预设文件里——它在 `data/<user>/worlds/*.json`，或者内嵌在角色卡里。
 * 所以"照搬酒馆预设体系"必须能单独吃一份世界书文件。
 *
 * 兼容三种常见形状：
 * - `{"entries": {"0": {...}, "1": {...}}}` —— 酒馆 v1 导出（`entries` 是 **uid 为键的对象**）；
 * - `{"entries": [ {...}, {...} ]}` —— 数组形式；
 * - 根就是数组。
 *
 * 同时兼容酒馆 v2 / 角色卡内嵌的字段别名（`keys` / `secondary_keys` / `insertion_order` /
 * `enabled` / `name`）。**没有对应的字段一律忽略，但不丢条目**——用户在界面上还能看到、还能改。
 */
object TavernWorldInfoImporter {

    data class Skipped(val name: String, val reason: String)

    data class Result(
        val entries: List<WorldInfoEntry>,
        val skipped: List<Skipped> = emptyList(),
        val sourceName: String? = null
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    class ImportException(message: String) : IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun import(text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw ImportException("文件是空的")
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
            ?: throw ImportException("不是合法的 JSON")

        val (array, name) = locateEntries(root)
            ?: throw ImportException("没有找到 entries，这看起来不是酒馆的世界书文件")

        val entries = mutableListOf<WorldInfoEntry>()
        val skipped = mutableListOf<Skipped>()
        array.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            val content = obj.str("content")
            val label = obj.str("comment").ifBlank { obj.str("name") }.ifBlank { "条目 " + (index + 1) }
            if (content.isBlank()) {
                skipped += Skipped(label, "content 为空")
                return@forEachIndexed
            }
            entries += WorldInfoEntry(
                id = obj.str("uid").ifBlank { obj.str("id") }.ifBlank { "wi-" + index },
                comment = label.take(200),
                content = content,
                keys = obj.stringList("key").ifEmpty { obj.stringList("keys") },
                secondaryKeys = obj.stringList("keysecondary").ifEmpty { obj.stringList("secondary_keys") },
                selectiveLogic = obj.int("selectiveLogic") ?: obj.int("selective_logic")
                    ?: WorldInfoEntry.LOGIC_AND_ANY,
                constant = obj.bool("constant") == true,
                disabled = obj.bool("disable") == true || obj.bool("disabled") == true,
                position = obj.int("position") ?: WorldInfoEntry.POSITION_BEFORE,
                depth = obj.int("depth") ?: WorldInfoEntry.DEFAULT_DEPTH,
                order = obj.int("order") ?: obj.int("insertion_order") ?: WorldInfoEntry.DEFAULT_ORDER,
                caseSensitive = obj.bool("caseSensitive") ?: obj.bool("case_sensitive"),
                matchWholeWords = obj.bool("matchWholeWords") ?: obj.bool("match_whole_words"),
                scanDepth = obj.int("scanDepth") ?: obj.int("scan_depth"),
                probability = obj.int("probability") ?: 100,
                useProbability = obj.bool("useProbability") ?: obj.bool("use_probability") ?: true,
                role = obj.int("role") ?: WorldInfoEntry.ROLE_SYSTEM,
                enabled = obj.bool("enabled") != false
            )
        }
        if (entries.isEmpty()) throw ImportException("这份世界书里没有可用条目")
        return Result(entries, skipped, name)
    }

    /** 找到条目数组与世界书名字；`entries` 既可能是对象（uid 为键）也可能是数组。 */
    private fun locateEntries(root: JsonElement): Pair<List<JsonElement>, String?>? {
        if (root is JsonArray) return root.toList() to null
        val obj = root as? JsonObject ?: return null
        val name = obj.str("name").ifBlank { null }
        val entries = obj["entries"] ?: return null
        return when (entries) {
            is JsonArray -> entries.toList() to name
            is JsonObject -> entries.values.toList() to name
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
}
