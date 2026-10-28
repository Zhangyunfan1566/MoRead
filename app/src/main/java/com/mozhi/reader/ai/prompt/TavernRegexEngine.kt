package com.mozhi.reader.ai.prompt

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlinx.serialization.Serializable

/**
 * 一条酒馆正则脚本。字段名与语义对齐 SillyTavern 的 `regex_scripts` 条目，
 * 但只保留本应用**真的会执行**的部分（弃用字段不搬）。
 *
 * @param placements 酒馆的 `placement` 数值数组（1=用户输入 2=AI 输出 5=世界书 6=思维链）。
 *   存原始数值而不是枚举，避免遇到没见过的放置位时丢信息。
 * @param markdownOnly / promptOnly 酒馆用它决定脚本作用在哪个阶段，见 [TavernRegexEngine.shouldApply]。
 * @param minDepth / maxDepth 只作用于距对话末尾这个深度区间的消息（null = 不限）。
 */
@Serializable
data class TavernRegexScript(
    val id: String,
    val name: String,
    /** 原始 `findRegex`，可能是 `/pattern/flags` 形式，也可能是裸 pattern。 */
    val find: String,
    val replacement: String = "",
    val trimStrings: List<String> = emptyList(),
    val placements: List<Int> = listOf(PLACEMENT_AI_OUTPUT),
    /** 本应用侧的开关；导入时取"这份预设是否启用"。 */
    val enabled: Boolean = false,
    val markdownOnly: Boolean = false,
    val promptOnly: Boolean = false,
    val minDepth: Int? = null,
    val maxDepth: Int? = null
) {
    companion object {
        const val PLACEMENT_USER_INPUT = 1
        const val PLACEMENT_AI_OUTPUT = 2
        const val PLACEMENT_WORLD_INFO = 5
        const val PLACEMENT_REASONING = 6
    }
}

/**
 * 酒馆正则脚本引擎（纯逻辑，无 Android 依赖，可直接单测）。
 *
 * ### 为什么需要它
 * 用户实测在用的那份预设带 **11 条正则脚本**，其中 8 条启用。它们不是装饰：
 * 「八股抹除」「破折号处理」「比喻正则」负责清掉模型自己的口头禅与八股，
 * 「切除 10 楼以内小总结 / 10 楼以上正文」靠 **深度区间**做长对话裁剪。
 * 没有这一层，输出质量的差距有一大截出在这里。
 *
 * ### 与酒馆的两处刻意差异（都是因为本应用的执行时机不同）
 *
 * 1. **作用时机**：酒馆把脚本分成"改存储正文"和"改提示词"两趟；本应用只在
 *    **构造请求时**跑一趟（改的是请求副本，不动用户存档）。为了拿到与酒馆**相同的净效果**，
 *    这里对 `isPrompt = true, isMarkdown = false` 求值，见 [shouldApply]。
 * 2. **`markdownOnly` 的处置**：不能简单地"跳过 markdownOnly"——实测那份预设里
 *    7 条启用的脚本 `markdownOnly` 与 `promptOnly` **同时为 true**，在酒馆里两个阶段都会跑。
 *    真正该跳过的是 **"只给显示用"**（`markdownOnly && !promptOnly`）的脚本，例如
 *    把 `<SUOT>` 换成整段 HTML 的那条——它在提示词里毫无意义。
 *
 * ### JS → Java 正则兼容
 * `findRegex` 按酒馆 `regexFromString` 的规则解析：`/pattern/flags` 取 pattern + flags；
 * 旗标非法（含重复字符或集合外字符）时**整串当作 pattern**，与酒馆一致。
 * 旗标映射：`i`→忽略大小写、`m`→多行、`s`→dotAll、`x`→注释、`u`→Unicode、
 * `g`/`y`→忽略（Java 的替换本就是全局）。未知旗标 → 整串当 pattern。
 *
 * 替换串支持：`{{match}}` / `$&` / `$0`（整个匹配）、`$1`…`$99`、`$<name>`、`${name}`、
 * `$$`（字面美元符）、`$\``` 与 `$'`（匹配前 / 后的文本）。**每个被替换进去的捕获组都会先过
 * `trimStrings`**——这是酒馆 `runRegexScript` 的行为，不是把 trimStrings 作用在整段结果上。
 *
 * 单条脚本出错（正则编不过）只跳过这一条，不影响其它脚本，也不会让整轮请求失败。
 */
