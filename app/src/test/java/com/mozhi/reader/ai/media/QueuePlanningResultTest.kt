package com.mozhi.reader.ai.media

import com.mozhi.reader.core.database.entity.IllustrationQueueEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量编排的结果与提示语。
 *
 * 背景：编排是**按章循环**的。第二轮把 `plan()` 从"静默降级"改成"抛错"之后，
 * 单章失败会把整批预览一起带走——[QueuePlanningResult] 就是为了兜住这件事。
 * 这里钉住的是"用户看得到哪些成了、哪些没成"，以及全成功时不打扰。
 */
class QueuePlanningResultTest {

    private fun row(chapterIndex: Int) = IllustrationQueueEntity(
        id = "q" + chapterIndex,
        bookId = 1L,
        chapterIndex = chapterIndex,
        sourceText = "正文",
        recipeJson = "{}",
        createdAt = 0L
    )

    private fun failure(chapterIndex: Int) = QueuePlanningResult.ChapterFailure(chapterIndex, "画面规划失败")

    @Test
    fun `全部成功时不给提示`() {
        val result = QueuePlanningResult(rows = listOf(row(0), row(1)), failed = emptyList())
        assertNull(result.summary())
    }

    @Test
    fun `部分失败时同时说清成功数与失败章号`() {
        val result = QueuePlanningResult(rows = (0 until 48).map(::row), failed = listOf(failure(2), failure(16)))
        val summary = result.summary()!!
        assertTrue(summary.contains("已编排 48 章"))
        // 章号按人读的 1 起：内部下标 2/16 → 第 3/17 章
        assertTrue(summary.contains("第 3、17 章"))
        assertTrue(summary.contains("单独重试"))
    }

    @Test
    fun `失败章号过多时只列前五个并给出总数`() {
        val result = QueuePlanningResult(rows = listOf(row(0)), failed = (0 until 12).map(::failure))
        val summary = result.summary()!!
        assertTrue(summary.contains("第 1、2、3、4、5"))
        assertTrue(summary.contains("等 12 章"))
    }

    @Test
    fun `一章都没成时给出可操作的话`() {
        val result = QueuePlanningResult(rows = emptyList(), failed = listOf(failure(0)))
        val summary = result.summary()!!
        assertTrue(summary.contains("一章都没编排出来"))
        assertTrue(summary.contains("生图提示词"))
        assertTrue(result.isEmpty)
    }

    @Test
    fun `没有失败也没有成功时不算有内容`() {
        assertTrue(QueuePlanningResult().isEmpty)
        assertNull(QueuePlanningResult().summary())
    }

    @Test
    fun `失败章号不重复展示`() {
        val result = QueuePlanningResult(rows = listOf(row(0)), failed = listOf(failure(3)))
        assertEquals("已编排 1 章；第 4 章没编排出来，可以单独重试。", result.summary())
    }
}
