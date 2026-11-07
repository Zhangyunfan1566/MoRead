package com.mozhi.reader.ai.prompt

import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * 一条世界书条目。字段名与语义对齐 SillyTavern 的 `world_info` 条目，
 * 只保留本应用**真的会执行**的部分（sticky / cooldown / delay / recursion / group 权重
 * 这些依赖长时间会话状态或全局打分的字段没有搬，见 [WorldInfoEngine] 的说明）。
 *
 * @param position 0=角色定义之前 1=角色定义之后 2/3=作者注位置 4=按深度插入 5/6=EM 位置 7=outlet。
 *   本应用没有作者注与 outlet，2/3/5/6 按"之前"处理，7 不注入。
 * @param depth 仅 [position] = [POSITION_AT_DEPTH] 时使用，对应酒馆的"@D"。
 * @param role 仅按深度插入时使用：0=system 1=user 2=assistant（与酒馆 `extension_prompt_roles` 一致）。
 * @param caseSensitive / matchWholeWords / scanDepth 为 null 时用全局设置（与酒馆一致）。
 */
@Serializable
data class WorldInfoEntry(
    val id: String = "",
    val comment: String = "",
    val content: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    /** 0=AND_ANY 1=NOT_ALL 2=NOT_ANY 3=AND_ALL，对应酒馆 `selectiveLogic`。 */
    val selectiveLogic: Int = LOGIC_AND_ANY,
    /** 常驻条目：不看关键词，永远注入。 */
    val constant: Boolean = false,
    val disabled: Boolean = false,
    val position: Int = POSITION_BEFORE,
    val depth: Int = DEFAULT_DEPTH,
    /** 插入顺序：**数值大的先插入**（酒馆 `sortFn = (a,b) => b.order - a.order`）。 */
    val order: Int = DEFAULT_ORDER,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val scanDepth: Int? = null,
    val probability: Int = 100,
    val useProbability: Boolean = true,
    val role: Int = ROLE_SYSTEM,
    /** 本应用侧的开关（酒馆用 `disable`，两者任一为关就不注入）。 */
    val enabled: Boolean = true
) {
    companion object {
        const val LOGIC_AND_ANY = 0
        const val LOGIC_NOT_ALL = 1
        const val LOGIC_NOT_ANY = 2
        const val LOGIC_AND_ALL = 3

        const val POSITION_BEFORE = 0
        const val POSITION_AFTER = 1
        const val POSITION_AN_TOP = 2
        const val POSITION_AN_BOTTOM = 3
        const val POSITION_AT_DEPTH = 4
        const val POSITION_EM_TOP = 5
        const val POSITION_EM_BOTTOM = 6
        const val POSITION_OUTLET = 7

        const val ROLE_SYSTEM = 0
        const val ROLE_USER = 1
        const val ROLE_ASSISTANT = 2

        /** 酒馆 `DEFAULT_DEPTH`：按深度插入时的默认楼层。 */
        const val DEFAULT_DEPTH = 4

        /** 酒馆 `DEFAULT_WEIGHT`，同时也是默认 `order`。 */
        const val DEFAULT_ORDER = 100
    }
}

/**
 * 世界书引擎（纯逻辑，无 Android 依赖，可直接单测）。
 *
 * 语义对齐酒馆 `world-info.js::checkWorldInfo`：
 *
 * 1. **激活顺序**：`disabled` 跳过 → `constant` 直接激活 → 无主键跳过 →
 *    主键**任一命中** → 没有副键则激活 → 有副键则按 [WorldInfoEntry.selectiveLogic] 判定
 *    （AND_ANY 任一命中 / NOT_ALL 任一未命中 / NOT_ANY 全部未命中 / AND_ALL 全部命中）。
 * 2. **关键词匹配**（`matchKeys`）：形如 `/正则/旗标` 的键走正则，**此时忽略大小写与整词设置**；
 *    否则按 `caseSensitive` 决定是否忽略大小写，`matchWholeWords` 时单词键用
 *    `(?:^|\W)(key)(?:$|\W)` 边界匹配、多词键退化为包含匹配。
 * 3. **概率**：`useProbability` 为真且 `probability != 100` 时掷一次
 *    `random * 100 <= probability` 才算通过（与酒馆一致，含"等于"）。
 * 4. **顺序**：激活后按 `order` **降序**排列。
 * 5. **预算**：按字符累计，超上限后**不再添加**后续条目（酒馆按 token，本应用没有 tokenizer，
 *    用字符数近似），并置 [Result.budgetStopped]。
 *
 * **没有搬的**（都在酒馆里依赖长时间会话状态或全局打分，搬过来只会得到半截行为）：
 * `sticky` / `cooldown` / `delay`（需要按消息计时的会话状态）、
 * `group` 权重打分（`useGroupScoring`）、递归扫描（`recursion`）、
 * `vectorized`（需要向量库）、`automationId` / `characterFilter`（本应用没有对应实体）。
 * 这些字段在导入时被忽略，但**不丢条目**。
 */
