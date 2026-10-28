package com.mozhi.reader.ai.prompt

import kotlin.random.Random

/**
 * 一次请求内的酒馆变量作用域（对应 SillyTavern 的 local/global variable）。
 *
 * **为什么需要它**：实测一份真实酒馆预设（144 条、31 条启用）里有 62 处宏，其中
 * `{{setvar::…}}` 与 `{{getvar::JailbreakPrompt}}` 是**破限正文的载体**——
 * 不执行变量宏，模型看到的就是一串字面量 `{{getvar::...}}`，破限等于没生效。
 *
 * 作用域语义（v1 简化）：全部变量共用一个命名空间，注入时按 [com.mozhi.reader.core.datastore.GlobalPromptPreset.order]
 * 顺序执行，因此**同一轮请求内靠前的 `setvar` 对靠后的 `getvar` 可见**——这正是预设作者依赖的行为。
 * 跨轮持久化由 [com.mozhi.reader.core.datastore.TavernVariableStore] 负责。
 */
class TavernVariables(initial: Map<String, String> = emptyMap()) {

    private val values: MutableMap<String, String> = LinkedHashMap(initial)

    /** 本轮是否发生过写入；调用方据此决定要不要落库。 */
    var dirty: Boolean = false
        private set

    fun get(name: String): String? = values[name.trim()]

    fun set(name: String, value: String) {
        val key = name.trim()
        if (key.isEmpty()) return
        if (values[key] != value) dirty = true
        values[key] = value
    }

    fun add(name: String, delta: Double) {
        val current = values[name.trim()]?.trim()?.toDoubleOrNull() ?: 0.0
        set(name, formatNumber(current + delta))
    }

    fun inc(name: String) = add(name, 1.0)

    fun dec(name: String) = add(name, -1.0)

    fun snapshot(): Map<String, String> = LinkedHashMap(values)

    /** 数字保持整数外观（`3.0` → `3`），避免 `{{addvar}}` 之后提示词里出现小数点噪音。 */
    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}

/**
 * 酒馆宏引擎（纯 Kotlin，无 Android 依赖，可直接单测）。
 *
 * 覆盖实测预设里出现的全部宏，外加常用扩展：
 * - 身份：`{{char}}` `{{user}}` `{{persona}}`
 * - 消息：`{{lastusermessage}}` `{{lastcharmessage}}`
 * - 变量：`{{getvar::k}}` `{{setvar::k::v}}` `{{addvar::k::n}}` `{{incvar::k}}` `{{decvar::k}}`
 * - 随机：`{{roll:1d6}}` / `{{roll 1d6}}` `{{random::a::b::c}}` `{{pick::a::b}}`
 * - 杂项：`{{trim}}` `{{newline}}` `{{noop}}` `{{// 注释}}`
 * - 条件：`{{#if k}}`…`{{else}}`…`{{/if}}`（支持嵌套、`!` 取反、`==`/`!=` 比较）
 *
 * **未识别的宏原样保留**——宁可让用户看见 `{{weirdmacro}}`，也不要静默删掉他预设里的内容；
 * 由 [unknownMacros] 列出来提示。
 *
 * @param variables 为 null 时**不执行**变量 / 随机 / `{{trim}}` 宏（它们原样留在文本里）。
 *   这是刻意的：没有作用域就没有副作用，纯函数场景（例如只做展示预览）保持可预期。
 */
object TavernMacros {

    /** `{{trim}}` 的占位结果：本函数不直接裁剪，由 [render] 在整段替换完成后统一 trim。 */
    private const val TRIM_MARKER = "\u0000TAVERN_TRIM\u0000"

    /** 注释宏的归一化名字；它不算「未知宏」。 */
    private const val COMMENT = "//"

    /** 引擎认识的宏名（不含参数）；用于 [unknownMacros] 判定。 */
    private val KNOWN = setOf(
        "char", "user", "persona",
        "lastusermessage", "lastcharmessage",
        "getvar", "setvar", "addvar", "incvar", "decvar",
        "roll", "random", "pick",
        "trim", "newline", "noop",
        "if", "else"
    )

    /** 展开文本。未知宏原样保留。 */
    fun render(text: String, context: MacroContext?, variables: TavernVariables?): String {
        if (!text.contains("{{")) return text
        val conditioned = if (variables != null) resolveConditionals(text, context, variables) else text
        val expanded = expandFlat(conditioned, context, variables)
        return if (expanded.contains(TRIM_MARKER)) expanded.replace(TRIM_MARKER, "").trim() else expanded
    }

