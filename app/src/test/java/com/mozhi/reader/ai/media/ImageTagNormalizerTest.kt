package com.mozhi.reader.ai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 标签清洗。旧的 `ImagePromptComposerTest` 里那条「NovelAI 指令必须含 Danbooru」
 * 断言的是**已被删除的两段式提示词生成**，随死代码一并移除；规范化用例保留并扩充。
 */
class ImageTagNormalizerTest {

    @Test
    fun `把模型输出规范成逗号分隔的短标签`() {
        val tags = ImageTagNormalizer.normalize(
            "```\nTags: masterpiece, 1girl\nreading_book, warm light.\n```"
        )
        assertEquals("masterpiece, 1girl, reading_book, warm light", tags)
        assertFalse(tags.contains("```"))
    }

    @Test
    fun `去重并去掉中文顿号等尾标点`() {
        assertEquals(
            "long silver hair, red eyes",
            ImageTagNormalizer.normalize("long silver hair, red eyes, long silver hair")
        )
        assertEquals("a, b", ImageTagNormalizer.normalize("a、b。"))
    }

    @Test
    fun `空输入返回空串`() {
        assertEquals("", ImageTagNormalizer.normalize("   "))
        assertEquals("", ImageTagNormalizer.normalize("Tags:"))
    }
}