object WorldInfoEngine {

    /** 全局扫描设置；对应酒馆的 `world_info_depth` / `world_info_budget`。 */
    @Serializable
    data class Options(
        /** 扫最近几条消息（酒馆默认 2）。 */
        val scanDepth: Int = 2,
        /** 世界书总字符预算（酒馆是"上下文的 25%"；本应用没有 tokenizer，直接用字符数）。 */
        val maxChars: Int = 4_000,
        val caseSensitive: Boolean = false,
        val matchWholeWords: Boolean = false
    )

    /** 一次命中的记录，供界面提示与排查。 */
    data class Activation(
        val entry: WorldInfoEntry,
        val matchedKey: String = "",
        val secondaryKey: String = ""
    )

    data class Result(
        /** 插到角色定义之前。 */
        val before: List<WorldInfoEntry> = emptyList(),
        /** 插到角色定义之后。 */
        val after: List<WorldInfoEntry> = emptyList(),
        /** 按深度插入的条目（用各自的 [WorldInfoEntry.depth] 与 [WorldInfoEntry.role]）。 */
        val atDepth: List<WorldInfoEntry> = emptyList(),
        val activations: List<Activation> = emptyList(),
        val budgetStopped: Boolean = false,
        val notes: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = before.isEmpty() && after.isEmpty() && atDepth.isEmpty()
        val size: Int get() = before.size + after.size + atDepth.size
    }

    /**
     * 扫描并分组。
     *
     * @param recentMessages 最近的消息正文，**旧的在前**；每条条目只取自己 `scanDepth` 范围内的尾部。
     */
    fun scan(
        entries: List<WorldInfoEntry>,
        recentMessages: List<String>,
        options: Options = Options(),
        random: Random = Random.Default
    ): Result {
        val notes = mutableListOf<String>()
        val activated = mutableListOf<Activation>()
        val ignoredPositions = sortedSetOf<Int>()

        entries.forEach { entry ->
            if (entry.disabled || !entry.enabled) return@forEach
            if (entry.content.isBlank()) return@forEach
            val haystack = scanText(entry, recentMessages, options)
            val activation = matchEntry(entry, haystack, options) ?: return@forEach
            if (!passesProbability(entry, random)) return@forEach
            activated += activation
        }

        // 酒馆 sortFn：order 降序；同 order 保持输入顺序（Kotlin 的 sortedWith 是稳定排序）。
        val ordered = activated.sortedWith(compareByDescending { it.entry.order })

        val before = mutableListOf<WorldInfoEntry>()
        val after = mutableListOf<WorldInfoEntry>()
        val atDepth = mutableListOf<WorldInfoEntry>()
        var used = 0
        var stopped = false
        ordered.forEach { activation ->
            val entry = activation.entry
            // outlet 位置本应用没有对应锚点：**命中了才提示**（原来在匹配之前就记，
            // 没命中的 outlet 条目也会让界面多一句无意义的话）。
            if (entry.position == WorldInfoEntry.POSITION_OUTLET) {
                ignoredPositions += entry.position
                return@forEach
            }
            if (stopped) return@forEach
            if (used + entry.content.length > options.maxChars) {
                stopped = true
                return@forEach
            }
            used += entry.content.length
            when (entry.position) {
                WorldInfoEntry.POSITION_BEFORE -> before += entry
                WorldInfoEntry.POSITION_AFTER -> after += entry
                WorldInfoEntry.POSITION_AT_DEPTH -> atDepth += entry
                else -> {
                    // 作者注 / EM 位置：本应用没有对应锚点，按"之前"处理并提示一次。
                    ignoredPositions += entry.position
                    before += entry
                }
            }
        }

        if (ignoredPositions.isNotEmpty()) {
            notes += "这些插入位置本应用没有对应锚点，已按「角色定义之前」处理或跳过：" +
                ignoredPositions.joinToString("、")
        }
        if (stopped) notes += "世界书字符预算（" + options.maxChars + "）已用尽，后面的条目没有再注入。"
        return Result(before, after, atDepth, activated, stopped, notes)
    }