    /** 列出文本里引擎**不执行**的宏名（不改内容，只用于导入预览与排查）。 */
    fun unknownMacros(text: String): List<String> = Regex("\\{\\{([^{}]+)}}")
        .findAll(text)
        .map { match -> macroName(match.groupValues[1]) }
        .filter { it.isNotBlank() && it != COMMENT && it.lowercase() !in KNOWN }
        .distinct()
        .toList()

    /** 文本里引用了、但当前作用域里**没有值**的变量名（`{{getvar::x}}` 的 x）。 */
    fun unresolvedVariables(text: String, variables: TavernVariables?): List<String> {
        if (variables == null) return emptyList()
        return Regex("\\{\\{getvar::([^{}]+)}}")
            .findAll(text)
            .map { it.groupValues[1].trim().substringBefore("::") }
            .filter { it.isNotBlank() && variables.get(it) == null }
            .distinct()
            .toList()
    }

    /** `{{roll 1d20}}` / `{{#if x}}` / `{{// 注释}}` 都归一成裸宏名（小写）。 */
    internal fun macroName(body: String): String {
        val raw = body.trim()
        if (raw.startsWith("//")) return COMMENT
        return raw.removePrefix("#").removePrefix("^").removePrefix("/").trim()
            .substringBefore("::")
            .substringBefore(' ')
            .substringBefore(':')
            .trim()
            .lowercase()
    }

    // ---------- 条件块 ----------

    private sealed interface Node
    private class Literal(val text: String) : Node
    private class Conditional(val condition: String, val then: List<Node>, val otherwise: List<Node>) : Node

    private fun resolveConditionals(text: String, context: MacroContext?, variables: TavernVariables): String {
        if (!text.contains("{{#if")) return text
        val (nodes, _) = parse(text, 0)
        return renderNodes(nodes, context, variables)
    }

    /** 返回 (节点列表, 下一个未消费的下标)；遇到 `{{else}}` / `{{/if}}` 就停下交给上层。 */
    private fun parse(text: String, start: Int): Pair<List<Node>, Int> {
        val nodes = mutableListOf<Node>()
        var index = start
        while (index < text.length) {
            val open = text.indexOf("{{", index)
            if (open < 0) {
                nodes += Literal(text.substring(index))
                return nodes to text.length
            }
            if (open > index) nodes += Literal(text.substring(index, open))
            val close = text.indexOf("}}", open + 2)
            if (close < 0) {
                nodes += Literal(text.substring(open))
                return nodes to text.length
            }
            val body = text.substring(open + 2, close).trim()
            index = close + 2
            when {
                body.startsWith("#if") -> {
                    val condition = body.removePrefix("#if").trim()
                    val (thenNodes, afterThen) = parse(text, index)
                    val marker = markerAt(text, afterThen)
                    if (marker.startsWith("{{else")) {
                        val (elseNodes, afterElse) = parse(text, afterThen + marker.length)
                        nodes += Conditional(condition, thenNodes, elseNodes)
                        index = skipCloseIf(text, afterElse)
                    } else {
                        nodes += Conditional(condition, thenNodes, emptyList())
                        index = skipCloseIf(text, afterThen)
                    }
                }
                body.startsWith("else") || body.startsWith("/if") -> return nodes to open
                else -> nodes += Literal(text.substring(open, close + 2))
            }
        }
        return nodes to text.length
    }

    /** 取 [index] 处的整条 `{{…}}`；不是宏开头就返回空串。 */
    private fun markerAt(text: String, index: Int): String {
        if (index < 0 || index + 2 > text.length || !text.startsWith("{{", index)) return ""
        val close = text.indexOf("}}", index + 2)
        return if (close < 0) "" else text.substring(index, close + 2)
    }

    private fun skipCloseIf(text: String, index: Int): Int {
        val marker = markerAt(text, index)
        return if (marker.isNotEmpty() && macroName(marker.substring(2, marker.length - 2)) == "if") index + marker.length
        else index
    }

    private fun renderNodes(nodes: List<Node>, context: MacroContext?, variables: TavernVariables): String =
        buildString {
            nodes.forEach { node ->
                when (node) {
                    is Literal -> append(node.text)
                    is Conditional -> {
                        val branch = if (evaluate(node.condition, variables)) node.then else node.otherwise
                        append(renderNodes(branch, context, variables))
                    }
                }
            }
        }

    /** 条件求值：`!` 取反、`==` / `!=` 比较，其余按「非空且不为 0/false」为真。 */
    internal fun evaluate(condition: String, variables: TavernVariables): Boolean {
        val trimmed = condition.trim()
        if (trimmed.isEmpty()) return false
        Regex("^(.*?)(==|!=)(.*)$").find(trimmed)?.let { match ->
            val left = operand(match.groupValues[1], variables)
            val right = operand(match.groupValues[3], variables)
            return if (match.groupValues[2] == "==") left == right else left != right
        }
        if (trimmed.startsWith("!")) return !truthy(operand(trimmed.removePrefix("!"), variables))
        return truthy(operand(trimmed, variables))
    }