object TavernRegexEngine {

    /** 单个脚本文本上限：超长正文不做正则，避免病态回溯把一轮请求拖死。 */
    private const val MAX_TEXT_CHARS = 200_000

    /** 酒馆允许的旗标集合（重复或集合外字符会让它退回"整串当 pattern"）。 */
    private val ALLOWED_FLAGS = "gmixXsuUAJ".toSet()

    /**
     * 该不该在**构造提示词**时执行这条脚本。
     *
     * 复刻酒馆 `getRegexedString` 在 `isPrompt = true, isMarkdown = false` 下的三选一判定，
     * 再补上本应用缺的那一趟（酒馆会先把脚本作用到存储正文，本应用没有这一步）：
     * - `promptOnly` → 执行（酒馆第二分支）
     * - 两者都 false → 执行（酒馆第三分支在提示词侧为假，但它的净效果来自"存储正文那一趟"，这里补上）
     * - `markdownOnly && !promptOnly` → **不执行**（纯显示用途）
     */
    fun shouldApply(script: TavernRegexScript): Boolean = !(script.markdownOnly && !script.promptOnly)

    /** 深度窗口：`depth` 为 null（不知道该消息的深度）时不按深度过滤。 */
    fun depthAllowed(script: TavernRegexScript, depth: Int?): Boolean {
        if (depth == null) return true
        script.minDepth?.let { if (it >= -1 && depth < it) return false }
        script.maxDepth?.let { if (it >= 0 && depth > it) return false }
        return true
    }