    /** 单条命中的脚本化入口，供测试与界面预览用。 */
    fun activationFor(entry: WorldInfoEntry, haystack: String, options: Options): Activation? =
        matchEntry(entry, haystack, options)

    private fun scanText(entry: WorldInfoEntry, recentMessages: List<String>, options: Options): String {
        val depth = (entry.scanDepth ?: options.scanDepth).coerceAtLeast(0)
        if (depth == 0) return ""
        val tail = recentMessages.takeLast(depth)
        return tail.joinToString("\n")
    }

    private fun matchEntry(entry: WorldInfoEntry, haystack: String, options: Options): Activation? {
        if (entry.constant) return Activation(entry)
        if (entry.keys.isEmpty()) return null
        if (haystack.isEmpty()) return null

        val primary = entry.keys.firstOrNull { key -> matches(haystack, key, entry, options) } ?: return null
        if (entry.secondaryKeys.isEmpty()) return Activation(entry, matchedKey = primary)

        val hits = entry.secondaryKeys.map { it to matches(haystack, it, entry, options) }
        val anyMatch = hits.any { it.second }
        val allMatch = hits.all { it.second }
        val secondaryHit = hits.firstOrNull { it.second }?.first.orEmpty()

        val passed = when (entry.selectiveLogic) {
            WorldInfoEntry.LOGIC_AND_ANY -> anyMatch
            WorldInfoEntry.LOGIC_NOT_ALL -> hits.any { !it.second }
            WorldInfoEntry.LOGIC_NOT_ANY -> !anyMatch
            WorldInfoEntry.LOGIC_AND_ALL -> allMatch
            else -> anyMatch
        }
        if (!passed) return null
        return Activation(entry, matchedKey = primary, secondaryKey = secondaryHit)
    }

    private fun passesProbability(entry: WorldInfoEntry, random: Random): Boolean {
        if (!entry.useProbability || entry.probability >= 100) return true
        if (entry.probability <= 0) return false
        return random.nextDouble() * 100.0 <= entry.probability
    }

    /**
     * 关键词匹配。`/正则/旗标` 形式的键走正则并**忽略大小写与整词设置**（酒馆行为）；
     * 其余按设置做包含或整词匹配。
     */
    internal fun matches(haystack: String, key: String, entry: WorldInfoEntry, options: Options): Boolean {
        val needle = key.trim()
        if (needle.isEmpty()) return false
        if (needle.length >= 2 && needle.startsWith("/") && needle.lastIndexOf('/') > 0) {
            val pattern = TavernRegexEngine.compile(needle)
            if (pattern != null) return pattern.matcher(haystack).find()
            // 编不过就当普通文本，不要因为一条坏键把整条世界书废掉
        }
        // 走到这里说明不是可用的 /正则/ 形式（要么没闭合、要么编不过）。作者本意是正则但写坏了，
        // 退化成普通文本时必须把包裹的斜杠剥掉：正文里不会有那些斜杠，留着就永远匹配不到，
        // 一条坏键等于让整条世界书失效。
        val plain = if (needle.startsWith("/")) needle.drop(1).substringBeforeLast('/') else needle
        val caseSensitive = entry.caseSensitive ?: options.caseSensitive
        val wholeWords = entry.matchWholeWords ?: options.matchWholeWords
        val text = if (caseSensitive) haystack else haystack.lowercase()
        val target = if (caseSensitive) plain else plain.lowercase()
        if (!wholeWords) return text.contains(target)
        if (target.any(Char::isWhitespace)) return text.contains(target)
        val boundary = Regex("(?:^|\\W)" + Regex.escape(target) + "(?:$|\\W)")
        return boundary.containsMatchIn(text)
    }
}
