package com.mozhi.reader.ai.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.mozhi.reader.ai.media.ImageCapabilities
import com.mozhi.reader.ai.media.ReferenceKind
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * NovelAI 生图客户端：`/ai/generate-image` 返回 zip（内含 PNG）。
 * v4 系模型走 v4_prompt 结构化提示词；v3 及更早用平铺 negative_prompt。
 */
class NovelAiImageClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val defaultSize: String,
    private val positivePrompt: String = "",
    private val negativePrompt: String,
    private val sampler: String = "",
    private val steps: Int = 28,
    private val scale: Float = 5f,
    /** NAI 的 UC 预设档位：0=Heavy 1=Light 2=None 3=Human Focus。 */
    private val ucPreset: Int = 0,
    /** 是否让官方自动追加质量词。 */
    private val qualityToggle: Boolean = true,
    private val httpClient: OkHttpClient
) : ImageGenerationClient {

    private val base = normalizeBase(baseUrl.ifBlank { DEFAULT_BASE_URL })
    override val defaultImageSize: String get() = defaultSize
    override val capabilities: ImageCapabilities get() {
        val v45 = model.startsWith("nai-diffusion-4-5")
        val v4 = model.startsWith("nai-diffusion-4")
        val v5 = model.startsWith("nai-diffusion-5")
        return ImageCapabilities(maxReferences = if (v4 || v5) 3 else 0,
            maxCharacterReferences = if (v45) 1 else 0, maxCharacters = if (v5) 22 else if (v4) 6 else Int.MAX_VALUE,
            perCharacterPrompt = v4 || v5, seed = true, vibe = v4 || v5,
            exclusiveCharacterAndStyle = true, characterReferenceCost = 5, tags = true, multilingual = v5)
    }

    override suspend fun generateImages(
        prompt: String,
        count: Int,
        size: String?
    ): List<GeneratedImage> {
        return generateImages(ImageRequest(prompt = prompt, count = count, size = size))
    }

    override suspend fun generateImages(request: ImageRequest): List<GeneratedImage> {
        require(request.count in 1..4)
        return (0 until request.count).map { index ->
            currentCoroutineContext().ensureActive()
            generateOne(request.copy(count = 1, seed = request.seed?.let { (it + index) and 0xffffffffL }))
        }
    }

    private suspend fun generateOne(input: ImageRequest): GeneratedImage {
        require(input.prompt.isNotBlank()) { "生图提示词不能为空" }
        val payload = buildPayload(input, input.size ?: defaultSize)
        val request = Request.Builder()
            .url("$base/ai/generate-image")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "*/*")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val zipBytes = withContext(Dispatchers.IO) {
            val response = try {
                executeWithRetry(request)
            } catch (error: Throwable) {
                throw mapTransportError(error)
            }
            response.use {
                if (!it.isSuccessful) {
                    throw httpError(it.code, extractErrorMessage(it.body.string()))
                }
                val contentType = it.header("Content-Type").orEmpty()
                if (contentType.contains("json", ignoreCase = true)) {
                    throw AiClientException.Malformed(
                        extractErrorMessage(it.body.string()) ?: "NovelAI 返回了非图片响应"
                    )
                }
                val declaredLength = it.body.contentLength()
                require(declaredLength < 0 || declaredLength <= MAX_ARCHIVE_BYTES) {
                    "生成图片超过 30 MB，已取消保存"
                }
                it.body.bytes().also { raw ->
                    require(raw.size <= MAX_ARCHIVE_BYTES) { "生成图片超过 30 MB，已取消保存" }
                }
            }
        }
        val image = NovelAiZipParser.firstImage(zipBytes)
            ?: throw AiClientException.Malformed("NovelAI 响应里没有图片文件")
        if (image.isEmpty()) throw AiClientException.Empty()
        return GeneratedImage(bytes = image, url = null, mediaType = "image/png")
    }

    /**
     * 5xx 与队列满重试一次。
     *
     * NAI（以及套在它前面的中转站）在排队拥塞时会回 500 / 503 或带
     * `{"statusCode":500,"message":"Internal Server Error"}` 的裸 500。
     * 这类失败**重试大概率就好了**，但一次性放弃会让用户白等几十秒拿到一个红条。
     * 只重试一次、只重试 5xx 与 429：4xx 是请求本身有问题，重试是浪费钱。
     */
    private suspend fun executeWithRetry(request: Request): okhttp3.Response {
        repeat(RETRY_ATTEMPTS) { attempt ->
            val last = attempt == RETRY_ATTEMPTS - 1
            val response = try {
                httpClient.newCall(request).execute()
            } catch (error: Throwable) {
                // 网络层错误（连接被重置、超时）同样值得再试一次；最后一次原样抛出，
                // 交给 mapTransportError 翻译成用户能看懂的文案。
                if (last) throw error
                // 用 delay 而不是 Thread.sleep：它可被取消，用户点「停止」能立刻退出。
                delay(RETRY_DELAY_MS * (attempt + 1))
                return@repeat
            }
            // 最后一次不重试：**把响应原样交回去**，调用方才能从 body 里读到
            // 真实错误（丢掉 body 只剩一句"重试后仍然失败"，等于把诊断信息扔了）。
            if (last || !isRetryable(response.code)) return response
            // 重试前必须关闭，否则连接不会归还连接池。
            runCatching { response.close() }
            delay(RETRY_DELAY_MS * (attempt + 1))
        }
        error("unreachable: repeat 的最后一次一定已经 return 或 throw")
    }

    private fun isRetryable(code: Int): Boolean = code == 429 || code in 500..599

    override suspend fun materializeImage(image: GeneratedImage): ByteArray =
        image.bytes?.takeIf(ByteArray::isNotEmpty) ?: throw AiClientException.Empty()

    /** 拆出来便于单测校验请求体结构。 */
    internal fun buildPayload(prompt: String, size: String): JsonObject {
        return buildPayload(ImageRequest(prompt), size)
    }

    internal fun buildPayload(request: ImageRequest, size: String): JsonObject {
        val (width, height) = parseSize(size)
        val effectivePrompt = mergePositivePrompt(positivePrompt, request.prompt)
        val negative = mergePositivePrompt(negativePrompt.trim().ifBlank { DEFAULT_NEGATIVE_PROMPT }, request.negative)
        val isV4 = capabilities.perCharacterPrompt
        val isV5 = model.startsWith("nai-diffusion-5")
        require(request.characterPrompts.size <= capabilities.maxCharacters) { "当前 NovelAI 模型的出场人物数量已达上限" }
        val parameters = linkedMapOf<String, JsonElement>(
            "width" to JsonPrimitive(width),
            "height" to JsonPrimitive(height),
            "scale" to JsonPrimitive(scale.coerceIn(0f, 10f)),
            "sampler" to JsonPrimitive(sampler.trim().ifBlank { DEFAULT_SAMPLER }),
            "steps" to JsonPrimitive(steps.coerceIn(1, 50)),
            "n_samples" to JsonPrimitive(1),
            "ucPreset" to JsonPrimitive(ucPreset.coerceIn(0, 3)),
            "qualityToggle" to JsonPrimitive(qualityToggle),
            "negative_prompt" to JsonPrimitive(negative),
            // 下面这一批是官方 web 端**每次都会带**的默认值。少发时，官方服务能靠默认值蒙混过去，
            // 但中转站/自建代理往往按"字段齐全"做校验，缺字段就直接 500。
            // 实测事故（2026-11-08）：选段出图转圈后返回
            // {"statusCode":500,"message":"Internal Server Error"}，就是中转站这一类。
            "dynamic_thresholding" to JsonPrimitive(false),
            "controlnet_strength" to JsonPrimitive(1),
            "legacy" to JsonPrimitive(false),
            "add_original_image" to JsonPrimitive(true),
            "noise_schedule" to JsonPrimitive(if (isV5) "karras" else "native"),
            "legacy_v3_extend" to JsonPrimitive(false),
            "legacy_uc" to JsonPrimitive(false),
            "autoSmea" to JsonPrimitive(false),
            "prefer_brownian" to JsonPrimitive(true),
            "deliberate_euler_ancestral_bug" to JsonPrimitive(false),
            "inpaintImg2ImgStrength" to JsonPrimitive(1),
            "normalize_reference_strength_multiple" to JsonPrimitive(false),
            "image_format" to JsonPrimitive("png"),
            "stream" to JsonPrimitive("msgpack")
        )
        // 只有非 V5 才发 cfg_rescale：官方能力表里 V5 没有这个字段，带上会被请求清洗删掉。
        if (!isV5) parameters["cfg_rescale"] = JsonPrimitive(0)
        request.seed?.let { parameters["seed"] = JsonPrimitive(it and 0xffffffffL) }
        val characterRefs = request.references.filter { it.spec.kind == ReferenceKind.CHARACTER }
        val styleRefs = request.references.filter { it.spec.kind == ReferenceKind.STYLE }
        require(characterRefs.size <= capabilities.maxCharacterReferences)
        require(characterRefs.isEmpty() || styleRefs.isEmpty()) { "角色参考不能与 Vibe 同时使用" }
        if (characterRefs.isNotEmpty()) {
            parameters["director_reference_images"] = JsonArray(characterRefs.map { JsonPrimitive(Base64.getEncoder().encodeToString(it.bytes)) })
            parameters["director_reference_descriptions"] = JsonArray(characterRefs.map {
                JsonObject(mapOf("caption" to JsonObject(mapOf("base_caption" to JsonPrimitive("character"), "char_captions" to JsonArray(emptyList())))))
            })
            parameters["director_reference_information_extracted"] = JsonArray(characterRefs.map { JsonPrimitive(1f) })
            parameters["director_reference_strength_values"] = JsonArray(characterRefs.map { JsonPrimitive(it.spec.strength.coerceIn(0f, 1f)) })
            parameters["director_reference_secondary_strength_values"] = JsonArray(characterRefs.map { JsonPrimitive(it.spec.fidelity.coerceIn(0f, 1f)) })
        }
        if (styleRefs.isNotEmpty()) {
            require(capabilities.vibe && styleRefs.all { it.encodedVibe })
            parameters["reference_image_multiple"] = JsonArray(styleRefs.map { JsonPrimitive(Base64.getEncoder().encodeToString(it.bytes)) })
            parameters["reference_information_extracted_multiple"] = JsonArray(styleRefs.map { JsonPrimitive(1f) })
            parameters["reference_strength_multiple"] = JsonArray(styleRefs.map { JsonPrimitive(it.spec.strength.coerceIn(0f, 1f)) })
        }
        if (isV4) {
            // V5 用 params_version=4，V4 / V4.5 用 3 —— 官方 web 端就是这么分的，
            // 恒发 3 会让 V5 模型被当成旧协议解析（实测 500 的另一个可能来源）。
            parameters["params_version"] = JsonPrimitive(if (isV5) 4 else 3)
            parameters["v4_prompt"] = JsonObject(
                mapOf(
                    "caption" to JsonObject(
                        mapOf(
                            "base_caption" to JsonPrimitive(effectivePrompt),
                            "char_captions" to JsonArray(request.characterPrompts.map { character ->
                                JsonObject(
                                    mapOf(
                                        "char_caption" to JsonPrimitive(character),
                                        "centers" to DEFAULT_CENTERS
                                    )
                                )
                            })
                        )
                    ),
                    "use_coords" to JsonPrimitive(false),
                    "use_order" to JsonPrimitive(true)
                )
            )
            // **正负 char_captions 必须等长**。官方会在长度不一致时直接拒绝：
            // 「V4 positive and negative character prompts must have the same length.」
            // 原来这里恒写空数组，只要正向带了角色就一定会被判不等长。
            // 按正向的角色数展开，没写负向的发空串（与官方 web 端一致）。
            parameters["v4_negative_prompt"] = JsonObject(
                mapOf(
                    "caption" to JsonObject(
                        mapOf(
                            "base_caption" to JsonPrimitive(negative),
                            "char_captions" to JsonArray(
                                request.characterPrompts.map {
                                    JsonObject(
                                        mapOf(
                                            "char_caption" to JsonPrimitive(""),
                                            "centers" to DEFAULT_CENTERS
                                        )
                                    )
                                }
                            )
                        )
                    ),
                    "legacy_uc" to JsonPrimitive(false)
                )
            )
        }
        return JsonObject(
            mapOf(
                "input" to JsonPrimitive(effectivePrompt),
                "model" to JsonPrimitive(model),
                "action" to JsonPrimitive("generate"),
                "parameters" to JsonObject(parameters)
            )
        )
    }

    /** Official /ai/encode-vibe returns binary. Caller caches by image hash + model + extraction. */
    suspend fun encodeVibe(bytes: ByteArray, information: Float): ByteArray = withContext(Dispatchers.IO) {
        val payload = JsonObject(mapOf("model" to JsonPrimitive(model),
            "image" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
            "information_extracted" to JsonPrimitive(information.coerceIn(0f, 1f))))
        val request = Request.Builder().url("$base/ai/encode-vibe").header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response.code, extractErrorMessage(response.body.string()))
            val data = response.body.bytes()
            require(data.isNotEmpty() && data.size <= MAX_ARCHIVE_BYTES) { "Vibe 编码响应无效" }
            data
        }
    }

    /** NovelAI 要求宽高为 64 的倍数；非法输入回落竖版 832x1216。 */
    private fun parseSize(size: String): Pair<Int, Int> {
        val parts = size.lowercase().split('x', '×')
        val width = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val height = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) return 832 to 1216
        fun align(value: Int): Int = (value / 64 * 64).coerceIn(64, 2048)
        return align(width) to align(height)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://image.novelai.net"
        const val DEFAULT_SAMPLER = "k_euler_ancestral"
        const val DEFAULT_NEGATIVE_PROMPT =
            "lowres, jpeg artifacts, worst quality, bad quality, watermark, blurry, very displeasing"
        private const val MAX_ARCHIVE_BYTES = 30 * 1024 * 1024
        /**
         * 角色位置。**不能发空数组**。
         *
         * 实测（2026-11-08，官方 image.novelai.net，同一个 key 同一个模型）：
         * - `char_captions[].centers = []`            → **HTTP 500**
         *   `{"statusCode":500,"message":"Internal Server Error"}`
         * - `char_captions[].centers = [{x:.5,y:.5}]` → 200，正常出图
         *
         * 只要 `char_captions` 非空（即正向带了角色），每个条目的 centers 就必须至少一个点，
         * 正向负向都一样。空数组会被服务端当成非法坐标直接 500 —— 而且返回的是裸 500，
         * 没有任何说明，看起来像服务挂了。
         *
         * 这里用画布中心。`use_coords=false` 时官方会自己按顺序排布，坐标只是个占位，
         * 所以统一给中心点即可（与 plana 的 AUTO 分支一致：position 为空时返回 {0.5, 0.5}）。
         */
        private val DEFAULT_CENTERS: JsonArray = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "x" to JsonPrimitive(0.5),
                        "y" to JsonPrimitive(0.5)
                    )
                )
            )
        )

        /** 总共试几次（含首次）。 */
        private const val RETRY_ATTEMPTS = 2
        /** 第 n 次重试前等 n × 这个值。 */
        private const val RETRY_DELAY_MS = 1_500L

        internal fun mergePositivePrompt(fixed: String, dynamic: String): String =
            listOf(fixed, dynamic)
                .map { it.trim().trim(',') }
                .filter(String::isNotBlank)
                .joinToString(", ")
    }
}

/** NovelAI zip 响应解包：取第一张图片文件，读取时限流防 zip 炸弹。 */
internal object NovelAiZipParser {
    private const val MAX_ENTRY_BYTES = 30 * 1024 * 1024
    private val IMAGE_SUFFIXES = listOf(".png", ".jpg", ".jpeg", ".webp")

    fun firstImage(bytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.lowercase()
                    if (IMAGE_SUFFIXES.any(name::endsWith)) {
                        return readLimited(zip)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun readLimited(input: ZipInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_ENTRY_BYTES) { "生成图片超过 30 MB，已取消保存" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
