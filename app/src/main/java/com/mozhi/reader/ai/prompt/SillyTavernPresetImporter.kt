package com.mozhi.reader.ai.prompt

import com.mozhi.reader.core.datastore.GlobalPromptInjectionPosition
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 导入 SillyTavern（酒馆）的「Chat Completion 预设」JSON，转成本应用可用的全局预设。
 *
 * 设计取舍（与酒馆语义的差异，必须写清楚，避免用户以为能 1:1 还原）：
 * 1. 酒馆的注入位置有两类：`injection_position = 1`（absolute，相对主提示词/聊天历史的绝对位）
 *    与 `injection_position = 0`（relative，按 `injection_depth` 从对话末尾往上数）。
 *    本应用只有四个位置，没有"按对话深度插入"的能力，因此做近似映射（见 [mapPosition]）。
 * 2. 酒馆预设里的 **marker 条目**（`charDescription`、`chatHistory`、`worldInfoBefore` 等）是占位标记，
 *    由酒馆在组装时替换成角色卡/世界书内容。本应用没有 marker 系统，这类条目一律跳过并在结果里列明。
 * 3. 单条预设上限 [GlobalPromptPresetStore.MAX_PROMPT_CHARS]，超长内容会被切成多段（保持顺序），
 *    而不是丢弃。
 */
object SillyTavernPresetImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 酒馆预设里由程序替换、不属于用户文本的占位条目，导入时跳过。 */
    private val MARKER_IDENTIFIERS = setOf(
        "main",
        "worldInfoBefore",
        "worldInfoAfter",
        "charDescription",
        "charPersonality",
        "scenario",
        "personaDescription",
        "chatHistory",
        "dialogueExamples",
        "enhanceDefinitions",
        "groupNudgePrompt",
        "newChat",
        "newGroupChat",
        "newExampleChat",
        "postHistoryInstructionsPlaceholder",
        "personaDescriptionPlaceholder"
    )

    data class Skipped(val name: String, val reason: String)

    data class Result(
        val presets: List<GlobalPromptPreset>,
        val skipped: List<Skipped>,
        val sourceName: String?,
        /** 因为超长被拆成多段的条目名（原始名） */
        val splitNames: List<String>
    ) {
        val isEmpty: Boolean get() = presets.isEmpty()
    }

    class ImportException(message: String) : IllegalArgumentException(message)

    /**
     * @param text 酒馆导出的预设 JSON 原文
     * @param enabledByDefault 导入后是否默认启用（默认 false，避免一次导入就改变行为）
     * @param overridePosition 指定后所有条目都用这个位置（用于"全部压到最近用户消息之后"这种破限向的用法）
     */
    fun import(
        text: String,
        enabledByDefault: Boolean = false,
        overridePosition: GlobalPromptInjectionPosition? = null
    ): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw ImportException("文件是空的")
        val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }
            .getOrNull() ?: throw ImportException("不是合法的 JSON")
        val promptArray = root["prompts"] as? JsonArray
            ?: throw ImportException("没有找到 prompts 数组，这看起来不是酒馆的对话补全预设")

        val orderEnabled = readOrderEnabled(root)
        val presets = mutableListOf<GlobalPromptPreset>()
        val skipped = mutableListOf<Skipped>()
        val splitNames = mutableListOf<String>()

        promptArray.forEachIndexed { index, element ->
            val entry = element as? JsonObject ?: return@forEachIndexed
            val identifier = entry.str("identifier")
            val name = entry.str("name").ifBlank { identifier.ifBlank { "条目 ${index + 1}" } }
            val content = entry.str("content")
            val isMarker = entry.bool("marker") == true || identifier in MARKER_IDENTIFIERS
            val enabled = orderEnabled[identifier] ?: entry.bool("enabled") ?: true

            when {
                isMarker -> skipped += Skipped(name, "酒馆占位条目（marker），本应用没有对应机制")
                !enabled -> skipped += Skipped(name, "在酒馆预设里是关闭状态")
                content.isBlank() -> skipped += Skipped(name, "内容为空")
                else -> {
                    val position = overridePosition ?: mapPosition(entry, identifier)
                    val parts = splitLong(content, name)
                    if (parts.size > 1) splitNames += name
                    parts.forEachIndexed { partIndex, part ->
                        val partName = if (parts.size == 1) name else "$name (${partIndex + 1}/${parts.size})"
                        presets += GlobalPromptPreset(
                            id = "tavern-${identifier.ifBlank { "entry$index" }}-${partIndex}",
                            name = partName.take(80),
                            prompt = part,
                            enabled = enabledByDefault,
                            position = position,
                            builtIn = false
                        )
                    }
                }
            }
        }

        if (presets.isEmpty()) {
            throw ImportException("这份预设里没有可导入的文本条目（可能全是 marker 或全部关闭）")
        }
        return Result(
            presets = presets,
            skipped = skipped,
            sourceName = root.str("name").ifBlank { null },
            splitNames = splitNames
        )
    }

    /**
     * 位置近似映射：
     * - `injection_position = 1`（absolute）+ role=system → 主系统提示词之后
     * - `injection_position = 1`（absolute）+ role=user/assistant → 最近用户消息之前
     * - `injection_position = 0`（relative）+ depth <= 0 → 最近用户消息之后（最贴近生成点）
     * - `injection_position = 0`（relative）+ depth >= 1 → 最近用户消息之前
     */
    internal fun mapPosition(entry: JsonObject, identifier: String): GlobalPromptInjectionPosition {
        val absolute = (entry["injection_position"]?.jsonPrimitive?.intOrNull ?: 0) == 1
        val depth = entry["injection_depth"]?.jsonPrimitive?.intOrNull ?: 0
        val role = entry.str("role").lowercase()
        return if (absolute) {
            if (role == "user" || role == "assistant") {
                GlobalPromptInjectionPosition.BEFORE_LAST_USER
            } else {
                GlobalPromptInjectionPosition.AFTER_SYSTEM
            }
        } else {
            if (depth <= 0) GlobalPromptInjectionPosition.AFTER_LAST_USER
            else GlobalPromptInjectionPosition.BEFORE_LAST_USER
        }
    }

    /** 超长内容按上限切段，尽量切在空行或句末，避免把一句话劈开。 */
    internal fun splitLong(content: String, name: String): List<String> {
        val max = GlobalPromptPresetStore.MAX_PROMPT_CHARS
        if (content.length <= max) return listOf(content)
        val parts = mutableListOf<String>()
        var rest = content
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = listOf(
                window.lastIndexOf("\n\n"),
                window.lastIndexOf('\n'),
                window.lastIndexOf('。'),
                window.lastIndexOf('；'),
                window.lastIndexOf(' ')
            ).max()
            val end = if (cut > max / 2) cut + 1 else max
            parts += rest.substring(0, end).trim()
            rest = rest.substring(end)
        }
        if (rest.isNotBlank()) parts += rest.trim()
        return parts
    }

    /** 酒馆把启用状态也存在 prompt_order 里；优先用它（按第一个 character_id）。 */
    private fun readOrderEnabled(root: JsonObject): Map<String, Boolean> {
        val order = root["prompt_order"] as? JsonArray ?: return emptyMap()
        val first = order.firstOrNull() as? JsonObject ?: return emptyMap()
        val entries = first["order"] as? JsonArray ?: return emptyMap()
        return buildMap {
            entries.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val id = obj.str("identifier")
                val enabled = obj.bool("enabled")
                if (id.isNotBlank() && enabled != null) put(id, enabled)
            }
        }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private val JsonElement.jsonPrimitive: JsonPrimitive? get() = this as? JsonPrimitive
}
