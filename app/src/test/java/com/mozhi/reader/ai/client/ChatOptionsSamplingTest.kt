package com.mozhi.reader.ai.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 采样参数：酒馆预设带 `top_p` / `top_k` / `min_p` / `repetition_penalty` / `frequency_penalty` /
 * `presence_penalty` / `seed`，以前这些值在本应用里**根本没有落点**（发不出去）。
 * 现在它们从 provider 的 `extraJson` 读进来，并在设了值时才写进请求体。
 */
class ChatOptionsSamplingTest {

    @Test
    fun `从 extraJson 读出全部采样扩展`() {
        val options = ChatOptions.fromExtraJson(
            """
            {"temperature":0.9,"top_p":0.95,"top_k":40,"min_p":0.05,
             "frequency_penalty":0.3,"presence_penalty":0.2,"repetition_penalty":1.1,
             "seed":12345,"max_tokens":2048}
            """.trimIndent()
        )
        assertEquals(0.9f, options.temperature)
        assertEquals(0.95f, options.topP)
        assertEquals(40, options.topK)
        assertEquals(0.05f, options.minP)
        assertEquals(0.3f, options.frequencyPenalty)
        assertEquals(0.2f, options.presencePenalty)
        assertEquals(1.1f, options.repetitionPenalty)
        assertEquals(12345L, options.seed)
        assertEquals(2048, options.maxTokens)
    }

    @Test
    fun `没写的字段保持 null，不会凭空塞进请求体`() {
        val options = ChatOptions.fromExtraJson("""{"temperature":0.7}""")
        assertEquals(0.7f, options.temperature)
        assertNull(options.topP)
        assertNull(options.topK)
        assertNull(options.minP)
        assertNull(options.frequencyPenalty)
        assertNull(options.presencePenalty)
        assertNull(options.repetitionPenalty)
        assertNull(options.seed)
    }

    @Test
    fun `空配置与坏配置都回落默认值`() {
        assertEquals(ChatOptions.Default, ChatOptions.fromExtraJson(null))
        assertEquals(ChatOptions.Default, ChatOptions.fromExtraJson(""))
        assertEquals(ChatOptions.Default, ChatOptions.fromExtraJson("{}"))
        assertEquals(ChatOptions.Default, ChatOptions.fromExtraJson("{不是 json"))
    }

    @Test
    fun `mergeExtras 接受 null 与空对象`() {
        val base = JsonObject(mapOf("model" to JsonPrimitive("m")))
        assertEquals(base, base.mergeExtras(null))
        assertEquals(base, base.mergeExtras(JsonObject(emptyMap())))
        val merged = base.mergeExtras(JsonObject(mapOf("top_k" to JsonPrimitive(40))))
        assertEquals(JsonPrimitive(40), merged["top_k"])
        assertEquals(JsonPrimitive("m"), merged["model"])
    }
}
