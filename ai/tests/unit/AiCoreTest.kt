/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import io.legado.app.ai.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AiCoreTest {
    private class Reader : ReaderPort {
        val books = linkedMapOf<String, Project>(); var writes = 0
        override fun publish(project: Project) { writes++; books[project.id] = project }
        override fun remove(projectId: String) { books.remove(projectId) }
        override fun open(projectId: String) { require(books.containsKey(projectId)) }
    }
    private class Model : CompletionGateway {
        val calls = mutableListOf<CompletionRequest>()
        var hook: ((CompletionRequest, Cancellation, (String) -> Unit) -> CompletionResult)? = null
        fun answer(r: CompletionRequest) = CompletionResult(when (r.purpose) {
            Purpose.PLAN -> "章节卡：寻找递信人；线索是铜扣；不提前揭露幕后人物。"
            Purpose.BODY -> "第1章 雨夜\n" + "雨水落在码头上，林砚收起信，决定先问清摆渡人的来历。".repeat(30)
            Purpose.MEMORY -> "人物仍在码头调查，尚不知道信件来源。伏笔 F01 为铜扣上的三道刻痕，未回收；时间为入夜至退潮前。"
            else -> "连接成功"
        }, "stop", 100, 200)
        override fun complete(r: CompletionRequest, c: Cancellation, partial: (String) -> Unit): CompletionResult {
            calls += r; return hook?.invoke(r, c, partial) ?: answer(r).also { partial(it.content) }
        }
    }
    private class F {
        val store = MemoryStore(); val reader = Reader(); val model = Model()
        val actions = ProjectActions(store, reader)
        val settings = NovelSettings(bible = "世界、人设与创作规则。".repeat(400), targetChars = 500)
        val p = actions.create("测试小说", settings)
        val engine = NovelEngine(store, model, reader)
        fun get() = store.get(p.id)!!
        fun run(limits: RunLimits = RunLimits()) = engine.run(p.id, limits)
    }
    private fun bad(block: () -> Unit) { try { block(); fail("expected rejection") } catch (_: Exception) {} }
    @Test fun fullBibleIsPreserved() {
        val f = F(); assertTrue(textLength(f.settings.bible) > 3000)
        assertTrue(ContextComposer.build(f.p, Purpose.PLAN).messages[0].content.endsWith(f.settings.bible))
    }
    @Test fun singleChapterPublishesOnlyAfterMemory() {
        val f = F(); f.run(); assertEquals(3, f.model.calls.size); assertEquals(1, f.get().chapters.size)
        assertEquals(1, f.reader.writes); assertNull(f.get().draft); assertNull(f.get().activeRun)
    }
    @Test fun chapterTitleNotDuplicatedInBody() {
        val f = F(); f.run(); assertEquals("第1章 雨夜", f.get().chapters[0].title)
        assertFalse(f.get().chapters[0].content.startsWith("第1章"))
    }
    @Test fun noPaidRequestForReadingOrSync() {
        val f = F(); f.run(); f.reader.open(f.p.id); f.engine.synchronizeReader(f.p.id); assertEquals(3, f.model.calls.size)
    }
    @Test fun exactChapterTargetAndAlreadyReached() {
        val f = F(); val l = RunLimits(RunMode.TO_CHAPTER, 2); f.run(l); f.run(l)
        assertEquals(2, f.get().chapters.size); assertEquals(6, f.model.calls.size)
    }
    @Test fun continuousStillHasRequestCap() {
        val f = F(); f.run(RunLimits(mode = RunMode.CONTINUOUS, requestLimit = 4))
        assertEquals(4, f.model.calls.size); assertEquals(1, f.get().chapters.size)
    }
    @Test fun reserveBudgetBeforeRequest() {
        val f = F(); f.run(RunLimits(tokenLimit = 1024)); assertTrue(f.model.calls.isEmpty()); assertTrue(f.get().receipts.isEmpty())
    }
    @Test fun reportedUsageReplacesReservation() {
        val f = F(); f.run(); assertEquals(900L, f.get().receipts.sumOf { it.counted })
        assertTrue(f.get().receipts.all { it.status == ReceiptStatus.REPORTED })
    }
    @Test fun failuresDoNotAutomaticallyRetry() {
        val f = F(); f.model.hook = { _, _, _ -> throw java.io.IOException("interrupted") }; f.run()
        assertEquals(1, f.model.calls.size); assertEquals(ReceiptStatus.ESTIMATED, f.get().receipts.single().status)
    }
    @Test fun oversizedBiblePausesWithoutTruncation() {
        val f = F(); f.actions.changeSettings(f.p.id, f.settings.copy(bible = "界".repeat(10000), contextTokens = 8192, outputTokens = 1024))
        f.run(); assertTrue(f.model.calls.isEmpty()); assertEquals(10000, f.get().settings.bible.length)
    }
    @Test fun truncatedPlanNeverStartsBody() {
        val f = F(); f.model.hook = { r, _, _ -> f.model.answer(r).copy(finishReason = "length") }; f.run()
        assertEquals(1, f.model.calls.size); assertTrue(f.get().chapters.isEmpty())
    }
    @Test fun truncatedMemoryPreservesBody() {
        val f = F(); f.model.hook = { r, _, cb -> f.model.answer(r).let { if (r.purpose == Purpose.MEMORY) it.copy(finishReason = "length") else it }.also { cb(it.content) } }
        f.run(); assertTrue(f.get().chapters.isEmpty()); assertTrue(f.get().draft!!.body.isNotBlank())
        assertEquals(DraftStage.MEMORY, f.get().draft!!.stage)
    }
    @Test fun parallelRunRejected() {
        val f = F(); var once = false
        f.model.hook = { r, _, cb -> if (!once) { once = true; bad { f.run() } }; f.model.answer(r).also { cb(it.content) } }
        f.run(); assertEquals(3, f.model.calls.size)
    }
    @Test fun deletionRejectsLateResponse() {
        val f = F(); f.model.hook = { r, _, cb -> f.actions.trash(f.p.id); runCatching { cb("迟到正文") }; f.model.answer(r) }
        f.run(); assertNotNull(f.get().deletedAt); assertTrue(f.get().chapters.isEmpty()); assertFalse(f.reader.books.containsKey(f.p.id))
    }
    @Test fun stopPreservesPartialDraftForReview() {
        val f = F(); f.model.hook = { r, _, cb ->
            if (r.purpose == Purpose.BODY) { cb("尚未完成的正文，必须保留。"); f.actions.stop(f.p.id) }
            f.model.answer(r)
        }
        f.run(); assertTrue(f.get().draft!!.needsReview); assertTrue(f.get().draft!!.body.contains("必须保留"))
    }
    @Test fun rollbackAlsoRollsBackMemory() {
        val f = F(); f.run(RunLimits(RunMode.TO_CHAPTER, 3))
        val memory = f.get().chapters[0].memoryAfter
        f.actions.deleteFrom(f.p.id, 2); assertEquals(1, f.get().chapters.size); assertEquals(memory, f.get().memory())
        assertEquals(2, f.get().archives.single().chapters.size)
    }
    @Test fun restoreOldStoryNeverOverwritesNewStory() {
        val f = F(); f.run(RunLimits(RunMode.TO_CHAPTER, 2)); val old = f.actions.deleteFrom(f.p.id, 2).archives.single()
        f.run(); val newId = f.get().chapters.last().id
        val restored = f.actions.restoreTail(f.p.id, old.id)
        assertNotEquals(f.p.id, restored.id); assertEquals(newId, f.get().chapters.last().id)
    }
    @Test fun restartDoesNotRestartBilling() {
        val f = F(); f.store.update(f.p.id) { it.copy(activeRun = ActiveRun(limits = RunLimits(), startedAt = 0, initialChapterCount = 0)) }
        f.actions.recoverInterrupted(); assertNull(f.get().activeRun); assertTrue(f.model.calls.isEmpty())
    }
    @Test fun backupsNeverRetainAnActiveRun() {
        val f = F(); f.run(); val decoded = ProjectJson.decode(ProjectJson.encodeBackup(f.get()))
        assertNull(decoded.activeRun); assertEquals(f.get().chapters, decoded.chapters)
        val restored = f.actions.restoreBackup(decoded); assertNotEquals(f.p.id, restored.id)
    }
    @Test fun atomicFileRoundTrip() {
        val root = Files.createTempDirectory("ai-core-test").toFile()
        try { val f = F(); val store = FileProjectStore(root); store.create(f.p)
            assertEquals(f.p, store.get(f.p.id)); store.update(f.p.id) { it.copy(status = "更新") }
            assertEquals("更新", FileProjectStore(root).get(f.p.id)!!.status)
        } finally { root.deleteRecursively() }
    }
    @Test fun jsonRejectsDuplicateKeysAndTraversalIds() {
        bad { Json.parse("{\"a\":1,\"a\":2}") }; val f = F(); bad { f.p.copy(id = "../../other").validate() }
        bad { Json.parse("[1,]") }; bad { Json.parse("{\"a\":NaN}") }
    }
    @Test fun apiRequiresHttpsAndRejectsCredentialUrls() {
        bad { ApiAddress.completionUrl("http://example.com") }; bad { ApiAddress.completionUrl("https://key@example.com") }
        bad { ApiAddress.completionUrl("https://example.com?key=value") }
        assertEquals("https://example.com/v1/chat/completions", ApiAddress.completionUrl("https://example.com/v1"))
    }
    @Test fun bodyDoesNotRequestOrExposeReasoning() {
        val f = F(); val payload = Json.parse(CompletionProtocol.payload(ContextComposer.build(f.p, Purpose.BODY), ApiConfig())).obj()
        assertEquals("disabled", payload["thinking"].obj().str("type"))
        var text = ""; val d = CompletionProtocol.StreamDecoder { text = it }
        d.line("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"private\",\"content\":\"正文\"},\"finish_reason\":\"stop\"}]}")
        d.line(""); d.line("data: [DONE]"); d.line("")
        assertEquals("正文", d.result().content); assertFalse(text.contains("private"))
    }
    @Test fun interruptedStreamNeverLooksComplete() {
        val d = CompletionProtocol.StreamDecoder(); d.line("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"半章\"}}]}"); d.line("")
        bad { d.result() }
    }
    @Test fun importedFutureSummaryIsNotAnOpeningMemory() {
        val f = F(); f.run(); val copied = f.actions.continuationCopy("原书", f.settings, f.get().chapters, "原书第1章之后的事实")
        f.actions.deleteFrom(copied.id, 1); assertEquals("", f.store.get(copied.id)!!.memory())
    }
    @Test fun longPrefixWithoutMemoryDoesNotGuess() {
        val f = F(); val chapters = (1..4).map { Chapter(ordinal = it, title = "第${it}章", content = "正文", memoryAfter = "", createdAt = 0) }
        val p = f.p.copy(chapters = chapters); bad { ContextComposer.build(p, Purpose.PLAN) }
    }
}