    /** 对一条脚本做替换；不满足条件或正则非法时原样返回。 */
    fun apply(text: String, script: TavernRegexScript, depth: Int? = null): String {
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) return text
        if (!script.enabled || !shouldApply(script)) return text
        if (!depthAllowed(script, depth)) return text
        val pattern = compile(script.find) ?: return text
        val matcher = pattern.matcher(text)
        if (!matcher.find()) return text
        val out = StringBuilder(text.length + 16)
        var last = 0
        do {
            out.append(text, last, matcher.start())
            out.append(replacement(script, matcher, text))
            last = matcher.end()
            // Java 的 find() 在零宽匹配后会自动前进一格，这里不需要额外处理。
        } while (matcher.find())
        out.append(text, last, text.length)
        return out.toString()
    }

    /**
     * 按放置位依次执行多条脚本。**顺序即预设给出的顺序**——脚本之间会叠加，
     * 「先抹除八股、再切小总结」和反过来的结果不一样。
     */
    fun applyAll(
        text: String,
        scripts: List<TavernRegexScript>,
        placement: Int,
        depth: Int? = null
    ): String {
        if (text.isEmpty()) return text
        var current = text
        scripts.forEach { script ->
            if (placement in script.placements) current = apply(current, script, depth)
        }
        return current
    }

    /** 该放置位下会真正跑起来的脚本条数（给界面提示用）。 */
    fun activeCount(scripts: List<TavernRegexScript>, placement: Int): Int =
        scripts.count { it.enabled && shouldApply(it) && placement in it.placements }

    /**
     * 解析并编译 `findRegex`。返回 null 表示"这条脚本在 Java 正则下不可用"，
     * 调用方应跳过它并在导入预览里提示，而不是让整轮请求失败。
     */
    fun compile(find: String): Pattern? {
        val parsed = parse(find) ?: return null
        return try {
            Pattern.compile(parsed.first, parsed.second)
        } catch (_: PatternSyntaxException) {
            null
        }
    }

    /** 给导入预览用：这条脚本为什么跑不起来（能跑返回 null）。 */
    fun unsupportedReason(find: String): String? {
        val parsed = parse(find) ?: return "findRegex 为空"
        return try {
            Pattern.compile(parsed.first, parsed.second)
            null
        } catch (error: PatternSyntaxException) {
            "Java 正则不支持：" + (error.description ?: "语法错误")
        }
    }

    /**
     * 复刻酒馆 `utils.js::regexFromString`：
     * `/(\/?)(.+)\1([a-z]*)/i` 取第一处匹配，旗标非法时退回"整串当 pattern"。
     * 返回 (pattern, javaOptions)。
     */
    internal fun parse(find: String): Pair<String, Int>? {
        val raw = find
        if (raw.isEmpty()) return null
        val match = Regex("^(\\/?)(.+)\\1([a-zA-Z]*)$", RegexOption.DOT_MATCHES_ALL).find(raw)
        if (match != null) {
            val flags = match.groupValues[3]
            if (flags.isEmpty()) return match.groupValues[2] to 0
            if (flags.toSet().size == flags.length && flags.all { it in ALLOWED_FLAGS }) {
                return match.groupValues[2] to optionsFor(flags)
            }
        }
        return raw to 0
    }

    private fun optionsFor(flags: String): Int {
        var options = 0
        flags.forEach { flag ->
            when (flag.lowercaseChar()) {
                'i' -> options = options or Pattern.CASE_INSENSITIVE
                'm' -> options = options or Pattern.MULTILINE
                's' -> options = options or Pattern.DOTALL
                'x' -> options = options or Pattern.COMMENTS
                'u' -> options = options or Pattern.UNICODE_CASE
                // g / y：Java 的替换本来就是全局；A / J 是酒馆的历史旗标，忽略。
                else -> Unit
            }
        }
        return options
    }

    // ---------- 替换串求值 ----------

    private fun replacement(script: TavernRegexScript, matcher: Matcher, text: String): String {
        val template = script.replacement.replace(
            Regex("\\{\\{match}}", RegexOption.IGNORE_CASE),
            "\$0"
        )
        val out = StringBuilder(template.length + 16)
        var index = 0
        while (index < template.length) {
            val char = template[index]
            if (char != '$' || index + 1 >= template.length) {
                out.append(char)
                index += 1
                continue
            }
            when (val next = template[index + 1]) {
                '$' -> { out.append('$'); index += 2 }
                '&' -> { out.append(filter(script, matcher.group(0))); index += 2 }
                '`' -> { out.append(filter(script, text.substring(0, matcher.start()))); index += 2 }
                '\'' -> { out.append(filter(script, text.substring(matcher.end()))); index += 2 }
                '<' -> {
                    val close = template.indexOf('>', index + 2)
                    if (close < 0) { out.append(char); index += 1 } else {
                        out.append(filter(script, namedGroup(matcher, template.substring(index + 2, close))))
                        index = close + 1
                    }
                }
                '{' -> {
                    val close = template.indexOf('}', index + 2)
                    if (close < 0) { out.append(char); index += 1 } else {
                        out.append(filter(script, namedGroup(matcher, template.substring(index + 2, close))))
                        index = close + 1
                    }
                }
                else -> {
                    if (!next.isDigit()) { out.append(char); index += 1 } else {
                        var end = index + 1
                        while (end < template.length && template[end].isDigit() && end - index <= 2) end += 1
                        val groupIndex = template.substring(index + 1, end).toIntOrNull()
                        val value = if (groupIndex == null || groupIndex > matcher.groupCount()) "" else matcher.group(groupIndex)
                        out.append(filter(script, value))
                        index = end
                    }
                }
            }
        }
        return out.toString()
    }

    /** 捕获组取值后再去 trimStrings——酒馆就是这么做的。 */
    private fun filter(script: TavernRegexScript, value: String?): String {
        var result = value.orEmpty()
        script.trimStrings.forEach { trim -> if (trim.isNotEmpty()) result = result.replace(trim, "") }
        return result
    }

    /** Java 的 `Matcher.group(name)` 对不存在的组会抛异常，这里吞掉当空串。 */
    private fun namedGroup(matcher: Matcher, name: String): String =
        runCatching { matcher.group(name) }.getOrNull().orEmpty()
}
