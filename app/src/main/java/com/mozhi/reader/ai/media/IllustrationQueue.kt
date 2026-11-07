package com.mozhi.reader.ai.media

import androidx.room.withTransaction
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.IllustrationQueueEntity
import com.mozhi.reader.core.di.ApplicationScope
import com.mozhi.reader.core.library.ImageConsistencyRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 批量编排的结果。
 *
 * **为什么不是"失败就抛"**：编排是**按章循环**的，中间某一章规划失败（模型抽风、返回无法解析）
 * 如果直接抛出去，用户已经排好的几十章会一起消失——而失败往往只发生在个别章节上。
 * 所以逐章兜住、把失败章号带回去，让用户看到"哪些成了、哪些没有"，而不是一片空白。
 *
 * 生成阶段（[IllustrationQueue.start]）本来就是逐行 try/catch 的，这里是补上编排阶段的对称处理。
 */
data class QueuePlanningResult(
    val rows: List<IllustrationQueueEntity> = emptyList(),
    val failed: List<ChapterFailure> = emptyList()
) {
    data class ChapterFailure(val chapterIndex: Int, val reason: String)

    val isEmpty: Boolean get() = rows.isEmpty()

    /** 给界面用的一句话；全成功时返回 null（不打扰）。章号按**人读的 1 起**显示。 */
    fun summary(): String? {
        if (failed.isEmpty()) return null
        if (rows.isEmpty()) {
            return "这次一章都没编排出来（" + failed.size + " 章失败）。先确认「生图提示词」模型可用，再重试。"
        }
        val shown = failed.take(5).joinToString("、") { (it.chapterIndex + 1).toString() }
        val label = if (failed.size > 5) "第 " + shown + " 等 " + failed.size + " 章" else "第 " + shown + " 章"
        return "已编排 " + rows.size + " 章；" + label + "没编排出来，可以单独重试。"
    }
}

/** Durable per-chapter checkpoints. Pausing finishes the paid request already in flight. */
@Singleton
class IllustrationQueue @Inject constructor(
    private val database: MoReadDatabase,
    private val library: LibraryRepository,
    private val recipes: ImageConsistencyRepository,
    private val media: AiMediaGenerationService,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val active = ConcurrentHashMap.newKeySet<Long>()
    private val stop = ConcurrentHashMap.newKeySet<Long>()
    val running = MutableStateFlow<Set<Long>>(emptySet())
    private val dao get() = database.imageConsistencyDao()

    /**
     * 逐章编排。**单章失败不影响其它章**——规划失败只记进 [QueuePlanningResult.failed]，
     * 不把整批抛掉（见 [QueuePlanningResult] 的说明）。
     */
    suspend fun prepare(bookId: Long, first: Int, last: Int): QueuePlanningResult {
        require(first >= 0 && last >= first && last - first < 100) { "每次请选择 1–100 章" }
        val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: error("书籍正文已移除")
        val boundary = ReadingScope.uptoProgress(book)
        require(last <= boundary.maxChapterIndex) { "只能为已读章节生成插图" }
        val time = System.currentTimeMillis()
        val rows = mutableListOf<IllustrationQueueEntity>()
        val failed = mutableListOf<QueuePlanningResult.ChapterFailure>()
        library.getChapters(bookId).filter { it.chapterIndex in first..last }.forEach { chapter ->
            currentCoroutineContext().ensureActive()
            val source = boundary.readableText(chapter.chapterIndex, library.readChapterText(bookId, chapter)).take(12_000)
            if (source.isBlank()) return@forEach
            try {
                rows += IllustrationQueueEntity(
                    UUID.randomUUID().toString(), bookId, chapter.chapterIndex, source,
                    recipes.plan(bookId, chapter.chapterIndex, source, useReferences = true).encode(),
                    createdAt = time
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed += QueuePlanningResult.ChapterFailure(
                    chapterIndex = chapter.chapterIndex,
                    reason = error.message.orEmpty().take(200)
                )
            }
        }
        return QueuePlanningResult(rows = rows, failed = failed)
    }

    suspend fun enqueue(rows: List<IllustrationQueueEntity>) {
        require(rows.isNotEmpty())
        check(rows.none { it.bookId in active }) { "请先暂停当前队列" }
        dao.saveQueue(rows)
    }

    fun pause(bookId: Long) { stop.add(bookId) }

    fun start(bookId: Long, onlyId: String? = null) {
        if (!active.add(bookId)) return
        stop.remove(bookId)
        running.update { it + bookId }
        scope.launch {
            try {
                for (row in dao.queue(bookId).filter { it.status == "pending" && (onlyId == null || it.id == onlyId) }) {
                    if (bookId in stop) break
                    val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: break
                    if (row.chapterIndex > book.maxReachedChapterIndex) break
                    dao.saveQueue(row.copy(status = "running", error = ""))
                    try {
                        val chapter = library.getChapter(bookId, row.chapterIndex) ?: error("原文章节已变化，请重新编排")
                        val readable = ReadingScope.uptoProgress(book).readableText(row.chapterIndex, library.readChapterText(bookId, chapter))
                        require(readable.startsWith(row.sourceText)) { "正文或已读范围已变化，请重新编排这一章" }
                        val recipe = ImageRecipeCodec.decode(row.recipeJson) ?: error("配方无法读取，请重新编排")
                        val generated = media.generateIllustration(bookId, row.chapterIndex, null, row.sourceText,
                            recipe.shot.text(), null, persist = false, recipe = recipe,
                            beforePaidRequest = {
                                library.getBook(bookId)?.let { current -> current.removedAt == 0L && current.textVersion == book.textVersion &&
                                    ReadingScope.uptoProgress(current).allowsPosition(row.chapterIndex, row.sourceText.length) } == true
                            })
                        withContext(NonCancellable) {
                            database.withTransaction {
                                val id = database.illustrationDao().insert(generated)
                                dao.saveQueue(row.copy(status = "done", illustrationId = id, error = ""))
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { dao.saveQueue(row.copy(status = "failed", error = error.message.orEmpty().take(500))) }
                }
            } finally {
                active.remove(bookId)
                stop.remove(bookId)
                running.update { it - bookId }
            }
        }
    }

    suspend fun retry(row: IllustrationQueueEntity) {
        check(row.bookId !in active) { "请先暂停队列" }
        require(row.status == "failed" || row.status == "running")
        dao.saveQueue(row.copy(status = "pending", error = ""))
        start(row.bookId, onlyId = row.id)
    }
}
