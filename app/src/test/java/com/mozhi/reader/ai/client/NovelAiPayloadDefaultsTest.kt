package com.mozhi.reader.ai.client

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NovelAI 请求体的**字段齐全性**与**失败重试**。
 *
 * 2026-11-08 真机事故：选段出图转圈后返回
 * `{"statusCode":500,"message":"Internal Server Error"}`。
 * 那是**中转站**的报错格式（官方是 `{"status":…,"message":…}`），
 * 而中转站普遍按"字段齐全"校验请求体 —— 少发官方 web 端必需的默认字段就 500。
 *
 * 这组用例把"必发字段"钉住：以后谁删掉一个，这里立刻红。
 */
class NovelAiPayloadDefaultsTest {

    @Test
    fun officialWebDefaultsAreAlwaysPresent() {
        val parameters = client("nai-diffusion-4-5-full").buildPayload("1girl", "832x1216").obj("parameters")

        // 官方 web 端每次都会带这一批默认值；缺任何一个都可能被中转站判为非法请求。
        assertFalse(parameters.flag("dynamic_thresholding"))
        assertEquals(1, parameters.int("controlnet_strength"))
        assertFalse(parameters.flag("legacy"))
        assertTrue(parameters.flag("add_original_image"))
        assertEquals("native", parameters.text("noise_schedule"))
        assertFalse(parameters.flag("legacy_v3_extend"))
        assertFalse(parameters.flag("legacy_uc"))
        assertFalse(parameters.flag("autoSmea"))
        assertTrue(parameters.flag("prefer_brownian"))
        assertFalse(parameters.flag("deliberate_euler_ancestral_bug"))
        assertEquals(1, parameters.int("inpaintImg2ImgStrength"))
        assertFalse(parameters.flag("normalize_reference_strength_multiple"))
        assertEquals("png", parameters.text("image_format"))
        assertEquals("msgpack", parameters.text("stream"))
        // 非 V5 才发 cfg_rescale。
        assertEquals(0, parameters.int("cfg_rescale"))
    }

    @Test
    fun v5UsesParamsVersion4AndKarrasAndOmitsCfgRescale() {
        val parameters = client("nai-diffusion-5-full").buildPayload("1girl", "832x1216").obj("parameters")

        assertEquals(4, parameters.int("params_version"))
        assertEquals("karras", parameters.text("noise_schedule"))
        // 官方能力表里 V5 没有 cfg_rescale，带上会被请求清洗删掉，干脆不发。
        assertFalse(parameters.containsKey("cfg_rescale"))
    }

    @Test
    fun v4UsesParamsVersion3() {
        val parameters = client("nai-diffusion-4-5-full").buildPayload("1girl", "832x1216").obj("parameters")
        assertEquals(3, parameters.int("params_version"))
    }

    @Test
    fun negativeCharCaptionsMatchPositiveLength() {
        // 官方硬规则：「V4 positive and negative character prompts must have the same length.」
        // 以前负向恒写空数组，只要正向带了角色就必然不等长。
        val request = ImageRequest(prompt = "1girl, 1boy", characterPrompts = listOf("silver hair", "black hair"))
        val parameters = client("nai-diffusion-4-5-full").buildPayload(request, "832x1216").obj("parameters")

        val positive = parameters.getValue("v4_prompt").jsonObject
            .getValue("caption").jsonObject.getValue("char_captions").jsonArray
        val negative = parameters.getValue("v4_negative_prompt").jsonObject
            .getValue("caption").jsonObject.getValue("char_captions").jsonArray

        assertEquals(2, positive.size)
        assertEquals(positive.size, negative.size)
        assertEquals("", negative[0].jsonObject.getValue("char_caption").jsonPrimitive.content)
        assertFalse(parameters.getValue("v4_negative_prompt").jsonObject
            .getValue("legacy_uc").jsonPrimitive.boolean)
    }

    @Test
    fun noCharactersMeansBothCaptionListsAreEmpty() {
        val parameters = client("nai-diffusion-4-5-full").buildPayload("landscape", "832x1216").obj("parameters")
        val positive = parameters.getValue("v4_prompt").jsonObject
            .getValue("caption").jsonObject.getValue("char_captions").jsonArray
        val negative = parameters.getValue("v4_negative_prompt").jsonObject
            .getValue("caption").jsonObject.getValue("char_captions").jsonArray
        assertEquals(0, positive.size)
        assertEquals(0, negative.size)
    }

