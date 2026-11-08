package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.ai.media.AgentMediaResult
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.ai.search.WebSearchService
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.retrieval.ReadingScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal class WebScrapeTool(
    private val service: WebSearchService
) : AgentTool {
    override val displayName: String = "抓取网页正文"

    override val spec: ToolSpec = ToolSpec(
        name = "web_scrape",
        description = "抓取一个已知网址的网页正文。当搜索结果摘要不足以回答，或用户直接给出网址时使用；不要用它绕过书籍防剧透范围。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "要抓取的完整 http/https 网址")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("url")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val url = arguments["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (url.isEmpty()) return ToolResult.Failure("INVALID_ARGUMENT", "缺少网址 url")
        val result = try {
            service.scrape(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ToolResult.Failure("WEB_SCRAPE_FAILED", error.message ?: "网页抓取失败", error)
        }
        return ToolResult.Success(buildString {
            append("网页正文（回答中用到时，在该句末写〔来源 完整网址〕标注）：\n")
            append("标题：").append(result.title).append('\n')
            append("来源：").append(result.url).append("\n\n")
            append(result.content)
        })
    }
}

internal class WebSearchTool(
    private val service: WebSearchService
) : AgentTool {
    override val displayName: String = "搜索互联网"

    override val spec: ToolSpec = ToolSpec(
        name = "web_search",
        description = "搜索互联网以获取书外知识、近期事实和可引用来源。不要用它查询尚未阅读的书中后续剧情；需要核对本书已读内容时应使用 search_book。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "简洁、独立且适合搜索引擎的查询词")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "返回结果数量，1-8，默认 5")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("query")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.Failure("INVALID_ARGUMENT", "缺少搜索词 query")
        val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 8)
        val results = try {
            service.search(query, limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ToolResult.Failure("WEB_SEARCH_FAILED", error.message ?: "网络搜索失败", error)
        }
        if (results.isEmpty()) return ToolResult.Success("没有找到与「$query」相关的网页结果。")
        return ToolResult.Success(buildString {
            append("互联网搜索结果（回答中用到某条结果时，在该句末写〔来源 完整网址〕标注，不要另列网址）：\n")
            results.forEachIndexed { index, result ->
                append("\n[").append(index + 1).append("] ").append(result.title)
                append("\n").append(result.url)
                if (result.snippet.isNotBlank()) append("\n").append(result.snippet)
                append('\n')
            }
        })
    }
}

internal class GenerateImageTool(
    private val bookId: Long,
    /** 谁发起的（用于插图归属）；书库伴读没有当前角色，允许为空。 */
    private val personaId: Long?,
    private val getBook: suspend () -> BookEntity?,
    private val mediaService: AiMediaGenerationService,
    private val readingScope: ReadingScope,
    /**
     * 是否允许这次调用真的出图。
     *
     * **工具始终注册**，只是在关闭时执行会得到一条可操作的失败说明。
     * 之前是把工具整个摘掉，结果模型只能回答「我没有生图能力」——
     * 用户以为功能不存在，其实只是「设置 → 伴读 → 自主生图」没打开。
     * 工具在、说明在，模型才能告诉用户去哪儿开。
     */
    private val allowed: suspend () -> Boolean = { true }
) : AgentTool {
    override val displayName: String = "生成并保存插图"

    override val spec: ToolSpec = ToolSpec(
        name = "generate_image",
        description = "调用用户分配的生图模型生成小说插图，并永久保存到本书插图廊。提示词必须遵守本轮阅读范围；系统会按当前后端自动改写提示词，NovelAI 使用 Danbooru tags。用户要求画面、插图或角色形象时调用；系统明确已开启自主生图时，也可根据对话主动为有画面感的场景或人物配图。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("prompt") {
                    put("type", "string")
                    put("description", "完整画面提示词：人物、环境、构图、光线、风格；不要文字和水印")
                }
                putJsonObject("source_text") {
                    put("type", "string")
                    put("description", "可选：作为插图依据的已读原文或简述")
                }
                putJsonObject("chapter_number") {
                    put("type", "integer")
                    put("description", "可选关联章节号，从 1 开始")
                }
                putJsonObject("char_offset") {
                    put("type", "integer")
                    put("description", "可选章内 UTF-16 锚点")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("prompt")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!allowed()) {
            return ToolResult.Failure(
                "IMAGE_REPLIES_DISABLED",
                "自主生图当前是关闭的，所以这次没有真的出图。请让用户到「设置 → 伴读 → 自主生图」打开开关后再试；" +
                    "或者在正文里选中一段文字，用选段工具条上的「生成插图」按钮（那条路径不受这个开关限制）。"
            )
        }
        val prompt = arguments["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isEmpty()) return ToolResult.Failure("INVALID_ARGUMENT", "缺少生图提示词 prompt")
        val book = getBook() ?: return ToolResult.Failure("SOURCE_MISSING", "未找到当前书籍")
        val chapterNumber = arguments["chapter_number"]?.jsonPrimitive?.intOrNull
        val maxVisibleChapter = readingScope.clampLastChapter(book.totalChapters)
        if (chapterNumber != null && chapterNumber !in 1..maxVisibleChapter + 1) {
            return ToolResult.Failure("OUT_OF_SCOPE", "插图锚点超出当前可见范围：最多可访问第 ${maxVisibleChapter + 1} 章。")
        }
        val chapterIndex = chapterNumber?.minus(1) ?: if (readingScope.isWholeBook) {
            book.lastReadChapterIndex
        } else {
            readingScope.maxChapterIndex
        }
        val requestedOffset = arguments["char_offset"]?.jsonPrimitive?.intOrNull
        val charOffset = (requestedOffset ?: if (!readingScope.isWholeBook && chapterIndex == readingScope.maxChapterIndex) {
            readingScope.maxCharOffset
        } else {
            0
        }).coerceAtLeast(0)
        if (!readingScope.isWholeBook && chapterIndex == readingScope.maxChapterIndex && charOffset > readingScope.maxCharOffset) {
            return ToolResult.Failure("OUT_OF_SCOPE", "插图锚点超出当前阅读水位（最大字符偏移 ${readingScope.maxCharOffset}）。")
        }
        val illustration = mediaService.generateIllustration(
            bookId = bookId,
            chapterIndex = chapterIndex,
            charOffset = charOffset,
            sourceText = arguments["source_text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            prompt = prompt,
            personaId = personaId
        )
        return ToolResult.Success(AgentMediaResult(
            mediaKind = "image",
            mediaId = illustration.id,
            path = illustration.imagePath,
            mediaType = illustration.mediaType,
            message = "插图已保存到《${book.title}》插图廊"
        ).encode())
    }
}

internal class SynthesizeSpeechTool(
    private val bookId: Long,
    private val mediaService: AiMediaGenerationService
) : AgentTool {
    override val displayName: String = "合成并缓存语音"

    override val spec: ToolSpec = ToolSpec(
        name = "synthesize_speech",
        description = "使用用户分配的 OpenAI 或 MiniMax TTS 模型合成语音。相同文本、模型、音色和参数会直接复用缓存。用户要求朗读、配音或有声片段时调用。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "要朗读的文本，最长 8000 字；只能包含当前已知内容")
                }
                putJsonObject("voice_id") {
                    put("type", "string")
                    put("description", "可选音色 ID。OpenAI 如 alloy、nova；MiniMax 可填系统/克隆音色 ID。省略则使用模型配置")
                }
                putJsonObject("speed") {
                    put("type", "number")
                    put("description", "语速倍率；OpenAI 0.25-4.0，MiniMax 0.5-2.0，省略使用模型配置")
                }
                putJsonObject("volume") {
                    put("type", "number")
                    put("description", "MiniMax 音量 0-10，可选")
                }
                putJsonObject("pitch") {
                    put("type", "integer")
                    put("description", "MiniMax 音调 -12 到 12，可选")
                }
                putJsonObject("format") {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.buildJsonArray {
                        add(JsonPrimitive("mp3")); add(JsonPrimitive("wav"));
                        add(JsonPrimitive("flac")); add(JsonPrimitive("aac"))
                    })
                }
            }
            putJsonArray("required") { add(JsonPrimitive("text")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.Failure("INVALID_ARGUMENT", "缺少朗读文本 text")
        val speech = mediaService.synthesizeSpeech(
            bookId = bookId,
            text = text,
            voiceId = arguments["voice_id"]?.jsonPrimitive?.contentOrNull,
            speed = arguments["speed"]?.jsonPrimitive?.floatOrNull,
            volume = arguments["volume"]?.jsonPrimitive?.floatOrNull,
            pitch = arguments["pitch"]?.jsonPrimitive?.intOrNull,
            format = arguments["format"]?.jsonPrimitive?.contentOrNull
        )
        return ToolResult.Success(AgentMediaResult(
            mediaKind = "audio",
            path = speech.path,
            mediaType = speech.mediaType,
            message = if (speech.cacheHit) "已复用语音缓存，可直接播放" else "语音已生成并缓存，可直接播放"
        ).encode())
    }
}
