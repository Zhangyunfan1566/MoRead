package com.mozhi.reader.ai.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设**顶层**采样参数的解析。
 *
 * 关键行为：**中性值不搬**（`top_k = 0` / `min_p = 0` 在酒馆里表示"这个采样器关掉"，
 * 发出去有供应商会拒；`seed = -1` 表示随机），以及 **导入时绝不自动启用**。
 */
class SillyTavernSamplingImportTest {

    /** 贴近实测那份预设的顶层值。 */
    private val preset = """
    {
      "name": "带采样参数的预设",
      "temperature": 1,
      "frequency_penalty": 0,
      "presence_penalty": 0,
      "top_p": 0.95,
      "top_k": 0,
      "top_a": 1,
      "min_p": 0,
      "repetition_penalty": 1,
      "openai_max_context": 2000000,
      "openai_max_tokens": 65535,
      "seed": -1,
      "reasoning_effort": "max",
      "prompts": [{"identifier":"jb","name":"破限","role":"system","content":"放开限制","injection_position":0,"injection_depth":0,"enabled":true}]
    }
    """.trimIndent()

    @Test
    fun `顶层采样参数被解析出来`() {
        val sampling = SillyTavernPresetImporter.import(preset).sampling
        assertEquals(1f, sampling.temperature)
        assertEquals(0.95f, sampling.topP)
        assertEquals(1f, sampling.repetitionPenalty)
        assertEquals(65535, sampling.maxTokens)
        assertEquals(2_000_000, sampling.maxContextTokens)
        assertEquals("max", sampling.reasoningEffort)
    }

    @Test
    fun `中性的采样值不搬`() {
        val sampling = SillyTavernPresetImporter.import(preset).sampling
        // top_k = 0 / min_p = 0 表示关掉这个采样器，不是"取零"
        assertNull(sampling.topK)
        assertNull(sampling.minP)
        // seed = -1 表示随机
        assertNull(sampling.seed)
    }

    @Test
    fun `导入时绝不自动启用采样开关`() {
        val sampling = SillyTavernPresetImporter.import(preset, enabledByDefault = true).sampling
        assertFalse(sampling.applySampling)
        assertTrue(sampling.hasSampling)
    }

    @Test
    fun `没有采样参数的预设得到空设置`() {
        val sampling = SillyTavernPresetImporter.import(
            """{"name":"x","prompts":[{"identifier":"jb","name":"破限","role":"system","content":"x","enabled":true}]}"""
        ).sampling
        assertFalse(sampling.hasSampling)
        assertNull(sampling.maxContextTokens)
        assertNull(sampling.temperature)
    }

    @Test
    fun `正的 seed 会被保留`() {
        val sampling = SillyTavernPresetImporter.import(
            """{"name":"x","seed":12345,"prompts":[{"identifier":"jb","name":"j","role":"system","content":"x","enabled":true}]}"""
        ).sampling
        assertEquals(12345L, sampling.seed)
    }
}
