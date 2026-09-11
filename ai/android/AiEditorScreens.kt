/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.AtomicFile
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import io.legado.app.ai.core.*
import io.legado.app.data.appDb
import java.io.File

private fun EditText.changed(action: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { action() }
        override fun afterTextChanged(s: Editable?) {}
    })
}
private fun EditText.value() = text.toString()
private fun EditText.number(label: String): Int = value().toIntOrNull() ?: error("请填写有效的$label")

fun AiActivity.editNovel(id: String? = null) {
    page(if (id == null) "新建小说" else "设定与剧情指令", { if (id == null) showHome() else showProject(id) })
    pageId = id
    work({ id?.let { AiRuntime.store.get(it) } }) { p ->
        if (p?.activeRun != null) { info("请先停止本轮创作，再修改设定"); showProject(p.id); return@work }
        renderNovelForm(p)
    }
}
private fun AiActivity.renderNovelForm(p: Project?) {
    val draftFile = AtomicFile(File(noBackupFilesDir, "ai_form_${p?.id ?: "new"}.json"))
    val restored = runCatching { Json.parse(draftFile.readFully().toString(Charsets.UTF_8)).obj() }.getOrNull()
    if (restored != null) label("已恢复上次未保存的编辑内容。点击保存后才会用于创作。")
    val settings = p?.settings ?: NovelSettings()
    fun saved(key: String, fallback: String): String = (restored?.get(key) as? String) ?: fallback
    val title = field("书名", saved("title", p?.title.orEmpty()))
    val bible = field("世界观与人物总设定", saved("bible", settings.bible), 12)
    val counter = label("")
    fun updateCounter() { counter.text = "${textLength(bible.value())}字 · 支持超过3000字的完整总设定，不自动缩短" }
    updateCounter(); bible.changed { updateCounter() }
    button("填入长设定示例") {
        val fill = { work({ assets.open("ai_world_example.txt").bufferedReader().use { it.readText() } }) { bible.setText(it) } }
        if (bible.value().isNotBlank()) confirm("替换总设定", "将用示例替换当前编辑框中的内容。原已保存的设定在点击保存前不变。", "填入示例", fill) else fill()
    }
    val chars = field("每章目标字数", saved("chars", settings.targetChars.toString()), number = true)
    val outline = field("小说总纲（可选）", saved("outline", settings.outline), 5)
    val director = field("剧情指令（持续生效，可随时修改）", saved("director", settings.director), 4)
    label("设定修改只影响之后的创作，不改写已经完成的正文。想从旧章节改变走向，请使用“章节与重写”。")
    val advancedStart = content.childCount
    val context = field("模型上下文上限 · Token", saved("context", settings.contextTokens.toString()), number = true)
    val output = field("单次输出上限 · Token", saved("output", settings.outputTokens.toString()), number = true)
    val recent = field("最近前文章数 · 新引擎最多取2章", saved("recent", settings.recentChapters.toString()), number = true)
    val parts = field("每章最多分段次数 · 1至20", saved("parts", settings.maxParts.toString()), number = true)
    val advancedViews = (advancedStart until content.childCount).map { content.getChildAt(it) }
    advancedViews.forEach { it.visibility = View.GONE }
    button("展开／收起高级参数") { advancedViews.forEach { it.visibility = if (it.visibility == View.GONE) View.VISIBLE else View.GONE } }
    val handler = Handler(Looper.getMainLooper())
    fun snapshot() = mapOf("title" to title.value(), "bible" to bible.value(), "chars" to chars.value(),
        "outline" to outline.value(), "director" to director.value(), "context" to context.value(),
        "output" to output.value(), "recent" to recent.value(), "parts" to parts.value())
    var live = true
    val saveDraft = Runnable {
        if (live) runCatching {
            val out = draftFile.startWrite()
            try { out.write(Json.stringify(snapshot()).toByteArray(Charsets.UTF_8)); draftFile.finishWrite(out) }
            catch (e: Exception) { draftFile.failWrite(out); throw e }
        }.onFailure { android.widget.Toast.makeText(this, "编辑草稿暂未保存，请检查存储空间", android.widget.Toast.LENGTH_LONG).show() }
    }
    persistForm = { handler.removeCallbacks(saveDraft); saveDraft.run() }
    for (f in listOf(title, bible, chars, outline, director, context, output, recent, parts)) f.changed {
        handler.removeCallbacks(saveDraft); handler.postDelayed(saveDraft, 700)
    }
    button(if (p == null) "创建小说" else "保存设定") {
        val name = title.value().trim()
        require(name.isNotBlank() && name.length <= 200) { "请填写200字以内的书名" }
        val next = NovelSettings(bible.value(), outline.value(), director.value(), chars.number("章长"),
            context.number("上下文上限"), output.number("输出上限"), recent.number("前文章数"), parts.number("分段次数"))
        next.validate()
        work({
            if (p == null) AiRuntime.actions.create(name, next)
            else {
                AiRuntime.actions.changeSettings(p.id, next)
                AiRuntime.store.update(p.id) { current ->
                    check(current.activeRun == null)
                    current.copy(title = name, pendingPublish = current.chapters.isNotEmpty(),
                        draft = current.draft?.let { if (it.body.isNotBlank()) it.copy(needsReview = true) else it })
                }.also { AiRuntime.sync(it.id) }
            }
        }) { saved ->
            live = false; handler.removeCallbacks(saveDraft); persistForm = null; draftFile.delete(); showProject(saved.id)
        }
    }
}
fun AiActivity.showDraft(id: String) {
    page("检查草稿", { showProject(id) }); pageId = id
    work({ AiRuntime.store.get(id)!! }) { p ->
        require(p.activeRun == null) { "请先停止生成，再编辑草稿" }
        val d = p.draft ?: return@work info("目前没有未收录草稿")
        label("第${d.ordinal}章 · 草稿不会出现在正式目录里。保存修改后，返回选择接着写或采用正文。")
        val text = field("草稿正文", d.body, 16)
        button("保存修改") {
            val body = text.text.toString()
            work({ AiRuntime.store.update(id) { current ->
                check(current.activeRun == null && current.draft?.chapterId == d.chapterId && current.revision == p.revision) { "草稿已变化，请重新打开检查" }
                current.copy(draft = d.copy(body = body, stage = DraftStage.BODY, completedParts = 0,
                    memoryAfter = "", needsReview = true, memoryProgress = null), status = "草稿修改已保存，等待你选择下一步")
            } }) { showProject(id) }
        }
        if (d.plan.isNotBlank()) button("查看本章规划卡") { showText("本章规划", d.plan) { showDraft(id) } }
    }
}
fun AiActivity.showMemory(id: String) {
    page("记忆档案与纠正", { showProject(id) }); pageId = id
    work({ AiRuntime.store.get(id)!! }) { p ->
        label(MemoryLedger.status(p))
        label("新章节只提取本章变化。人物、事实、线索和时间线按当前故事分支检索；删掉的后文不会留在工作记忆中。")
        button("查看当前状态与相关记忆（只读）") {
            val query = p.settings.director + p.draft?.plan.orEmpty()
            showText("当前记忆视图", MemoryLedger.working(p) + "\n\n" + MemoryLedger.retrieve(p, query).text) { showMemory(id) }
        }
        button("查看逐章摘要与证据") {
            page("逐章记忆", { showMemory(id) })
            p.chapters.forEach { c -> button("${c.title} · ${if (c.memoryV2 == null) "旧版台账" else "增量记忆"}") {
                val text = c.memoryV2?.let { m -> m.parts.joinToString("\n\n") { d ->
                    "摘要：${d.summary}\n" + d.records.joinToString("\n") { r ->
                        "${r.identity()} ${r.kind} ${r.entity}/${r.key}：${r.value} [${r.status}]\n证据：${r.evidence}"
                    } + if (d.warningCount > 0) "\n${d.warningCount}条缺少原文证据，未入库。" else ""
                } } ?: c.memoryAfter.ifBlank { "这章没有旧台账，正文仍完整保留。" }
                showText(c.title, text) { showMemory(id) }
            } }
        }
        val last = p.chapters.asReversed().firstOrNull { it.authorNote.isNotBlank() }
        val note = last?.authorNote ?: if (p.chapters.isEmpty()) p.seedMemory else ""
        val memory = field("作者核对与纠正（可选，优先于自动提取）", note, 8)
        label("填写当前阶段必须记住的纠正，不必抄整本书。全文保存在本地；工作提示词优先取前1200字。自动档案和旧台账不会被覆盖。")
        button("保存记忆纠正", p.activeRun == null) {
            val value = memory.text.toString()
            work({ AiRuntime.store.update(id) { current ->
                check(current.activeRun == null && current.revision == p.revision) { "项目已变化，请重新打开记忆" }
                if (current.chapters.isEmpty()) current.copy(seedMemory = value, status = "开篇记忆已更新")
                else current.copy(chapters = current.chapters.dropLast(1) + current.chapters.last().copy(authorNote = value),
                    status = "作者纠正已保存，自动记录与正文保持不变")
            } }) { showProject(id) }
        }
    }
}
fun AiActivity.showContinuation(bookUrl: String) {
    page("建立 AI 续写副本", { chooseOriginal() })
    work({ val b = appDb.bookDao.getBook(bookUrl) ?: error("请先把这本书加入书架")
        b to appDb.bookChapterDao.getChapterList(bookUrl).size }) { (book, count) ->
        label(book.name, true)
        label("复制后的小说独立保存，原书和源文件不变。只读取已经导入或缓存的正文；本步骤不调用 API。")
        val through = field("复制至第几章（包含）", count.toString(), number = true)
        val bible = field("续写总设定 · 世界观与人设", "", 9)
        val memory = field("前文记忆 · 你核对过的情节、人物和伏笔", "", 8)
        label("复制超过3章时，请填写前文记忆，避免模型只看最后几章就猜测完整剧情。不要把复制范围之后的情节写入当前记忆。")
        val director = field("接下来的剧情方向", "", 4)
        val target = field("每章目标字数", "4000", number = true)
        button("创建独立续写副本") {
            val end = through.number("章节范围")
            val settings = NovelSettings(bible = bible.value(), director = director.value(), targetChars = target.number("章长"))
            settings.validate(); val summary = memory.value()
            work({
                val original = AiRuntime.reader.originals(bookUrl, end)
                val p = AiRuntime.actions.continuationCopy(original.first.name, settings, original.second, summary)
                AiRuntime.sync(p.id); p.id
            }) { showProject(it) }
        }
    }
}
