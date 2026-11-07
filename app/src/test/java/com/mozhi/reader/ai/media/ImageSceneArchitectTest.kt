package com.mozhi.reader.ai.media

import com.mozhi.reader.ai.knowledge.BookCharacter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 画面规划器：JSON 解析、校验清洗、档案变更合并、协议内容与消息顺序。 */
class ImageSceneArchitectTest {

    private val capabilities = ImageCapabilities(perCharacterPrompt = true, tags = true, vibe = true)

    private fun input(
        target: String = "她推开窗，晚风灌进来。",
        maxImages: Int = 1,
        minImages: Int = 1,
        locked: String? = null,
        capabilities: ImageCapabilities = this.capabilities
    ) = ImageSceneArchitect.Input(
        bookTitle = "测试书",
        chapterTitle = "第一章",
        chapterIndex = 2,
        targetText = target,
        contextText = "",
        people = listOf(BookCharacter(name = "小雪", evidence = emptyList(), sourceName = "小雪")),
        looks = listOf(
            LookSpec(id = "look-1", characterKey = "小雪", name = "小雪", sinceChapter = 0,
                natural = "银发红瞳", tags = "long silver hair, red eyes", source = "text")
        ),
        style = StyleSpec(tags = "watercolor, illustration"),
        capabilities = capabilities,
        maxImages = maxImages,
        minImages = minImages,
        lockedOrientation = locked,
        naturalLanguage = false,
        negativeEnabled = true
    )

    // ---------- 解析 ----------

    @Test
    fun `剥掉 thinking 块后再取 JSON`() {
        val raw = """
            <thinking>
            A. 事实账本：她在窗边
            B. 角色清点：小雪已在库
            </thinking>
            {"images":[{"tag":"1girl, medium shot, window, night, moonlight","characters":[{"name":"小雪","tag":"girl, long silver hair, red eyes, white dress, standing, smile, looking at viewer"}],"negative":"","size":"portrait"}],"changes":[]}
        """.trimIndent()
        val output = SceneArchitectParser.parse(raw, input(), tagsBackend = true, naturalLanguage = false)
        assertEquals(1, output.images.size)
        assertTrue(output.thinking.contains("事实账本"))
        assertEquals(1, output.images.first().characters.size)
        assertEquals("小雪", output.images.first().characters.first().name)
        assertEquals("portrait", output.images.first().orientation)
    }

    @Test
    fun `容忍前后说明文字与代码块围栏`() {
        val raw = """
            好的，这是规划结果：
            ```json
            {"images":[{"tag":"1girl, close-up, rain, cold colors","size":"portrait"}],"changes":[]}
            ```
            以上。
        """.trimIndent()
        val output = SceneArchitectParser.parse(raw, input(), tagsBackend = true, naturalLanguage = false)
        assertEquals("1girl, close-up, rain, cold colors", output.images.first().tag)
    }

    @Test
    fun `字符串里的花括号不会截断 JSON`() {
        val raw = """{"images":[{"tag":"1girl, {{signed}}, holding sign, text","size":"portrait"}],"changes":[]}"""
        val output = SceneArchitectParser.parse(raw, input(), tagsBackend = true, naturalLanguage = false)
        assertEquals(1, output.images.size)
        assertTrue(output.images.first().tag.contains("holding sign"))
    }

    @Test
    fun `NAI 后端丢弃含中文的画面 tag 并留下说明`() {
        val raw = """{"images":[{"tag":"一个女孩站在窗边","size":"portrait"},{"tag":"1girl, window","size":"portrait"}],"changes":[]}"""
        val output = SceneArchitectParser.parse(raw, input(), tagsBackend = true, naturalLanguage = false)
        assertEquals(1, output.images.size)
        assertEquals("1girl, window", output.images.first().tag)
        assertTrue(output.notes.any { it.contains("中文") })
    }

