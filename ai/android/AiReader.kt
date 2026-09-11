/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.legado.app.ai.core.*
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.postEvent
import java.io.File
import java.io.FileOutputStream

/** AI writes files; normal reading never invokes a completion endpoint. */
class AiReader(private val context: Context) : ReaderPort {
    private val root = File(context.filesDir, "ai_reading").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    fun path(id: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        return File(root, "$id.txt")
    }
    fun url(id: String) = Uri.fromFile(path(id)).toString()
    fun projectId(bookUrl: String?): String? {
        if (bookUrl.isNullOrBlank()) return null
        return runCatching {
            val uri = Uri.parse(bookUrl)
            if (uri.scheme != "file") return null
            val f = File(uri.path ?: return null)
            if (f.parentFile?.canonicalPath != root.canonicalPath) return null
            f.name.removeSuffix(".txt").takeIf { f.name.endsWith(".txt") && it.matches(Regex("[0-9a-fA-F-]{36}")) }
        }.getOrNull()
    }
    override fun publish(project: Project) {
        require(project.deletedAt == null)
        if (project.chapters.isEmpty()) { remove(project.id); return }
        val target = path(project.id); val temp = File(target.path + ".pending")
        val bookUrl = url(project.id)
        val chapters = ArrayList<BookChapter>()
        var offset = 0L
        FileOutputStream(temp).use { output ->
            project.chapters.forEachIndexed { index, c ->
                val title = if (c.title.startsWith("第")) c.title else "第${index + 1}章 ${c.title}"
                val heading = (title + "\n").toByteArray(Charsets.UTF_8)
                val content = (c.content.trim() + "\n\n").toByteArray(Charsets.UTF_8)
                output.write(heading); offset += heading.size
                chapters += BookChapter(url = "ai-chapter:${c.id}", title = title, bookUrl = bookUrl,
                    baseUrl = bookUrl, index = index, start = offset, end = offset + content.size,
                    wordCount = "${textLength(c.content)}字")
                output.write(content); offset += content.size
            }
            output.fd.sync()
        }
        check(temp.renameTo(target)) { "更新阅读文件失败；原章节仍保留在 AI 存档中，可重新同步" }
        TextFile.clear()
        var saved: Book? = null
        appDb.runInTransaction {
            val old = appDb.bookDao.getBook(bookUrl)
            val pIndex = old?.durChapterIndex?.coerceIn(0, chapters.lastIndex) ?: 0
            val pPos = if (old != null && old.durChapterIndex == pIndex) old.durChapterPos else 0
            val b = (old ?: Book(bookUrl = bookUrl, author = "AI创作 · ${project.id.take(8)}")).copy(
                name = project.title, origin = BookType.localTag, originName = target.name,
                charset = "UTF-8", type = BookType.text or BookType.local, canUpdate = false,
                customTag = "AI创作", intro = "本机 AI 创作小说。通过阅读菜单的 AI 创作管理设定、版本和连载。",
                latestChapterTitle = chapters.last().title, latestChapterTime = maxOf(System.currentTimeMillis(), target.lastModified()),
                lastCheckTime = System.currentTimeMillis(), totalChapterNum = chapters.size,
                lastCheckCount = (chapters.size - (old?.totalChapterNum ?: 0)).coerceAtLeast(0),
                durChapterIndex = pIndex, durChapterPos = pPos, durChapterTitle = chapters[pIndex].title,
                wordCount = "${project.chapters.sumOf { textLength(it.content) }}字")
            if (old == null) appDb.bookDao.insert(b) else appDb.bookDao.update(b)
            appDb.bookChapterDao.delByBook(bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            saved = b
        }
        saved?.let { book -> main.post {
            if (ReadBook.book?.bookUrl == book.bookUrl) ReadBook.onChapterListUpdated(book, false)
            postEvent(EventBus.UP_BOOKSHELF, true)
        } }
    }
    override fun remove(projectId: String) {
        val bookUrl = url(projectId)
        appDb.bookDao.getBook(bookUrl)?.let { b ->
            appDb.bookDao.delete(b); BookHelp.clearCache(b)
        }
        path(projectId).let { check(!it.exists() || it.delete()) { "阅读文件暂时无法移除；可重新同步" } }
        File(path(projectId).path + ".pending").delete()
        TextFile.clear()
        main.post {
            if (ReadBook.book?.bookUrl == bookUrl) ReadBook.book = null
            postEvent(EventBus.UP_BOOKSHELF, true)
        }
    }
    override fun open(projectId: String) {
        val bookUrl = url(projectId)
        require(appDb.bookDao.getBook(bookUrl)?.totalChapterNum ?: 0 > 0) { "还没有正式章节，请先完成一章" }
        main.post { context.startActivity(Intent(context, ReadBookActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("bookUrl", bookUrl)) }
    }
    /** On explicit directory refresh, use exact chapter boundaries instead of reparsing AI text. */
    fun managedChapters(book: Book): ArrayList<BookChapter>? {
        projectId(book.bookUrl) ?: return null
        return ArrayList(appDb.bookChapterDao.getChapterList(book.bookUrl))
    }
    fun originals(bookUrl: String, through: Int): Pair<Book, List<Chapter>> {
        val book = appDb.bookDao.getBook(bookUrl) ?: error("原书不在书架，请先加入书架")
        val toc = appDb.bookChapterDao.getChapterList(bookUrl)
        require(through in 1..toc.size) { "请选择已有章节范围" }
        var bytes = 0L
        val chapters = toc.take(through).filterNot { it.isVolume }.mapIndexed { i, c ->
            val text = BookHelp.getContent(book, c)?.takeIf { it.isNotBlank() }
                ?: error("第 ${c.index + 1} 章尚未缓存；请先用原阅读器下载，再建立续写副本")
            require(!text.startsWith("获取本地书籍内容失败")) { "原书正文读取失败，请先检查原阅读器" }
            bytes += text.toByteArray(Charsets.UTF_8).size
            require(bytes <= 20L * 1024 * 1024) { "本次复制超过 20 MiB，请缩小章节范围" }
            Chapter(ordinal = i + 1, title = c.title, content = text, memoryAfter = "", createdAt = System.currentTimeMillis())
        }
        return book to chapters
    }
}