    @Test
    fun everyCharCaptionCarriesANonEmptyCenter() {
        // **这是 500 的真正原因**（2026-11-08 用官方 API 逐条实测过）：
        //   centers = []              → HTTP 500 {"statusCode":500,"message":"Internal Server Error"}
        //   centers = [{x:.5,y:.5}]   → 200，正常出图
        // 只要 char_captions 非空，每个条目的 centers 就至少要有一个点；正向负向都一样。
        val request = ImageRequest(prompt = "1girl, 1boy", characterPrompts = listOf("silver hair", "black hair"))
        val parameters = client("nai-diffusion-5-full-medium").buildPayload(request, "832x1216").obj("parameters")

        val positive = parameters.getValue("v4_prompt").jsonObject
            .getValue("caption").jsonObject.getValue("char_captions").jsonArray
        val negative = parameters.getValue("v4_negative_prompt").jsonObject
            .getValue("caption").jsonObject.getValue("char_captions").jsonArray

        (positive + negative).forEachIndexed { index, element ->
            val centers = element.jsonObject.getValue("centers").jsonArray
            assertTrue("第 $index 条 char_captions 的 centers 不能为空", centers.isNotEmpty())
            val point = centers[0].jsonObject
            assertTrue(point.getValue("x").jsonPrimitive.content.isNotBlank())
            assertTrue(point.getValue("y").jsonPrimitive.content.isNotBlank())
        }
    }

    // ---------- 重试 ----------

    @Test
    fun serverErrorIsRetriedOnceThenSucceeds() = runBlocking {
        var calls = 0
        val http = countingClient { _ ->
            calls += 1
            if (calls == 1) 500 to """{"statusCode":500,"message":"Internal Server Error"}"""
            else 200 to null
        }
        val result = client("nai-diffusion-4-5-full", http).generateImages("1girl").single()
        assertEquals(2, calls)
        assertTrue((result.bytes?.size ?: 0) > 0)
    }

    @Test
    fun clientErrorIsNotRetried() = runBlocking {
        var calls = 0
        val http = countingClient { _ ->
            calls += 1
            400 to """{"status":400,"message":"bad prompt"}"""
        }
        val error = runCatching { client("nai-diffusion-4-5-full", http).generateImages("1girl") }.exceptionOrNull()
        // 4xx 是请求本身有问题，重试纯属浪费钱 —— 只打一次。
        assertEquals(1, calls)
        requireNotNull(error) { "400 应该抛出异常" }
        assertTrue(error is AiClientException)
        assertTrue(error.message.orEmpty().contains("400"))
    }

    @Test
    fun persistentServerErrorSurfacesTheRealBodyNotAGenericText() = runBlocking {
        var calls = 0
        val http = countingClient { _ ->
            calls += 1
            500 to """{"statusCode":500,"message":"Internal Server Error"}"""
        }
        val error = runCatching { client("nai-diffusion-4-5-full", http).generateImages("1girl") }.exceptionOrNull()
        assertEquals(2, calls)
        // 最后一次的响应体必须原样带回，否则用户只看到"重试后仍然失败"，没有任何诊断信息。
        requireNotNull(error) { "持久 500 应该抛出异常" }
        assertTrue(error.message.orEmpty().contains("Internal Server Error"))
    }

    // ---------- 脚手架 ----------

    private fun client(model: String, http: OkHttpClient = OkHttpClient()) = NovelAiImageClient(
        baseUrl = "https://image.novelai.net",
        apiKey = "k",
        model = model,
        defaultSize = "832x1216",
        negativePrompt = "",
        httpClient = http
    )

    /** 按 [respond] 给的 (状态码, 错误体) 出响应；200 则回一张能解析的最小 zip。 */
    private fun countingClient(
        respond: (okhttp3.Request) -> Pair<Int, String?>
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val (code, errorBody) = respond(chain.request())
                if (code !in 200..299) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("ERR")
                        .header("Content-Type", "application/json")
                        .body((errorBody ?: "").toByteArray().toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    val bytes = zipOf("image_0.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/x-zip-compressed")
                        .body(bytes.toResponseBody("application/x-zip-compressed".toMediaType()))
                        .build()
                }
            }
        )
        .build()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
}