    @Test
    fun `没有可解析的 JSON 直接抛错`() {
        val error = runCatching {
            SceneArchitectParser.parse("我拒绝回答这个问题。", input(), tagsBackend = true, naturalLanguage = false)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("JSON"))
    }

    @Test
    fun `要求至少一张但模型返回空数组时报错`() {
        val error = runCatching {
            SceneArchitectParser.parse("""{"images":[],"changes":[]}""", input(minImages = 1), true, false)
        }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun `minImages 为 0 时允许空画面`() {
        val output = SceneArchitectParser.parse(
            """{"images":[],"changes":[{"name":"小雪","field":"new","value":"hair=short red hair","reason":"剪短"}]}""",
            input(minImages = 0), true, false
        )
        assertTrue(output.images.isEmpty())
        assertEquals(1, output.changes.size)
    }

    @Test
    fun `画面数量按 maxImages 截断`() {
        val raw = """{"images":[{"tag":"a"},{"tag":"b"},{"tag":"c"}],"changes":[]}"""
        val output = SceneArchitectParser.parse(raw, input(maxImages = 2), true, false)
        assertEquals(2, output.images.size)
    }

    @Test
    fun `changes 缺字段的条目被丢弃`() {
        val raw = """{"images":[{"tag":"1girl"}],"changes":[{"name":"","field":"hair","value":"x"},{"name":"小雪","field":"hair","value":"short red hair","reason":"剪短"}]}"""
        val output = SceneArchitectParser.parse(raw, input(), true, false)
        assertEquals(1, output.changes.size)
        assertEquals("short red hair", output.changes.first().value)
    }

    // ---------- 负面词清洗 ----------

    @Test
    fun `通用质量负面词被剔除，画面专用负面词保留`() {
        val notes = mutableListOf<String>()
        val kept = SceneArchitectParser.stripGenericNegative(
            "worst quality, low quality, blurry, lowres, bad anatomy, bad hands, jpeg artifacts, extra people, rain",
            notes
        )
        assertEquals("extra people, rain", kept)
        assertTrue(notes.first().contains("通用质量词"))
    }

    @Test
    fun `负面词全是质量词时返回空串`() {
        assertEquals("", SceneArchitectParser.stripGenericNegative("worst quality, lowres, blurry"))
    }

    @Test
    fun `带花括号权重的质量词也能识别`() {
        assertEquals("", SceneArchitectParser.stripGenericNegative("{worst quality}, {bad anatomy}"))
    }

    // ---------- 档案变更合并 ----------

    @Test
    fun `永久变化写进外貌卡并推进 sinceChapter`() {
        val current = LookSpec(id = "l1", characterKey = "小雪", name = "小雪", sinceChapter = 0,
            natural = "银发; 红瞳", tags = "long silver hair, red eyes", source = "text")
        val change = ImageSceneArchitect.LookChange("小雪", "hair", "short red hair", "此处剪短", 0)
        val updated = SceneArchitectLibrary.applyChanges(current, listOf(change), chapterIndex = 5)
        assertNotNull(updated)
        assertEquals(5, updated!!.sinceChapter)
        assertTrue(updated.tags.contains("short red hair"))
        assertFalse(updated.tags.contains("long silver hair"))
        assertEquals("architect", updated.source)
    }

    @Test
    fun `与自己无关的变更不改动外貌卡`() {
        val current = LookSpec(id = "l1", characterKey = "小雪", name = "小雪", tags = "long silver hair")
        assertNull(SceneArchitectLibrary.applyChanges(current, listOf(ImageSceneArchitect.LookChange("别人", "hair", "x")), 1))
    }

    @Test
    fun `空内容的外貌卡被写入时不会拼出多余分隔符`() {
        val current = LookSpec(id = "l1", characterKey = "小雪", name = "小雪", natural = "", tags = "")
        val updated = SceneArchitectLibrary.applyChanges(current, listOf(ImageSceneArchitect.LookChange("小雪", "eyes", "blue eyes")), 1)
        assertEquals("blue eyes", updated!!.tags)
    }

    // ---------- 协议与消息顺序 ----------

    @Test
    fun `破限内置文本含成人内容条款与执行纪律`() {
        val jailbreak = SceneArchitectPrompts.DEFAULT_JAILBREAK
        assertTrue(jailbreak.contains("成人内容"))
        assertTrue(jailbreak.contains("不因内容露骨"))
        assertTrue(jailbreak.contains("未成年人"))
        assertTrue(jailbreak.contains("只输出协议要求的 JSON"))
    }

    @Test
    fun `NAI 规范含显式场景条款与角色块分工`() {
        val spec = SceneArchitectPrompts.DEFAULT_NAI_SPEC
        assertTrue(spec.contains("显式场景"))
        assertTrue(spec.contains("breasts"))
        assertTrue(spec.contains("characters[]"))
        assertTrue(spec.contains("Base 里不写任何单个角色的外貌"))
        assertTrue(spec.contains("不要写质量词"))
    }

    @Test
    fun `协议按后端形态切换：有角色块时要求 characters，没有时不要求`() {
        val perCharacter = SceneArchitectPrompts.protocol(input(), tagsBackend = true, perCharacterPrompts = true,
            naturalLanguage = false, negativeEnabled = true)
        assertTrue(perCharacter.contains("characters"))
        assertTrue(perCharacter.contains("negative"))
        val flat = SceneArchitectPrompts.protocol(input(), tagsBackend = true, perCharacterPrompts = false,
            naturalLanguage = false, negativeEnabled = false)
        assertFalse(flat.contains("每个可见角色各写一个 characters[] 块"))
        assertFalse(flat.contains("negative 是本画面"))
    }

    @Test
    fun `锁定画幅时协议直接指定 size`() {
        val locked = SceneArchitectPrompts.protocol(input(locked = "landscape"), true, true, false, true)
        assertTrue(locked.contains("锁定为「landscape」"))
    }

    @Test
    fun `消息顺序固定为 破限 规范 思维链 协议 正文`() {
        val settings = com.mozhi.reader.core.media.ImageApiSettings()
        val messages = SceneArchitectPrompts.buildMessages(input(), settings, tagsBackend = true,
            perCharacterPrompts = true, naturalLanguage = false, negativeEnabled = true)
        assertEquals(5, messages.size)
        assertTrue(messages[0].content.contains("image_core"))
        assertTrue(messages[1].content.contains("NovelAI 提示词规范"))
        assertTrue(messages[2].content.contains("输出前思考清单"))
        assertTrue(messages[3].content.contains("规则："))
        assertTrue(messages[4].content.contains("目标正文"))
        assertTrue(messages[4].content.contains("她推开窗"))
    }

    @Test
    fun `设置里的自定义破限覆盖内置默认`() {
        val settings = com.mozhi.reader.core.media.ImageApiSettings(jailbreakPrompt = "自定义破限文本")
        val messages = SceneArchitectPrompts.buildMessages(input(), settings, true, true, false, true)
        assertEquals("自定义破限文本", messages[0].content)
    }

    @Test
    fun `外观库为空时明确告诉模型必须建档`() {
        val content = SceneArchitectPrompts.buildMessages(
            input().copy(looks = emptyList()), com.mozhi.reader.core.media.ImageApiSettings(), true, true, false, true
        ).last().content
        assertTrue(content.contains("当前为空"))
        assertTrue(content.contains("必须通过 changes 建档"))
    }

    @Test
    fun `外观库非空时逐条列出 tags 与分项`() {
        val content = SceneArchitectPrompts.buildMessages(
            input(), com.mozhi.reader.core.media.ImageApiSettings(), true, true, false, true
        ).last().content
        assertTrue(content.contains("long silver hair, red eyes"))
        assertTrue(content.contains("key=小雪"))
    }

}
