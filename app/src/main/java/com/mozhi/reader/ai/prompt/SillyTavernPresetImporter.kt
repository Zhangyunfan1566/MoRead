package com.mozhi.reader.ai.prompt

import com.mozhi.reader.core.datastore.GlobalPromptInjectionPosition
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.core.datastore.GlobalPromptPresetStore
import com.mozhi.reader.core.datastore.PromptRole
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
 * **保留酒馆语义**（这是与旧版最大的区别）：
 * - `role` 原样保留 → `assistant` 条目会以 assistant 身份插入，即**预填充**；
 * - `injection_position = 0`（relative）的 `injection_depth` **原样保留为 depth**，不再压平；
 * - `injection_position = 1`（absolute）按角色映射到锚点（system → 主提示词之后，
 *   user → 最近用户消息之前，assistant → 最近用户消息之后 = 预填充位）；
 * - `identifier` 原样记录；`prompt_order` 的先后与开关一并保留；
 * - **marker 条目不再丢弃**：以 `marker = true` 收进 [Result.markers]，可见、可手动改成可注入；
 * - 内容里出现不支持的宏（`getvar` / `roll` / `#if` …）时，在 [Result.unsupportedMacros] 里列出**提示**，
 *   不静默改写内容。
 *
 * 仍然保留一个"一键压平"开关（[flattenToLastUser]），把整份预设降级成旧行为。
 */
object SillyTavernPresetImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 酒馆预设里由宿主替换的占位条目；导入后标 `marker = true`，保留但不注入。 */
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
        /** 可注入的条目（已按 prompt_order 排好）。 */
        val presets: List<GlobalPromptPreset>,
        /** marker 条目：保留在列表里但不注入。 */
        val markers: List<GlobalPromptPreset>,
        val skipped: List<Skipped>,
        val sourceName: String?,
        /** 因为超长被拆成多段的条目名（原始名）。 */
        val splitNames: List<String>,
        /** 内容里出现的、本应用**不会执行**的宏名（只提示，不改内容）。 */
        val unsupportedMacros: List<String>
    ) {
        val isEmpty: Boolean get() = presets.isEmpty() && markers.isEmpty()
    }

    class ImportException(message: String) : IllegalArgumentException(message)

    /**
     * @param text 酒馆导出的预设 JSON 原文
     * @param enabledByDefault 导入后是否默认启用（默认 false，避免一次导入就改变行为）
     * @param flattenToLastUser 旧行为：忽略 role 与 depth，全部压到「最近用户消息之后」并作为 system 文本
     */
    fun import(
        text: String,
        enabledByDefault: Boolean = false,
        flattenToLastUser: Boolean = false
    ): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw ImportException("文件是空的")
        val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }
            .getOrNull() ?: throw ImportException("不是合法的 JSON")
        val promptArray = root["prompts"] as? JsonArray
            ?: throw ImportException("没有找到 prompts 数组，这看起来不是酒馆的对话补全预设")

        val orderEnabled = readOrder(root)
        val presets = mutableListOf<GlobalPromptPreset>()
        val markers = mutableListOf<GlobalPromptPreset>()
        val skipped = mutableListOf<Skipped>()
        val splitNames = mutableListOf<String>()
        val macros = linkedSetOf<String>()

        promptArray.forEachIndexed { index, element ->
            val entry = element as? JsonObject ?: return@forEachIndexed
            val identifier = entry.str("identifier")
            val name = entry.str("name").ifBlank { identifier.ifBlank { "条目 ${index + 1}" } }
            val content = entry.str("content")
            val isMarker = entry.bool("marker") == true || identifier in MARKER_IDENTIFIERS
            val order = orderEnabled[identifier]?.first ?: index
            val enabled = orderEnabled[identifier]?.second ?: entry.bool("enabled") ?: true

            if (isMarker) {
                markers += GlobalPromptPreset(
                    id = "tavern-marker-${identifier.ifBlank { "entry$index" }}",
                    name = name.take(80),
                    prompt = content,
                    enabled = false,
                    position = GlobalPromptInjectionPosition.AFTER_SYSTEM,
                    role = PromptRole.SYSTEM,
                    identifier = identifier,
                    order = order,
                    marker = true
                )
                return@forEachIndexed
            }
            if (!enabled) {
                skipped += Skipped(name, "在酒馆预设里是关闭状态")
                return@forEachIndexed
            }
            if (content.isBlank()) {
                skipped += Skipped(name, "内容为空")
                return@forEachIndexed
            }

            macros += GlobalPromptInjector.unsupportedMacros(content)
            val role = entry.role()
            val depth = entry.depth()
            val position = entry.anchor(role)
            val parts = splitLong(content, name)
            if (parts.size > 1) splitNames += name
            parts.forEachIndexed { partIndex, part ->
                val partName = if (parts.size == 1) name else "$name (${partIndex + 1}/${parts.size})"
                presets += if (flattenToLastUser) {
                    GlobalPromptPreset(
                        id = "tavern-${identifier.ifBlank { "entry$index" }}-$partIndex",
                        name = partName.take(80),
                        prompt = part,
                        enabled = enabledByDefault,
                        position = GlobalPromptInjectionPosition.AFTER_LAST_USER,
                        role = PromptRole.SYSTEM,
                        depth = -1,
                        identifier = identifier,
                        order = order,
                        marker = false
                    )
                } else {
                    GlobalPromptPreset(
                        id = "tavern-${identifier.ifBlank { "entry$index" }}-$partIndex",
                        name = partName.take(80),
                        prompt = part,
                        enabled = enabledByDefault,
                        position = position,
                        role = role,
                        depth = depth,
                        identifier = identifier,
                        order = order,
                        marker = false
                    )
                }
            }
        }

        if (presets.isEmpty() && markers.isEmpty()) {
            throw ImportException("这份预设里没有可导入的条目")
        }
        return Result(
            presets = presets.sortedWith(compareBy({ it.order }, { it.name })),
            markers = markers,
            skipped = skipped,
            sourceName = root.str("name").ifBlank { null },
            splitNames = splitNames,
            unsupportedMacros = macros.toList()
        )
    }

    /** 酒馆的 `role` → 本应用角色。缺省 system。 */
    private fun JsonObject.role(): PromptRole = when (str("role").lowercase()) {
        "user" -> PromptRole.USER
        "assistant" -> PromptRole.ASSISTANT
        else -> PromptRole.SYSTEM
    }

    /**
     * 相对深度：`injection_position = 0` 时取 `injection_depth`（**原样保留**，不再压平）；
     * 绝对位置（=1）或缺省时返回 -1，表示"用锚点"。
     */
    private fun JsonObject.depth(): Int {
        val absolute = (this["injection_position"]?.jsonPrimitive?.intOrNull ?: 0) == 1
        if (absolute) return -1
        return (this["injection_depth"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 200)
    }

    /** 绝对锚点：按角色挑最贴近酒馆语义的落点（depth >= 0 时此项不起作用）。 */
    private fun JsonObject.anchor(role: PromptRole): GlobalPromptInjectionPosition = when (role) {
        PromptRole.ASSISTANT -> GlobalPromptInjectionPosition.AFTER_LAST_USER
        PromptRole.USER -> GlobalPromptInjectionPosition.BEFORE_LAST_USER
        PromptRole.SYSTEM -> GlobalPromptInjectionPosition.AFTER_SYSTEM
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

    /** 酒馆把启用状态与顺序存在 prompt_order 里；返回 identifier → (顺序, 是否启用)。 */
    private fun readOrder(root: JsonObject): Map<String, Pair<Int, Boolean>> {
        val order = root["prompt_order"] as? JsonArray ?: return emptyMap()
        val first = order.firstOrNull() as? JsonObject ?: return emptyMap()
        val entries = first["order"] as? JsonArray ?: return emptyMap()
        return buildMap {
            entries.forEachIndexed { index, item ->
                val obj = item as? JsonObject ?: return@forEachIndexed
                val id = obj.str("identifier")
                if (id.isBlank()) return@forEachIndexed
                put(id, index to (obj.bool("enabled") ?: true))
            }
        }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private val JsonElement.jsonPrimitive: JsonPrimitive? get() = this as? JsonPrimitive
}
