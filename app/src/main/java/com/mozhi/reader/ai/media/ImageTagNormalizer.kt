package com.mozhi.reader.ai.media

/**
 * NovelAI / Danbooru 标签的机械清洗。
 *
 * 这是旧 `ImagePromptComposer` 拆除后**唯一活下来的部分**。旧的两段式提示词生成
 * （先让模型挑人物、再由代码拼 Style/Character/Scene 自然语言）已被
 * [ImageSceneArchitect] 一次请求产出完整配方所取代；`compose()` / `currentFormat()` /
 * `systemInstruction()` 在全仓库**没有任何调用点**，是死代码，已删除。
 *
 * 保留的用途只有一个：外貌卡的中→英翻译结果要按 danbooru 短标签规范化
 * （去围栏、去 `Tags:` 前缀、去尾标点、去重）。
 */
object ImageTagNormalizer {

    /** 中英文标点统一的标签分隔符：模型经常用顿号/句号/分号切标签，不切开就成了一整串。 */
    private val separators = Regex("[,\\uFF0C\\u3001\\u3002\\uFF1B\\uFF1A;]")

    fun normalize(value: String): String = value
        .replace("```", "")
        .lineSequence()
        .map { it.trim().removePrefix("Tags:").removePrefix("tags:").trim() }
        .filter(String::isNotBlank)
        .joinToString(", ")
        .split(separators)
        .map { it.trim().trim('.', ';') }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(", ")
}
