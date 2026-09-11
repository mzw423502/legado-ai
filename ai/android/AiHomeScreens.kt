/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import io.legado.app.ai.core.*
import io.legado.app.data.appDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun AiActivity.projectStats(p: Project): String {
    val basic = "${p.chapters.size}章 · ${p.chapters.sumOf { textLength(it.content) }}字"
    return if (AiRuntime.settings.isDemo(p.id)) "$basic\n离线体验：不连接 API，不产生费用"
    else "$basic\n本机累计 ${p.receipts.size} 次请求 · ${p.receipts.sumOf { it.counted }} Token（含保守估算）"
}
fun AiActivity.showHome(trash: Boolean = false) {
    page(if (trash) "回收站" else "AI 创作", if (trash) ({ showHome() }) else null)
    if (!trash) {
        top.menu.add("回收站").setOnMenuItemClickListener { showHome(true); true }
        button("新建小说") { editNovel() }
        button("从书架小说继续写") { chooseOriginal() }
        separator()
    } else label("恢复后不会自动调用 API。彻底删除前，建议先导出备份。")
    work({ AiRuntime.store.all().filter { (it.deletedAt != null) == trash }.sortedByDescending { it.updatedAt } }) { list ->
        if (list.isEmpty()) label(if (trash) "回收站是空的" else "还没有创作小说。新建一本，或先体验阅读与创作流程。")
        for (p in list) {
            label(p.title, true); label(projectStats(p))
            if (!trash) button(if (p.activeRun != null) "正在创作 · 打开" else "打开创作") { showProject(p.id) }
            else {
                button("恢复《${p.title}》") { work({ AiRuntime.actions.restoreProject(p.id); AiRuntime.sync(p.id) }) { showHome() } }
                button("彻底删除《${p.title}》") {
                    confirm("彻底删除", "正文、设定、记忆和所有旧版本都将永久删除，无法恢复。手机中另存的导出文件不受影响。", "彻底删除") {
                        work({ AiRuntime.actions.erase(p.id) }) { showHome(true) }
                    }
                }
            }
            separator()
        }
        if (!trash) {
            button("离线体验") {
                work({
                    val settings = NovelSettings(bible = assets.open("ai_world_example.txt").bufferedReader().use { it.readText() }, targetChars = 1000)
                    val p = AiRuntime.actions.create("雾港来信 · 离线体验", settings)
                    AiRuntime.settings.setDemo(p.id, true)
                    AiRuntime.store.update(p.id) { it.copy(chapters = listOf(Chapter(ordinal = 1, title = "第1章 退潮之前",
                        content = AiDemoText.body, memoryAfter = "离线体验设定：林砚带着旧信和铜扣登上摆渡人的船；铜扣刻痕、灯塔逆向灯光仍是未解伏笔。", createdAt = System.currentTimeMillis())),
                        pendingPublish = true, status = "离线体验已准备好，可直接阅读，也可试写下一章") }
                    AiRuntime.sync(p.id); p.id
                }) { showProject(it) }
            }
            button("恢复 AI 小说备份") { importResult.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        }
    }
}
fun AiActivity.showProject(id: String) {
    page("小说创作", { showHome() }); pageId = id
    work({ AiRuntime.store.get(id) ?: error("小说不存在或已彻底删除") }) { p ->
        if (p.deletedAt != null) { showHome(true); return@work }
        top.title = p.title; watchId = id
        val running = p.activeRun != null || AiRuntime.runningId == id
        lastRunning = running; lastChapterCount = p.chapters.size
        statsLabel = label(projectStats(p)); statusLabel = label(p.status)
        button("阅读", p.chapters.isNotEmpty()) { work({ AiRuntime.sync(id); AiRuntime.reader.open(id) }) { } }
        if (running) {
            button("停止并保留草稿") { work({ AiRuntime.stop(id) }) { showProject(id) } }
            button("停止并丢弃本章草稿") { confirm("丢弃草稿", "停止本轮创作并回收当前草稿；前面的正式章节保留。已发出的请求仍可能计费。", "停止并丢弃") {
                work({ AiRuntime.actions.discardDraft(id) }) { showProject(id) }
            } }
        } else if (p.draft?.needsReview == true) {
            label("上次草稿未完成，请先选择如何处理。")
            button("检查和编辑草稿") { showDraft(id) }
            button("接着草稿写") { work({ AiRuntime.actions.continueDraft(id) }) { requestStart(id, RunLimits()) } }
            button("采用现有正文，只整理记忆") {
                confirm("采用草稿正文", "不补写正文；下一次调用只整理长期记忆，成功后收录到目录。", "采用并继续") {
                    work({ AiRuntime.actions.acceptDraftBody(id) }) { requestStart(id, RunLimits()) }
                }
            }
            button("丢弃这份草稿") { confirm("丢弃草稿", "草稿进入旧版本记录，正式章节保持不变。", "丢弃") {
                work({ AiRuntime.actions.discardDraft(id) }) { showProject(id) }
            } }
        } else {
            button(if (p.draft == null) "写下一章" else "继续创作本章") { requestStart(id, RunLimits()) }
            button("连续创作") { showRunSetup(id) }
            if (p.draft != null) button("检查和编辑草稿") { showDraft(id) }
        }
        if (p.draft != null || running) draftLabel = label(p.draft?.let {
            "第${it.ordinal}章草稿 · ${textLength(it.body)}字\n${it.body.takeLast(1200)}" } ?: "正在准备下一章…")
        separator()
        button("设定与剧情指令", !running) { editNovel(id) }
        button("长期记忆与伏笔", !running) { showMemory(id) }
        button("章节与重写", !running && p.chapters.isNotEmpty()) { showChapters(id) }
        button("旧版本与已丢弃草稿", !running) { showArchives(id) }
        if (p.pendingPublish) button("重新同步到书架（不调用 API）") { work({ AiRuntime.sync(id) }) { showProject(id) } }
        button("导出与备份") { showExports(id) }
        button("移入回收站") { confirm("移入回收站", "停止这本书的创作，从书架移除正文。设定、章节、草稿和旧版本保留在 AI 回收站中，可恢复。", "移入回收站") {
            work({ AiRuntime.actions.trash(id) }) { showHome() }
        } }
    }
}
fun AiActivity.showChapters(id: String) {
    page("章节与重写", { showProject(id) })
    label("从某章重写时，先保留旧后文，再回退到该章之前的记忆。不会把已删除的剧情带入新故事。")
    work({ AiRuntime.store.get(id)!! }) { p ->
        for (c in p.chapters) {
            button("${c.title} · ${textLength(c.content)}字") {
                page(c.title, { showChapters(id) }); label(c.content)
                button("从本章重新创作") { confirm("从第${c.ordinal}章重写", "本章及后文将保存为旧版本。保留前 ${c.ordinal - 1} 章，并同步回退人物、伏笔和时间线。接下来由你手动开始新一章。", "保留旧版并回退") {
                    work({ AiRuntime.actions.deleteFrom(id, c.ordinal); AiRuntime.sync(id) }) { showProject(id) }
                } }
            }
        }
    }
}
fun AiActivity.showArchives(id: String) {
    page("旧版本", { showProject(id) })
    work({ AiRuntime.store.get(id)!! }) { p ->
        if (p.archives.isEmpty()) label("尚未产生旧版本。重写或丢弃的内容会保存在这里。")
        for (a in p.archives.asReversed()) {
            val whenText = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(a.createdAt))
            label("$whenText · 从第${a.fromOrdinal}章开始 · ${a.chapters.size}章${if (a.draft != null) "＋草稿" else ""}", true)
            button("查看这份旧版本") {
                page("旧版本预览", { showArchives(id) })
                a.chapters.forEach { c -> button(c.title) { showText(c.title, c.content) { showArchives(id) } } }
                a.draft?.let { d -> button("未收录草稿") { showText("旧草稿", d.body.ifBlank { d.plan }) { showArchives(id) } } }
                button("恢复此版本") { confirm("恢复旧版本", "前文未改动时直接恢复；已经写出新剧情时，恢复为独立副本，不覆盖新内容。恢复后不会自动调用 API。", "恢复") {
                    work({ val restored = AiRuntime.actions.restoreTail(id, a.id); AiRuntime.sync(restored.id); restored.id }) { showProject(it) }
                } }
            }
        }
    }
}
fun AiActivity.showExports(id: String) {
    page("导出与备份", { showProject(id) })
    label("完整备份包含正文、设定、记忆、草稿和所有版本，不包含 API Key。卸载或换手机前请保存备份。")
    work({ AiRuntime.store.get(id)!! }) { p ->
        button("完整 AI 小说备份 · JSON") { export("${p.title}.legado-ai.json", "application/json") { ProjectJson.encodeBackup(AiRuntime.store.get(id)!!) } }
        button("导出正式正文 · TXT") { export("${p.title}.txt", "text/plain") {
            AiRuntime.store.get(id)!!.chapters.joinToString("\n\n") { "${it.title}\n\n${it.content}" }
        } }
        button("导出正式正文 · Markdown") { export("${p.title}.md", "text/markdown") {
            AiRuntime.store.get(id)!!.chapters.joinToString("\n\n") { "## ${it.title}\n\n${it.content}" }
        } }
        if (p.draft != null) button("单独导出未完成草稿") { export("${p.title}_草稿.txt", "text/plain") { AiRuntime.store.get(id)?.draft?.body.orEmpty() } }
    }
}
fun AiActivity.chooseOriginal() {
    page("选择续写的原书", { showHome() })
    label("只复制已经导入或缓存的正文，原书和源文件不会被修改。不在这里自动下载网络章节。")
    work({ appDb.bookDao.all.filter { AiRuntime.reader.projectId(it.bookUrl) == null } }) { books ->
        if (books.isEmpty()) label("请先回到原书架导入小说，或找书并缓存正文。")
        books.forEach { b -> button("${b.name} · ${b.author}") { showContinuation(b.bookUrl) } }
    }
}
