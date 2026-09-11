/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai.core

class ProjectActions(private val store: ProjectStore, private val reader: ReaderPort,
    private val clock: Clock = SystemClock,
    private val cancelNetwork: (projectId: String, runId: String) -> Unit = { _, _ -> }) {
    fun create(title: String, settings: NovelSettings): Project {
        val p = Project(title = title.trim(), settings = settings, createdAt = clock.nowMillis())
        store.create(p); return p
    }
    fun changeSettings(id: String, settings: NovelSettings): Project = store.update(id) { p ->
        check(p.activeRun == null) { "请先停止创作，再修改设定" }; check(p.deletedAt == null)
        settings.validate(); p.copy(settings = settings, updatedAt = clock.nowMillis())
    }
    private fun stoppedChange(id: String, change: (Project) -> Project): Project {
        var oldRun: String? = null
        val result = store.update(id) { p -> oldRun = p.activeRun?.id; change(p.copy(activeRun = null)) }
        oldRun?.let { cancelNetwork(id, it) }; return result
    }
    fun stop(id: String): Project = stoppedChange(id) { p ->
        p.copy(status = "已停止，草稿已保留",
            draft = p.draft?.let { if (it.stage == DraftStage.BODY && it.body.isNotBlank()) it.copy(needsReview = true) else it })
    }
    fun continueDraft(id: String) = store.update(id) { p ->
        check(p.activeRun == null && p.deletedAt == null)
        p.copy(draft = p.draft?.copy(needsReview = false), status = "已选择接着草稿写；等待手动开始")
    }
    fun acceptDraftBody(id: String) = store.update(id) { p ->
        check(p.activeRun == null && p.deletedAt == null)
        val draft = requireNotNull(p.draft) { "没有草稿" }; require(draft.body.isNotBlank())
        p.copy(draft = draft.copy(stage = DraftStage.MEMORY, needsReview = false), status = "正文待收录；下次开始仅整理记忆，不重写正文")
    }
    fun discardDraft(id: String): Project = stoppedChange(id) { p ->
        check(p.deletedAt == null) { "小说已在回收站" }; val d = p.draft
        p.copy(draft = null, archives = if (d == null) p.archives else p.archives +
            ArchivedTail(fromOrdinal = d.ordinal, prefixIds = p.chapters.map { it.id }, chapters = emptyList(), draft = d, createdAt = clock.nowMillis()),
            status = "草稿已回收；正式章节保持不变")
    }
    fun deleteFrom(id: String, ordinal: Int): Project = stoppedChange(id) { p ->
        check(p.deletedAt == null) { "小说已在回收站" }; require(ordinal in 1..p.chapters.size) { "请选择已有章节" }
        val prefix = p.chapters.take(ordinal - 1)
        val tail = ArchivedTail(fromOrdinal = ordinal, prefixIds = prefix.map { it.id }, chapters = p.chapters.drop(ordinal - 1),
            draft = p.draft, createdAt = clock.nowMillis())
        p.copy(chapters = prefix, draft = null, archives = p.archives + tail, pendingPublish = true,
            status = "后文已保存为旧版本；人物状态和伏笔已退回保留章节")
    }
    fun restoreTail(id: String, archiveId: String): Project {
        val p = store.get(id) ?: error("项目不存在"); check(p.activeRun == null && p.deletedAt == null)
        val a = p.archives.firstOrNull { it.id == archiveId } ?: error("旧版本不存在")
        val unchanged = p.chapters.map { it.id } == a.prefixIds && p.draft == null
        if (unchanged) return store.update(id) { now ->
            check(now.activeRun == null && now.revision == p.revision) { "内容刚被修改，请重新操作" }
            now.copy(chapters = now.chapters + a.chapters, draft = a.draft,
                archives = now.archives.filterNot { it.id == archiveId }, pendingPublish = true, status = "旧版本已恢复")
        }
        val catalog = (p.chapters + p.archives.flatMap { it.chapters }).associateBy { it.id }
        val prefix = a.prefixIds.map { catalog[it] ?: error("原前文已缺失，不能安全恢复") }
        val restored = p.copy(id = newId(), title = (p.title.take(180) + " · 恢复副本"),
            createdAt = clock.nowMillis(), updatedAt = clock.nowMillis(), revision = 0,
            chapters = prefix + a.chapters, draft = a.draft, archives = emptyList(), receipts = emptyList(),
            activeRun = null, pendingPublish = true, status = "旧后文已恢复为副本，未覆盖新剧情")
        store.create(restored); return restored
    }
    fun trash(id: String): Project {
        val deleted = stoppedChange(id) { it.copy(deletedAt = clock.nowMillis(), pendingPublish = true, status = "已移入回收站") }
        synchronizeRemoval(id); return store.get(id) ?: deleted
    }
    fun synchronizeRemoval(id: String) = store.update(id) { p ->
        check(p.deletedAt != null && p.activeRun == null); reader.remove(id); p.copy(pendingPublish = false)
    }
    fun restoreProject(id: String) = store.update(id) { p ->
        check(p.activeRun == null && p.deletedAt != null)
        p.copy(deletedAt = null, pendingPublish = true, status = "已恢复；等待同步书架")
    }
    fun erase(id: String) {
        val p = store.get(id) ?: return
        check(p.deletedAt != null && p.activeRun == null && !p.pendingPublish) { "请先移入回收站并完成书架移除" }
        store.erase(id, p.revision)
    }
    fun recoverInterrupted() {
        for (p in store.all().filter { it.activeRun != null }) store.update(p.id) { current ->
            current.copy(activeRun = null, status = "上次运行被系统中断；请检查草稿后手动继续",
                draft = current.draft?.let { if (it.stage == DraftStage.BODY && it.body.isNotBlank()) it.copy(needsReview = true) else it },
                receipts = current.receipts.map { if (it.status == ReceiptStatus.RESERVED) it.copy(status = ReceiptStatus.ESTIMATED) else it })
        }
    }
    fun continuationCopy(title: String, settings: NovelSettings, originals: List<Chapter>, authorMemory: String): Project {
        require(originals.isNotEmpty()) { "没有可复制的正文，请先使用原阅读器缓存或导入" }
        require(authorMemory.isNotBlank() || originals.size <= settings.recentChapters) { "长篇续写需要先整理并核对前文记忆，不能只靠最后几章冒充完整记忆" }
        val copied = originals.mapIndexed { i, c -> c.copy(id = newId(), ordinal = i + 1,
            memoryAfter = if (i == originals.lastIndex) authorMemory else "") }
        val p = Project(title = title.trim().take(190) + " · AI续写", settings = settings, createdAt = clock.nowMillis(),
            chapters = copied, pendingPublish = true, seedMemory = authorMemory, status = "已建立独立续写副本；原书和源文件不变")
        store.create(p); return p
    }
    fun restoreBackup(backup: Project): Project {
        backup.validate()
        val p = backup.copy(id = newId(), title = backup.title.take(180) + " · 导入备份",
            revision = 0, activeRun = null, deletedAt = null, pendingPublish = true,
            receipts = backup.receipts.map { if (it.status == ReceiptStatus.RESERVED) it.copy(status = ReceiptStatus.ESTIMATED) else it },
            draft = backup.draft?.copy(needsReview = true), status = "备份已恢复；不会自动调用 API")
        store.create(p); return p
    }
}
