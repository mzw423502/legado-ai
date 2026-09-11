/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai.core

class NovelEngine(private val store: ProjectStore, private val gateway: CompletionGateway,
    private val reader: ReaderPort, private val clock: Clock = SystemClock,
    private val device: DeviceConditions = UnrestrictedDevice) {
    fun run(projectId: String, limits: RunLimits, cancellation: Cancellation = Cancellation()): Project {
        limits.validate()
        val started = store.update(projectId) { p ->
            check(p.deletedAt == null) { "小说已在回收站" }
            check(p.activeRun == null) { "这本小说已有任务在运行" }
            if (p.draft?.needsReview == true) throw Paused("中断草稿需要先选择：接着写、手动收录或丢弃。")
            p.settings.validate()
            p.copy(activeRun = ActiveRun(limits = limits, startedAt = clock.nowMillis(), initialChapterCount = p.chapters.size), status = "准备创作")
        }
        val lease = started.activeRun!!
        try {
            synchronizeReader(projectId, lease.id)
            while (true) {
                cancellation.check()
                val p = active(projectId, lease.id)
                if (done(p, lease)) break
                ensureConditions(p, lease)
                if (p.draft == null) edit(projectId, lease.id) {
                    it.copy(draft = Draft(ordinal = it.chapters.size + 1), status = "规划下一章")
                }
                createChapter(projectId, lease.id, cancellation)
                synchronizeReader(projectId, lease.id)
                maybeArcSummary(projectId, lease.id, cancellation)
            }
            stopStatus(projectId, lease.id, "本轮创作完成")
        } catch (e: Exception) {
            val text = if (e is Cancelled) "已停止；未完成草稿已保留" else e.message ?: "创作已暂停"
            stopStatus(projectId, lease.id, text, true)
        } finally {
            cancellation.clear(); stopStatus(projectId, lease.id, null)
        }
        return store.get(projectId) ?: throw IllegalStateException("项目已彻底删除")
    }
    private fun done(p: Project, r: ActiveRun): Boolean = when (r.limits.mode) {
        RunMode.ONE_CHAPTER -> p.chapters.size > r.initialChapterCount
        RunMode.TO_CHAPTER -> p.chapters.size >= r.limits.targetChapter
        RunMode.CONTINUOUS -> false
    }
    private fun active(id: String, run: String): Project {
        val p = store.get(id) ?: throw Cancelled()
        if (p.deletedAt != null || p.activeRun?.id != run) throw Cancelled()
        return p
    }
    private fun edit(id: String, run: String, change: (Project) -> Project): Project = store.update(id) { p ->
        if (p.deletedAt != null || p.activeRun?.id != run) throw Cancelled()
        change(p).copy(updatedAt = clock.nowMillis())
    }
    private fun ensureConditions(p: Project, run: ActiveRun) {
        if (clock.nowMillis() - run.startedAt >= run.limits.maxMinutes * 60000L)
            throw Paused("达到本轮时间上限；已停止新请求")
        device.pauseReason(run.limits)?.let { throw Paused(it) }
        val receipts = p.receipts.filter { it.runId == run.id }
        if (receipts.size >= run.limits.requestLimit) throw Paused("达到本轮请求次数上限")
        if (receipts.sumOf { it.counted } >= run.limits.tokenLimit) throw Paused("达到本轮 Token 上限")
    }
    private fun request(id: String, run: String, purpose: Purpose, cancellation: Cancellation,
        partial: (Project, String) -> Project): CompletionResult {
        cancellation.check()
        val p = active(id, run); val lease = p.activeRun!!
        ensureConditions(p, lease)
        val req = ContextComposer.build(p, purpose)
        val reservation = req.inputTokenUpperBound + req.maxTokens
        edit(id, run) { current ->
            ensureConditions(current, lease)
            val counted = current.receipts.filter { it.runId == run }.sumOf { it.counted }
            if (counted + reservation > lease.limits.tokenLimit)
                throw Paused("剩余 Token 预算不足以安全发起下一次请求；未发出请求")
            current.copy(receipts = current.receipts + Receipt(req.requestId, run, purpose, reservation, startedAt = clock.nowMillis()))
        }
        try {
            cancellation.check()
            val response = gateway.complete(req, cancellation) { text ->
                cancellation.check(); edit(id, run) { current -> partial(current, text) }
            }
            settle(id, req.requestId, response.reportedTokens())
            cancellation.check(); active(id, run)
            if (response.content.isBlank() && purpose != Purpose.MEMORY)
                throw Paused("接口未返回正文；未创建空白章节")
            if (response.finishReason !in setOf("stop", "length"))
                throw Paused("接口未正常结束（${response.finishReason}）；草稿未自动收录")
            return response
        } catch (e: Exception) { settle(id, req.requestId, null); throw e }
    }
    private fun settle(id: String, receiptId: String, actual: Long?) {
        if (store.get(id) == null) return
        store.update(id) { p -> p.copy(receipts = p.receipts.map { r ->
            if (r.id != receiptId || r.status != ReceiptStatus.RESERVED) r
            else r.copy(counted = actual ?: r.reserved,
                status = if (actual == null) ReceiptStatus.ESTIMATED else ReceiptStatus.REPORTED,
                completedAt = clock.nowMillis())
        }) }
    }
    private fun createChapter(id: String, run: String, cancellation: Cancellation) {
        var p = active(id, run)
        if (p.draft!!.stage == DraftStage.PLAN) {
            val res = request(id, run, Purpose.PLAN, cancellation) { cur, text ->
                cur.copy(draft = cur.draft!!.copy(plan = text), status = "正在规划第 ${cur.draft.ordinal} 章")
            }
            if (res.finishReason == "length") throw Paused("章节卡被输出上限截断；请提高上限后继续")
            edit(id, run) { it.copy(draft = it.draft!!.copy(plan = res.content, stage = DraftStage.BODY)) }
        }
        p = active(id, run)
        if (p.draft!!.stage == DraftStage.BODY) {
            while (true) {
                p = active(id, run); val before = p.draft!!
                if (before.completedParts >= p.settings.maxParts)
                    throw Paused("达到本章分段上限；请检查草稿，手动收录或增加分段上限")
                val base = before.body
                val join = if (base.isBlank() || base.endsWith("\n")) "" else "\n"
                val res = request(id, run, Purpose.BODY, cancellation) { cur, text ->
                    cur.copy(draft = cur.draft!!.copy(body = base + join + text), status = "正在写第 ${cur.draft.ordinal} 章")
                }
                val body = base + join + res.content
                if (textLength(res.content) < 20) throw Paused("本次新增正文过短，请检查草稿")
                val complete = res.finishReason == "stop" && textLength(body) >= (p.settings.targetChars * 0.75).toInt()
                edit(id, run) { it.copy(draft = it.draft!!.copy(body = body, completedParts = before.completedParts + 1,
                    stage = if (complete) DraftStage.MEMORY else DraftStage.BODY)) }
                if (complete) break
            }
        }
        p = active(id, run)
        if (p.draft!!.stage == DraftStage.MEMORY) collectMemory(id, run, cancellation)
        cancellation.check()
        edit(id, run) { cur ->
            val d = cur.draft!!; check(d.stage == DraftStage.READY)
            val first = d.body.lineSequence().firstOrNull()?.trim().orEmpty()
            val hasTitle = first.length <= 100 && Regex("^第[\\d一二三四五六七八九十百千万零〇两]+章.*").matches(first)
            val title = if (hasTitle) first else "第${d.ordinal}章"
            val content = if (hasTitle) d.body.substringAfter('\n', "").trim() else d.body.trim()
            require(content.isNotBlank()) { "正文为空" }
            cur.copy(chapters = cur.chapters + Chapter(d.chapterId, d.ordinal, title, content, d.memoryAfter, clock.nowMillis(),
                d.memoryProgress?.let { ChapterMemory(memoryHash(content), it.parts) }),
                draft = null, pendingPublish = true, status = "正文已保存，正在更新原阅读器目录")
        }
    }
    /** Retry only bounded extraction/format errors. Never retry authoring, billing, auth or network failures. */
    private fun collectMemory(id: String, run: String, cancellation: Cancellation) {
        var p = active(id, run)
        val hash = memoryHash(p.draft!!.body)
        if (p.draft!!.memoryProgress?.bodyHash != hash) edit(id, run) { cur ->
            cur.copy(draft = cur.draft!!.copy(memoryProgress = MemoryLedger.newProgress(cur, cur.draft)),
                status = "正文已保存；开始按段整理记忆增量")
        }
        while (true) {
            cancellation.check(); p = active(id, run)
            val d = p.draft!!; val progress = d.memoryProgress!!
            val parts = MemoryLedger.chunks(d.body, progress.chunkChars)
            if (progress.parts.size >= parts.size) break
            try {
                val response = request(id, run, Purpose.MEMORY, cancellation) { cur, text ->
                    cur.copy(draft = cur.draft!!.copy(memoryAfter = text),
                        status = "正文已保存；整理记忆 ${progress.parts.size + 1}/${parts.size}${if (progress.compact) "（紧凑重试）" else ""}")
                }
                if (response.finishReason != "stop") throw MemoryFormatException("单段记忆输出被截断")
                val delta = MemoryLedger.parseDelta(response.content, parts[progress.parts.size],
                    MemoryLedger.index(active(id, run)), progress.compact)
                edit(id, run) { cur -> cur.copy(draft = cur.draft!!.copy(memoryProgress =
                    progress.copy(parts = progress.parts + delta, compact = false))) }
            } catch (e: MemoryFormatException) {
                if (!progress.compact) edit(id, run) { cur -> cur.copy(draft = cur.draft!!.copy(
                    memoryProgress = progress.copy(compact = true)), status = "记忆格式不完整；仅重试本段一次，不重写正文") }
                else throw Paused("正文已保存，记忆待整理。紧凑重试仍未通过；可点击“只整理记忆”，不会重写正文。原因：${e.message}")
            }
        }
        edit(id, run) { cur ->
            val d = cur.draft!!; val progress = d.memoryProgress!!
            cur.copy(draft = d.copy(memoryAfter = ChapterMemory(progress.bodyHash, progress.parts).display(),
                stage = DraftStage.READY), status = "本章记忆增量已校验")
        }
    }
    /** An arc is a convenience index, not a prerequisite for publishing or the next chapter. */
    private fun maybeArcSummary(id: String, run: String, cancellation: Cancellation) {
        val p = active(id, run)
        if (p.chapters.isEmpty() || p.chapters.size % 15 != 0 || p.chapters.last().arcSummary.isNotBlank()) return
        if (p.chapters.takeLast(15).any { it.memoryV2 == null }) return
        try {
            val res = request(id, run, Purpose.ARC_SUMMARY, cancellation) { cur, _ -> cur }
            if (res.finishReason == "stop" && textLength(res.content) in 50..1400) edit(id, run) { cur ->
                cur.copy(chapters = cur.chapters.dropLast(1) + cur.chapters.last().copy(arcSummary = res.content))
            }
        } catch (e: Cancelled) { throw e }
        catch (_: Exception) {
            cancellation.check(); active(id, run)
            // Chapter deltas already exist. The missing arc cannot erase text or invalidate memory.
        }
    }
    fun synchronizeReader(id: String, run: String? = null) {
        store.update(id) { p ->
            if (run != null && p.activeRun?.id != run) throw Cancelled()
            if (!p.pendingPublish) p else {
                if (p.deletedAt != null) reader.remove(p.id) else reader.publish(p)
                p.copy(pendingPublish = false, status = if (p.deletedAt == null) "目录已更新" else "已移入回收站")
            }
        }
    }
    private fun stopStatus(id: String, run: String, message: String?, review: Boolean = false) {
        if (store.get(id) == null) return
        store.update(id) { p ->
            if (p.activeRun?.id != run) p else p.copy(activeRun = null, status = message ?: p.status,
                draft = p.draft?.let { if (review && it.stage == DraftStage.BODY && it.body.isNotBlank()) it.copy(needsReview = true) else it })
        }
    }
}
