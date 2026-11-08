package com.mozhi.reader.core.pattern

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正则字面量的**平台可移植性**守门。
 *
 * `java.util.regex.Pattern` 在两个平台上**不是同一套实现**：
 * JVM（单测跑在这里）是 OpenJDK 的实现，Android 是包在 **ICU4C** 外面的 libcore 实现。
 *
 * 两者对裸花括号的处理**不一致**：JVM 把裸 `}` 当普通字符放过，ICU4C 直接报
 * `U_REGEX_RULE_SYNTAX`，落到 Java 层就是 `Syntax error in regexp pattern near index N`。
 *
 * 结果就是 **单测全绿、装机即崩**。2026-11-08 的事故正是如此：
 * `\{\{([^{}]+)}}` 在 JVM 合法，在真机上让「导入酒馆预设」100% 失败。
 *
 * 判定与 ICU4C 对齐：字符类 `[...]` 内的 `{ }` 合法；`{n}` / `{n,}` / `{n,m}` 合法；
 * 其余位置的 `{ }` 必须写成 `\{` / `\}`。
 *
 * 复核依据（本机 ICU4C 74.2 实测）：
 * `\{\{([^{}]+)}}` → U_REGEX_RULE_SYNTAX；`\{\{([^{}]+)\}\}` → OK；
 * `a}` / `a{,2}` / `a{` / `like {this}` / `\$\{[^{}]*}` 全部失败。
 */
class RegexPortabilityTest {

    @Test
    fun noBareBracesInRegexLiterals() {
        val offenders = mutableListOf<String>()
        val root = File("src/main/java/com/mozhi/reader")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val source = file.readText()
                val path = file.relativeTo(root).invariantSeparatorsPath
                regexLiterals(source).forEach { (offset, regex) ->
                    val bad = bareBraces(regex)
                    if (bad.isNotEmpty()) {
                        val line = source.take(offset).count { it == '\n' } + 1
                        offenders += "$path:$line  $regex  → 裸花括号下标 ${bad.take(4)}"
                    }
                }
            }
        assertTrue(
            "以下正则字面量含裸花括号：JVM 上合法，但 Android 的 ICU 实现会抛 " +
                "Syntax error in regexp pattern。把 { 写成 \\{、} 写成 \\}：\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty()
        )
    }

    /** 反证：扫描器自己要有牙齿，否则这个守门测试等于没开。 */
    @Test
    fun scannerHasTeeth() {
        assertTrue(bareBraces("\\{\\{([^{}]+)}}").isNotEmpty())
        assertTrue(bareBraces("\\{\\{([^{}]+)\\}\\}").isEmpty())
        assertTrue(bareBraces("a{2,3}").isEmpty())
        assertTrue(bareBraces("a{2,}").isEmpty())
        assertTrue(bareBraces("[^{}]+").isEmpty())
        assertTrue(bareBraces("like {this}").isNotEmpty())
        assertTrue(bareBraces("a{,2}").isNotEmpty())
        assertTrue(bareBraces("a}").isNotEmpty())
    }

    // ---------- 源码扫描 ----------

    /** 找出源码里所有 `Regex(<字符串字面量>)`，返回 (偏移, 还原后的正则)。 */
    private fun regexLiterals(source: String): List<Pair<Int, String>> {
        val found = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (true) {
            val at = source.indexOf("Regex(", i)
            if (at < 0) return found
            var j = skipSpaces(source, at + "Regex(".length)
            val parsed = readLiteral(source, j)
            if (parsed == null) {
                i = at + 1
                continue
            }
            found += at to parsed.first
            i = parsed.second
        }
    }

    private fun skipSpaces(source: String, from: Int): Int {
        var i = from
        while (i < source.length && source[i].isWhitespace()) i += 1
        return i
    }

    /**
     * 读一个 Kotlin 字符串字面量并还原成"正则真正看到的字符串"。
     * 返回 (内容, 字面量结束后的下标)；不是字面量返回 null。
     *
     * `${…}` 模板段整体替换成 `X`：它是编译期插值，花括号不属于正则语法，
     * 里面的引号也可能把字面量切坏。
     */
    private fun readLiteral(source: String, start: Int): Pair<String, Int>? {
        if (source.startsWith(TRIPLE, start)) {
            val end = source.indexOf(TRIPLE, start + 3)
            if (end < 0) return null
            return source.substring(start + 3, end) to (end + 3)
        }
        if (source.getOrNull(start) != '"') return null
        val out = StringBuilder()
        var i = start + 1
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' -> return out.toString() to (i + 1)
                c == '\n' -> return null
                c == '\\' && i + 1 < source.length -> {
                    val next = source[i + 1]
                    when (next) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        '"' -> out.append('"')
                        '\'' -> out.append('\'')
                        '\\' -> out.append('\\')
                        '\u0024' -> out.append('\u0024')
                        'u' -> {
                            val hex = source.substring(i + 2, minOf(i + 6, source.length))
                            val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                            out.append('\\').append(next)
                        }
                        else -> out.append('\\').append(next)
                    }
                    i += 2
                }
                c == '\u0024' && source.getOrNull(i + 1) == '{' -> {
                    i = skipTemplate(source, i + 1)
                    out.append('X')
                }
                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return null
    }

    /** 从 `{` 起跳过配平的花括号（其中的字符串字面量也要跳过），返回 `}` 之后的下标。 */
    private fun skipTemplate(source: String, braceAt: Int): Int {
        var i = braceAt
        var depth = 0
        while (i < source.length) {
            when (source[i]) {
                '"' -> {
                    i += 1
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') i += 1
                        i += 1
                    }
                    i += 1
                }
                '{' -> { depth += 1; i += 1 }
                '}' -> {
                    depth -= 1
                    i += 1
                    if (depth == 0) return i
                }
                else -> i += 1
            }
        }
        return source.length
    }

    // ---------- ICU4C 对齐的判定 ----------

    /** 返回裸花括号的下标；空表表示可移植。 */
    private fun bareBraces(regex: String): List<Int> {
        val bad = mutableListOf<Int>()
        var i = 0
        var inClass = false
        while (i < regex.length) {
            val c = regex[i]
            when {
                c == '\\' -> i += 2
                inClass -> {
                    if (c == ']') inClass = false
                    i += 1
                }
                c == '[' -> {
                    inClass = true
                    i += 1
                }
                c == '{' -> {
                    val close = regex.indexOf('}', i + 1)
                    val inner = if (close > 0) regex.substring(i + 1, close) else null
                    if (inner != null && QUANTIFIER.matches(inner)) i = close + 1 else { bad += i; i += 1 }
                }
                c == '}' -> {
                    bad += i
                    i += 1
                }
                else -> i += 1
            }
        }
        return bad
    }

    private companion object {
        const val TRIPLE = "\"\"\""
        /** ICU 的量词只接受 `\d+` 或 `\d+,` 或 `\d+,\d+`。 */
        val QUANTIFIER = Regex("\\d+(,\\d*)?")
    }
}
