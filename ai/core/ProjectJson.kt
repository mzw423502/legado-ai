/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai.core

object ProjectJson {
    fun encode(p: Project): String { p.validate(); return Json.stringify(projectMap(p)) }
    fun decode(text: String): Project {
        val o = Json.parse(text).obj()
        require(o.num("schema") == 1L) { "不支持此项目文件版本" }
        val p = Project(schema = 1, id = o.str("id"), title = o.str("title"), settings = settings(o["settings"].obj()),
            createdAt = o.num("createdAt"), updatedAt = o.num("updatedAt"), revision = o.num("revision"),
            chapters = o.list("chapters").map { chapter(it.obj()) }, seedMemory = o.str("seedMemory"),
            draft = o["draft"]?.let { draft(it.obj()) }, archives = o.list("archives").map { archive(it.obj()) },
            receipts = o.list("receipts").map { receipt(it.obj()) }, activeRun = o["activeRun"]?.let { run(it.obj()) },
            pendingPublish = o.bool("pendingPublish"), deletedAt = o.nullLong("deletedAt"), status = o.str("status"))
        p.validate(); return p
    }
    fun encodeBackup(p: Project): String = encode(p.copy(activeRun = null,
        draft = p.draft?.let { it.copy(needsReview = it.needsReview || it.stage == DraftStage.BODY) }, status = "备份快照；恢复后不自动运行"))
    private fun projectMap(p: Project): Map<String, Any?> = linkedMapOf(
        "schema" to p.schema, "id" to p.id, "title" to p.title, "settings" to settingsMap(p.settings),
        "createdAt" to p.createdAt, "updatedAt" to p.updatedAt, "revision" to p.revision,
        "chapters" to p.chapters.map(::chapterMap), "seedMemory" to p.seedMemory,
        "draft" to p.draft?.let(::draftMap), "archives" to p.archives.map(::archiveMap),
        "receipts" to p.receipts.map(::receiptMap), "activeRun" to p.activeRun?.let(::runMap),
        "pendingPublish" to p.pendingPublish, "deletedAt" to p.deletedAt, "status" to p.status)
    private fun settingsMap(s: NovelSettings) = mapOf("bible" to s.bible, "outline" to s.outline,
        "director" to s.director, "targetChars" to s.targetChars, "contextTokens" to s.contextTokens,
        "outputTokens" to s.outputTokens, "recentChapters" to s.recentChapters, "maxParts" to s.maxParts)
    private fun settings(o: Map<String, Any?>) = NovelSettings(bible = o.str("bible"), outline = o.str("outline"),
        director = o.str("director"), targetChars = o.int("targetChars", 4000),
        contextTokens = o.int("contextTokens", 128000), outputTokens = o.int("outputTokens", 16384),
        recentChapters = o.int("recentChapters", 3), maxParts = o.int("maxParts", 8))
    private fun draftMap(d: Draft) = mapOf("chapterId" to d.chapterId, "ordinal" to d.ordinal,
        "stage" to d.stage.name, "plan" to d.plan, "body" to d.body, "memoryAfter" to d.memoryAfter,
        "completedParts" to d.completedParts, "needsReview" to d.needsReview, "note" to d.note,
        "memoryProgress" to d.memoryProgress?.let(MemoryJson::progressMap))
    private fun draft(o: Map<String, Any?>) = Draft(chapterId = o.str("chapterId"), ordinal = o.int("ordinal"),
        stage = DraftStage.valueOf(o.str("stage")), plan = o.str("plan"), body = o.str("body"),
        memoryAfter = o.str("memoryAfter"), completedParts = o.int("completedParts"),
        needsReview = o.bool("needsReview"), note = o.str("note"),
        memoryProgress = o["memoryProgress"]?.let { MemoryJson.progress(it.obj()) })
    private fun chapterMap(c: Chapter) = mapOf("id" to c.id, "ordinal" to c.ordinal, "title" to c.title,
        "content" to c.content, "memoryAfter" to c.memoryAfter, "createdAt" to c.createdAt,
        "memoryV2" to c.memoryV2?.let(MemoryJson::chapterMap), "authorNote" to c.authorNote, "arcSummary" to c.arcSummary)
    private fun chapter(o: Map<String, Any?>) = Chapter(o.str("id"), o.int("ordinal"), o.str("title"),
        o.str("content"), o.str("memoryAfter"), o.num("createdAt"),
        o["memoryV2"]?.let { MemoryJson.chapter(it.obj()) }, o.str("authorNote"), o.str("arcSummary"))
    private fun archiveMap(a: ArchivedTail) = mapOf("id" to a.id, "fromOrdinal" to a.fromOrdinal,
        "prefixIds" to a.prefixIds, "chapters" to a.chapters.map(::chapterMap),
        "draft" to a.draft?.let(::draftMap), "createdAt" to a.createdAt)
    private fun archive(o: Map<String, Any?>) = ArchivedTail(id = o.str("id"), fromOrdinal = o.int("fromOrdinal"),
        prefixIds = o.list("prefixIds").map { it as String }, chapters = o.list("chapters").map { chapter(it.obj()) },
        draft = o["draft"]?.let { draft(it.obj()) }, createdAt = o.num("createdAt"))
    private fun receiptMap(r: Receipt) = mapOf("id" to r.id, "runId" to r.runId, "purpose" to r.purpose.name,
        "reserved" to r.reserved, "counted" to r.counted, "status" to r.status.name,
        "startedAt" to r.startedAt, "completedAt" to r.completedAt)
    private fun receipt(o: Map<String, Any?>) = Receipt(o.str("id"), o.str("runId"), Purpose.valueOf(o.str("purpose")),
        o.num("reserved"), o.num("counted"), ReceiptStatus.valueOf(o.str("status")),
        o.num("startedAt"), o.nullLong("completedAt"))
    private fun runMap(r: ActiveRun) = mapOf("id" to r.id, "startedAt" to r.startedAt,
        "initialChapterCount" to r.initialChapterCount, "limits" to limitsMap(r.limits))
    private fun run(o: Map<String, Any?>) = ActiveRun(o.str("id"), limits(o["limits"].obj()),
        o.num("startedAt"), o.int("initialChapterCount"))
    private fun limitsMap(l: RunLimits) = mapOf("mode" to l.mode.name, "targetChapter" to l.targetChapter,
        "requestLimit" to l.requestLimit, "tokenLimit" to l.tokenLimit, "maxMinutes" to l.maxMinutes,
        "minBattery" to l.minBattery)
    private fun limits(o: Map<String, Any?>) = RunLimits(RunMode.valueOf(o.str("mode")),
        o.int("targetChapter"), o.int("requestLimit"), o.num("tokenLimit"), o.int("maxMinutes"),
        o.int("minBattery")).also { it.validate() }
    private fun Map<String, Any?>.list(key: String): List<Any?> = if (containsKey(key)) get(key).arr() else emptyList()
    private fun Map<String, Any?>.int(key: String, default: Int = 0): Int = num(key, default.toLong()).also {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "字段 $key 超出整数范围" }
    }.toInt()
    private fun Map<String, Any?>.nullLong(key: String): Long? = if (get(key) == null) null else num(key)
}
