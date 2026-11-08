package com.mozhi.reader.ai.prompt

import com.mozhi.reader.core.datastore.GlobalPromptInjectionPosition
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.core.datastore.GlobalPromptPresetStore
import com.mozhi.reader.core.datastore.PromptRole
import com.mozhi.reader.core.datastore.TavernRequestSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 导入 SillyTavern（酒馆）的「Chat Completion 预设」JSON，转成本应用可用的全局预设。
 *
 * **保留酒馆语义**（这是与旧版最大的区别）：
 * - `role` 原样保留 → `assistant` 条目会以 assistant 身份插入，即**预填充**；
 * - `injection_position = 0`（relative）的 `injection_depth` **原样保留为 depth**，不再压平；
 * - `injection_position = 1`（absolute）按角色映射到锚点（system → 主提示词之后，
 *   user → 最近用户消息之前，assistant → 最近用户消息之后 = 预填充位）；
 * - `identifier` 原样记录；`prompt_order` 的先后与开关一并保留；
 * - **marker 判定以条目字段为准**：酒馆靠 prompt 对象上的 `marker` 布尔值决定是否注入，
 *   本应用原先只看 identifier 名单，会把用户**真的想注入**的条目（实测：一份真实预设用
 *   `enhanceDefinitions` 承载「🛡️破限[顶]」，字段 `marker = false`）静默丢掉。
 *   现在改为「字段优先，字段缺失时才回落到 identifier 名单」；
 * - **marker 条目不再丢弃**：以 `marker = true` 收进 [Result.markers]，可见、可手动改成可注入；
 * - 内容里出现不支持的宏（`getvar` / `roll` / `#if` …）时，在 [Result.unsupportedMacros] 里列出**提示**，
 *   不静默改写内容。
 *
 * 仍然保留一个"一键压平"开关（[flattenToLastUser]），把整份预设降级成旧行为。
 */
object SillyTavernPresetImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 酒馆预设里由宿主替换的占位条目。**只在条目自身没有 `marker` 字段时**才当作 marker——
     * 这是回落用的名单，不是权威判据（见类注释）。
     */
    private val MARKER_IDENTIFIERS = setOf(
        // 注意 `main` 与 `enhanceDefinitions` 列在这里只是"旧预设里通常是占位"的提示，
        // 真正判定还要叠加"内容为空"（见 import()）。它们的真实身份是**带内容的提示词**，
        // 无条件按 marker 丢弃会把用户的主提示词整段吞掉。
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
        val unsupportedMacros: List<String>,
        /**
         * `extensions.regex_scripts` 里的正则脚本。`enabled` 反映**预设自身**的开关
         * （`disabled == false`）；"这份预设要不要生效"由调用方再与一次。
         */
        val regexScripts: List<TavernRegexScript> = emptyList(),
        /** 因为 Java 正则不支持而跑不起来的脚本（只提示，不丢条目）。 */
        val unsupportedRegex: List<Skipped> = emptyList(),
        /**
         * 预设**顶层**的请求参数（采样 / 上下文）。
         * 只解析不启用——`applySampling` 一律为 false，必须由用户在设置页显式打开。
         */
        val sampling: TavernRequestSettings = TavernRequestSettings()
    ) {
        val isEmpty: Boolean get() = presets.isEmpty() && markers.isEmpty() && regexScripts.isEmpty()
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
            // 字段优先：显式 false 就是「要注入」，不再被 identifier 名单覆盖。
            // 名单只是**旧预设的回落**：酒馆里的 marker 条目本身就是空占位，
            // 所以只有「在名单里 且 内容为空」才按 marker 处理。否则像 `main` 这种
            // 名字在名单里、但内容是真正的系统提示词的条目会被整段丢弃（本预设就踩到）。
            val isMarker = entry.bool("marker")
                ?: (identifier in MARKER_IDENTIFIERS && content.isBlank())
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

        val regex = readRegexScripts(root, skipped)

        if (presets.isEmpty() && markers.isEmpty() && regex.first.isEmpty()) {
            throw ImportException("这份预设里没有可导入的条目")
        }
        return Result(
            presets = presets.sortedWith(compareBy({ it.order }, { it.name })),
            markers = markers,
            skipped = skipped,
            sourceName = root.str("name").ifBlank { null },
            splitNames = splitNames,
            unsupportedMacros = macros.toList(),
            regexScripts = regex.first,
            unsupportedRegex = regex.second,
            sampling = readSampling(root)
        )
    }

    /**
     * 顶层采样参数。**中性的值一律不搬**：`top_k = 0` / `min_p = 0` 在酒馆里表示"这个采样器关掉"，
     * 直接发出去反而有供应商会拒；`seed = -1` 表示随机，也不是一个能发出去的种子。
     * 其余原样保留（是否真的应用由用户开关决定）。
     */
    private fun readSampling(root: JsonObject): TavernRequestSettings = TavernRequestSettings(
        applySampling = false,
        temperature = root.float("temperature"),
        topP = root.float("top_p"),
        topK = root.int("top_k")?.takeIf { it > 0 },
        minP = root.float("min_p")?.takeIf { it > 0f },
        frequencyPenalty = root.float("frequency_penalty"),
        presencePenalty = root.float("presence_penalty"),
        repetitionPenalty = root.float("repetition_penalty"),
        maxTokens = root.int("openai_max_tokens")?.takeIf { it > 0 },
        seed = root.long("seed")?.takeIf { it >= 0 },
        reasoningEffort = root.str("reasoning_effort").ifBlank { null },
        maxContextTokens = root.int("openai_max_context")?.takeIf { it > 0 }
    )

    /**
     * 解析 `extensions.regex_scripts`。字段名与酒馆一致。
     *
     * **Java 正则编不过的脚本照常保留在结果里**（用户能看到、能改），只是同时列进
     * [Result.unsupportedRegex] 提示它当前不会生效——绝不静默丢弃。
     */
    private fun readRegexScripts(
        root: JsonObject,
        skipped: MutableList<Skipped>
    ): Pair<List<TavernRegexScript>, List<Skipped>> {
        val extensions = root["extensions"] as? JsonObject ?: return emptyList<TavernRegexScript>() to emptyList()
        val array = extensions["regex_scripts"] as? JsonArray ?: return emptyList<TavernRegexScript>() to emptyList()
        val scripts = mutableListOf<TavernRegexScript>()
        val unsupported = mutableListOf<Skipped>()
        array.forEachIndexed { index, element ->
            val entry = element as? JsonObject ?: return@forEachIndexed
            val find = entry.str("findRegex")
            val name = entry.str("scriptName").ifBlank { entry.str("name") }
                .ifBlank { "正则脚本 " + (index + 1) }
            if (find.isBlank()) {
                skipped += Skipped(name, "findRegex 为空")
                return@forEachIndexed
            }
            scripts += TavernRegexScript(
                id = entry.str("id").ifBlank { "tavern-regex-" + index },
                name = name,
                find = find,
                replacement = entry.str("replaceString"),
                trimStrings = entry.stringList("trimStrings"),
                placements = entry.intList("placement").ifEmpty { listOf(TavernRegexScript.PLACEMENT_AI_OUTPUT) },
                enabled = entry.bool("disabled") != true,
                markdownOnly = entry.bool("markdownOnly") == true,
                promptOnly = entry.bool("promptOnly") == true,
                minDepth = entry["minDepth"]?.jsonPrimitive?.intOrNull,
                maxDepth = entry["maxDepth"]?.jsonPrimitive?.intOrNull
            )
            TavernRegexEngine.unsupportedReason(find)?.let { unsupported += Skipped(name, it) }
        }
        return scripts to unsupported
    }

    /**
     * 酒馆的 `role` → 本应用角色。缺省 system。
     *
     * `model` 也映射到 assistant：部分预设（含实测那份）用 `model` 表示模型侧发言，
     * 语义等同 assistant；落到 else 会变成 system，把预填充变成一条系统提示，行为完全不同。
     */
    private fun JsonObject.role(): PromptRole = when (str("role").lowercase()) {
        "user" -> PromptRole.USER
        "assistant", "model" -> PromptRole.ASSISTANT
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

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.intList(key: String): List<Int> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private val JsonElement.jsonPrimitive: JsonPrimitive? get() = this as? JsonPrimitive
}