    /** 操作数：`getvar::x` 取值，裸名字先当变量名、取不到再当字面量。 */
    private fun operand(raw: String, variables: TavernVariables): String {
        val literal = raw.trim().trim('"', '\'')
        if (literal.startsWith("getvar::")) return variables.get(literal.removePrefix("getvar::")) ?: ""
        return variables.get(literal) ?: literal
    }

    private fun truthy(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty()) return false
        if (text.equals("false", ignoreCase = true)) return false
        if (text.toDoubleOrNull() == 0.0) return false
        return true
    }

    // ---------- 平铺宏 ----------

    private fun expandFlat(text: String, context: MacroContext?, variables: TavernVariables?): String {
        if (!text.contains("{{")) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val open = text.indexOf("{{", index)
            if (open < 0) {
                out.append(text, index, text.length)
                break
            }
            out.append(text, index, open)
            val close = text.indexOf("}}", open + 2)
            if (close < 0) {
                out.append(text, open, text.length)
                break
            }
            val body = text.substring(open + 2, close)
            val replacement = resolve(body, context, variables)
            if (replacement == null) out.append(text, open, close + 2) else out.append(replacement)
            index = close + 2
        }
        return out.toString()
    }

    /** 返回 null 表示「不认识，原样保留」。 */
    private fun resolve(body: String, context: MacroContext?, variables: TavernVariables?): String? {
        val raw = body.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("//")) return ""
        if (raw.startsWith("#")) return null
        val name = macroName(raw)
        val args = argumentsOf(raw)

        return when (name) {
            "getvar" -> variables?.let { it.get(args.firstOrNull().orEmpty()) ?: "" }
            "setvar" -> variables?.let {
                it.set(args.firstOrNull().orEmpty(), args.drop(1).joinToString("::"))
                ""
            }
            "addvar" -> variables?.let {
                it.add(args.firstOrNull().orEmpty(), args.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 1.0)
                ""
            }
            "incvar" -> variables?.let { it.inc(args.firstOrNull().orEmpty()); "" }
            "decvar" -> variables?.let { it.dec(args.firstOrNull().orEmpty()); "" }
            "roll" -> variables?.let { roll(args.firstOrNull()) }
            "random" -> variables?.let { args.filter(String::isNotEmpty).randomOrNull() }
            "pick" -> variables?.let { args.filter(String::isNotEmpty).randomOrNull() }
            "trim" -> if (variables != null) TRIM_MARKER else null
            "newline" -> "\n"
            "noop" -> ""
            "char" -> context?.characterName?.takeIf(String::isNotBlank)
            "user" -> context?.userName?.takeIf(String::isNotBlank)
            "persona" -> context?.persona?.takeIf(String::isNotBlank)
            "lastusermessage" -> context?.lastUserMessage?.takeIf(String::isNotBlank)
            "lastcharmessage" -> context?.lastCharMessage?.takeIf(String::isNotBlank)
            else -> null
        }
    }

    /**
     * 参数切分：`getvar::k` → ["k"]；`roll 1d20` → ["1d20"]；`roll:1d6` → ["1d6"]；
     * `random::a::b` → ["a","b"]。
     */
    internal fun argumentsOf(raw: String): List<String> {
        val rest = when {
            raw.contains("::") -> raw.substringAfter("::")
            raw.contains(':') -> raw.substringAfter(':')
            raw.contains(' ') -> raw.substringAfter(' ')
            else -> return emptyList()
        }
        return rest.split("::").map { it.trim() }
    }

    /** `1d20` / `3d6` / 纯数字；缺省 1d100。骰数与面数都夹在安全范围，防止预设写出天文数字。 */
    internal fun roll(token: String?): String {
        val normalized = token?.trim()?.removePrefix(":").orEmpty().ifEmpty { "1d100" }
        val match = Regex("^(\\d*)d(\\d+)$", RegexOption.IGNORE_CASE).find(normalized)
            ?: return Random.nextInt(1, (normalized.toIntOrNull() ?: 100).coerceAtLeast(1) + 1).toString()
        val count = (match.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: 1).coerceIn(1, 100)
        val faces = (match.groupValues[2].toIntOrNull() ?: 100).coerceIn(1, 100_000)
        var total = 0L
        repeat(count) { total += Random.nextInt(1, faces + 1) }
        return total.toString()
    }

    private fun List<String>.randomOrNull(): String? =
        if (isEmpty()) null else this[Random.nextInt(size)]
}
