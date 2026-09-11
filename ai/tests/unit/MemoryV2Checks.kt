/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import io.legado.app.ai.core.*
import java.io.File
import java.nio.file.Files

/** Self-contained regression scenarios. Fixtures contain no user manuscript and never use a real API. */
object MemoryV2Checks {
    private val body = "林砚在码头收起铜扣。他决定去找摆渡人核实旧信的来源。\n".repeat(60)
    private val setting = NovelSettings(bible = "世界设定与人物规则。".repeat(350), targetChars = 500)
    private val memory = """{"summary":"林砚收起铜扣，在码头追查旧信的来源。","records":[{"kind":"state","entity":"林砚","key":"地点","value":"码头","evidence":"林砚在码头收起铜扣","importance":3,"status":"active","targetId":""}]}"""
    private class Reader : ReaderPort {
        val saved = linkedMapOf<String, Project>()
        override fun publish(project: Project) { saved[project.id] = project }
        override fun remove(projectId: String) { saved.remove(projectId) }
        override fun open(projectId: String) { check(projectId in saved) }
    }
    private class Gateway : CompletionGateway {
        val calls = mutableListOf<CompletionRequest>()
        var handler: ((CompletionRequest, Int) -> CompletionResult)? = null
        override fun complete(request: CompletionRequest, cancellation: Cancellation, onPartial: (String) -> Unit): CompletionResult {
            calls += request; cancellation.check()
            val res = handler?.invoke(request, calls.size) ?: CompletionResult(when (request.purpose) {
                Purpose.PLAN -> "前往码头，询问摆渡人。不要揭露旧信的幕后来源。"
                Purpose.BODY -> "第1章 码头\n$body"
                Purpose.MEMORY -> memory
                Purpose.ARC_SUMMARY -> "林砚多次到码头核实旧信，铜扣仍是重要线索，摆渡人的动机尚需确认。".repeat(3)
                else -> "连接成功"
            }, "stop", 100, 200)
            onPartial(res.content); return res
        }
    }
    private class F(val store: ProjectStore = MemoryStore()) {
        val reader = Reader(); val gateway = Gateway(); val actions = ProjectActions(store, reader)
        val p = actions.create("记忆回归样本", setting)
        val engine = NovelEngine(store, gateway, reader)
        fun get() = store.get(p.id)!!
        fun run(limits: RunLimits = RunLimits()) = engine.run(p.id, limits)
        fun readyBody(text: String = body) = store.update(p.id) { it.copy(draft = Draft(ordinal = it.chapters.size + 1,
            stage = DraftStage.MEMORY, body = text)) }
    }
    private fun expectReject(block: () -> Unit) { var rejected = false; try { block() } catch (_: Exception) { rejected = true }; check(rejected) }
    private fun legacy(p: Project, size: Int = 12): Project = p.copy(chapters = (1..size).map {
        Chapter(ordinal = it, title = "第${it}章 旧稿", content = "旧正文-$it。".repeat(600),
            memoryAfter = "旧版人物、道具和伏笔台账-$it。".repeat(100), createdAt = it.toLong())
    }, draft = Draft(ordinal = size + 1, stage = DraftStage.MEMORY, body = body, memoryAfter = "{旧记忆被截断"))

    fun case01OldSchemaRoundTripPreservesEveryText() {
        val f = F(); val before = legacy(f.p)
        val decoded = ProjectJson.decode(ProjectJson.encode(before))
        check(decoded == before && decoded.draft!!.body == body)
    }
    fun case02MemoryStageResumesWithoutRewritingThirteen() {
        val f = F(); val old = legacy(f.p); f.store.update(f.p.id) { old }
        f.run(); check(f.gateway.calls.map { it.purpose } == listOf(Purpose.MEMORY))
        check(f.get().chapters.size == 13 && f.get().chapters.take(12) == old.chapters)
        check(f.get().chapters.last().content == body.trim() && f.get().draft == null)
    }
    fun case03NormalPlanBodyDelta() { val f=F(); f.run(); check(f.gateway.calls.size==3); check(f.get().chapters.last().memoryV2 != null) }
    fun case04OneCompactRetryNoBodyRepeat() {
        val f=F(); f.readyBody(); var n=0
        f.gateway.handler={_,_-> n++; if(n==1) CompletionResult("{", "length",100,20) else CompletionResult(memory,"stop",100,200) }
        f.run(); check(f.gateway.calls.size==2); check(f.get().chapters.size==1)
        check(f.gateway.calls[1].messages[0].content.contains("最多6条"))
    }
    fun case05TwoTruncationsPauseWithDraftIntact() {
        val f=F(); f.readyBody(); f.gateway.handler={_,_->CompletionResult("{", "length")}; f.run()
        check(f.gateway.calls.size==2 && f.get().draft!!.body==body && f.get().chapters.isEmpty())
        check(f.get().status.contains("记忆待整理") && !f.get().status.contains("提高输出"))
    }
    fun case06RepairAfterTwoFailuresOnlyCallsMemory() {
        val f=F(); f.readyBody(); f.gateway.handler={_,_->CompletionResult("{", "length")}; f.run()
        f.gateway.handler=null; f.run(); check(f.get().chapters.size==1 && f.gateway.calls.all{it.purpose==Purpose.MEMORY})
    }
    fun case07InvalidJsonRetriesOnce() {
        val f=F(); f.readyBody(); f.gateway.handler={_,i->CompletionResult(if(i==1)"bad" else memory,"stop")}; f.run()
        check(f.get().chapters.size==1 && f.gateway.calls.size==2)
    }
    fun case08NetworkFailureNeverAutoswallowed() {
        val f=F(); f.readyBody(); f.gateway.handler={_,_->throw java.io.IOException("network")}; f.run()
        check(f.gateway.calls.size==1 && f.get().draft!!.body==body && f.get().activeRun==null)
    }
    fun case09BudgetAppliesToCompactRetry() {
        val f=F(); f.readyBody(); f.gateway.handler={_,_->CompletionResult("{","length",100,20)}
        f.run(RunLimits(requestLimit=1)); check(f.gateway.calls.size==1 && f.get().draft!!.body==body)
    }
    fun case10StopOrFilterIsNotRetried() {
        val f=F(); f.readyBody(); f.gateway.handler={_,_->CompletionResult("","content_filter")};f.run()
        check(f.gateway.calls.size==1 && f.get().chapters.isEmpty())
    }
    fun case11EmptyLengthCanRetryCompact() {
        val f=F(); f.readyBody();f.gateway.handler={_,i->if(i==1)CompletionResult("","length") else CompletionResult(memory,"stop")};f.run()
        check(f.get().chapters.size==1)
    }
    fun case12ChunkingIsLosslessUnicode() {
        val content="甲😀乙\n".repeat(7000); val parts=MemoryLedger.chunks(content,1000)
        check(parts.joinToString("")==content); check(parts.all{it.length<=1000 && !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first())})
    }
    fun case13LongChapterMemoryIsSegmented() {
        val f=F();val text=body.repeat(8);f.readyBody(text);f.run()
        check(f.get().chapters.single().content==text.trim())
        check(f.gateway.calls.size==MemoryLedger.chunks(text).size && f.gateway.calls.all{it.purpose==Purpose.MEMORY})
        check(f.gateway.calls.all{!it.messages.joinToString{it.content}.contains(setting.bible)})
    }
    fun case14SuccessfulChunksNotRepaidOnResume() {
        val f=F(); val text=body.repeat(8);f.readyBody(text)
        f.gateway.handler={_,i->if(i==2)throw java.io.IOException("interrupted") else CompletionResult(memory,"stop")};f.run()
        check(f.get().draft!!.memoryProgress!!.parts.size==1)
        f.gateway.handler=null; f.run();check(f.get().chapters.size==1)
        check(f.gateway.calls.size==MemoryLedger.chunks(text).size+1)
    }
    fun case15BodyEditInvalidatesOnlyMemoryProgress() {
        val f=F();f.readyBody();val old=MemoryLedger.newProgress(f.get(),f.get().draft!!)
        f.store.update(f.p.id){it.copy(draft=it.draft!!.copy(body=body+"正文修改",memoryProgress=old))};f.run()
        check(f.get().chapters.single().content.endsWith("正文修改"));check(f.gateway.calls.size==1)
    }
    fun case16NoEvidenceNoFact() {
        val delta=MemoryLedger.parseDelta(memory, "没有提到这些事情。",emptyMap(),false)
        check(delta.records.isEmpty() && delta.warningCount==1)
    }
    fun case17ValidEvidenceRetained() {
        val delta=MemoryLedger.parseDelta(memory,body,emptyMap(),false)
        check(delta.records.size==1 && delta.records.single().evidence=="林砚在码头收起铜扣")
    }
    fun case18UnknownCloseIsRejected() {
        val wrong=memory.replace("\"state\"","\"thread\"").replace("\"active\"","\"closed\"").replace("\"targetId\":\"\"","\"targetId\":\"T-unknown\"")
        val delta=MemoryLedger.parseDelta(wrong,body,emptyMap(),false);check(delta.warningCount==1 && delta.records.isEmpty())
    }
    fun case19InvalidOrOversizedDeltaRejected() {
        expectReject{MemoryLedger.parseDelta(memory.replace("\"importance\":3","\"importance\":4294967299"),body,emptyMap(),false)}
        expectReject{MemoryLedger.parseDelta("{\"summary\":\"${"字".repeat(700)}\",\"records\":[]}",body,emptyMap(),false)}
        expectReject{MemoryLedger.parseDelta("{\"summary\":\"甲\",\"records\":[],\"extra\":1}",body,emptyMap(),false)}
    }
    fun case20MemoryPayloadUsesWriterAndNoThinking() {
        val f=F();f.readyBody();val r=ContextComposer.build(f.get(),Purpose.MEMORY)
        val payload=Json.parse(CompletionProtocol.payload(r,ApiConfig(writingModel="writer",planningModel="planner"))).obj()
        check(payload.str("model")=="writer" && payload["thinking"].obj().str("type")=="disabled" && r.maxTokens<=4096)
    }
    fun case21MetadataNeverChangesProviderConfig() {
        val f=F();f.readyBody();val payload=Json.parse(CompletionProtocol.payload(ContextComposer.build(f.get(),Purpose.MEMORY),ApiConfig(deepSeekThinkingField=false))).obj()
        check("thinking" !in payload)
    }
    fun case22ContextBudgetDoesNotGrowWithThousandChapters() {
        val f=F();f.run();val c=f.get().chapters.single()
        val many=f.get().copy(chapters=(1..1000).map{c.copy(id=newId(),ordinal=it)},draft=null)
        val r=ContextComposer.build(many,Purpose.BODY)
        check(r.inputTokenUpperBound+r.maxTokens+512<=96000)
        check(r.messages.first().content.endsWith(setting.bible))
        check(MemoryLedger.working(many).length<=MemoryLedger.WORKING_CHARS)
        check(MemoryLedger.retrieve(many,"码头 铜扣").text.length<=MemoryLedger.RETRIEVAL_CHARS)
    }
    fun case23LegacyHugeLedgerDoesNotEnterWholePrompt() {
        val f=F();val old=legacy(f.p,1);val ledger="未解线索：旧信的封口来自北码头。\n".repeat(12000)
        val p=old.copy(chapters=listOf(old.chapters.single().copy(memoryAfter=ledger)),draft=null)
        val r=ContextComposer.build(p,Purpose.PLAN);check(!r.messages.last().content.contains(ledger));check(r.inputTokenUpperBound<96000)
        check(ProjectJson.decode(ProjectJson.encode(p)).chapters.single().memoryAfter==ledger)
    }
    fun case24RollbackRemovesFutureFactsAndKeepsArchive() {
        val f=F();f.run(RunLimits(RunMode.TO_CHAPTER,3));val all=f.get().chapters
        f.actions.deleteFrom(f.p.id,2)
        check(MemoryLedger.index(f.get()).values.all{it.ordinal==1});check(f.get().archives.single().chapters==all.drop(1))
        check(!MemoryLedger.retrieve(f.get(),"碼头").text.contains("第3章"))
    }
    fun case25RestoringOldBranchKeepsNewWriting() {
        val f=F();f.run(RunLimits(RunMode.TO_CHAPTER,2));val a=f.actions.deleteFrom(f.p.id,2).archives.single();f.run()
        val fresh=f.get();val restored=f.actions.restoreTail(f.p.id,a.id)
        check(restored.id!=fresh.id && f.get()==fresh && restored.chapters.last().id==a.chapters.last().id)
    }
    fun case26BackupKeepsMemoryStageAndAllArchives() {
        val f=F();f.run();f.actions.deleteFrom(f.p.id,1);f.readyBody()
        val before=f.get();val snapshot=ProjectJson.decode(ProjectJson.encodeBackup(before))
        check(snapshot.chapters==before.chapters && snapshot.archives==before.archives)
        check(snapshot.draft!!.stage==DraftStage.MEMORY && snapshot.draft.body==body)
        val restored=f.actions.restoreBackup(snapshot);check(restored.draft!!.body==body && restored.archives==before.archives)
    }
    fun case27LegacyBackupByteForByteGuard() {
        val root=Files.createTempDirectory("memory-migration-").toFile()
        try {val f=F(FileProjectStore(root));f.store.update(f.p.id){legacy(it)}
            // Reset guard to simulate first load of an original 1.0.1 save.
            File(root,"upgrade_backups").deleteRecursively()
            val raw=File(root,"${f.p.id}.json").readBytes(); val store=FileProjectStore(root)
            store.update(f.p.id){it.copy(status="升级后")}
            check(File(root,"upgrade_backups/${f.p.id}.json").readBytes().contentEquals(raw))
            check(store.get(f.p.id)!!.draft!!.body==body)
        }finally{root.deleteRecursively()}
    }
    fun case28InterruptedAtomicWriteRecoversOld() {
        val root=Files.createTempDirectory("memory-atomic-").toFile()
        try {val f=F(FileProjectStore(root));val old=File(root,"${f.p.id}.json");val bytes=old.readBytes()
            old.renameTo(File(old.path+".bak"));old.writeText("BROKEN");File(old.path+".new").writeText("partial")
            check(FileProjectStore(root).get(f.p.id)==f.p);check(old.readBytes().contentEquals(bytes))
        }finally{root.deleteRecursively()}
    }
    fun case29UpgradeCreatesNoPaidRequest() {
        val f=F();val before=legacy(f.p); val decoded=ProjectJson.decode(ProjectJson.encodeBackup(before));f.actions.restoreBackup(decoded)
        check(f.gateway.calls.isEmpty())
    }
    fun case30MemoryAtTinyContextIsBounded() {
        val f=F();f.readyBody();val p=f.get().copy(settings=setting.copy(contextTokens=8192,outputTokens=1024))
        val r=ContextComposer.build(p,Purpose.MEMORY);check(r.inputTokenUpperBound+r.maxTokens+512<=8192)
    }
    fun case31ReportedUsageRemainsActual() {val f=F();f.run();check(f.get().receipts.sumOf{it.counted}==900L);check(f.get().receipts.all{it.status==ReceiptStatus.REPORTED})}
    fun case32UnknownUsageMarkedEstimated() {
        val f=F();f.readyBody();f.gateway.handler={_,_->CompletionResult(memory,"stop")};f.run();check(f.get().receipts.single().status==ReceiptStatus.ESTIMATED)
    }
    fun case33PartialMemoryIsNotCanon() {
        val f=F();f.readyBody();f.gateway.handler={_,_->CompletionResult("{\"summary\":\"尚未完整的", "length")};f.run()
        check(MemoryLedger.index(f.get()).isEmpty());check(f.get().draft!!.body==body)
    }
    fun case34ArcAtFifteenUsesSummaries() {
        val f=F();f.run(RunLimits(RunMode.TO_CHAPTER,15,tokenLimit=10000000))
        check(f.get().chapters.size==15 && f.gateway.calls.count{it.purpose==Purpose.ARC_SUMMARY}==1)
        check(f.get().chapters.last().arcSummary.isNotBlank())
        val r=f.gateway.calls.last();check(!r.messages.last().content.contains(body))
    }
    fun case35ArcFailureDoesNotEraseFinishedChapter() {
        val f=F();f.run(RunLimits(RunMode.TO_CHAPTER,14,tokenLimit=10000000))
        f.gateway.handler={r,_-> if(r.purpose==Purpose.ARC_SUMMARY)throw java.io.IOException("arc fail")
            else CompletionResult(when(r.purpose){Purpose.PLAN->"章节卡";Purpose.BODY->"第15章 码头\n$body";else->memory},"stop")}
        f.run();check(f.get().chapters.size==15 && f.get().draft==null)
    }
    fun case36NoFutureArcAfterRollback() {
        val f=F();f.run(RunLimits(RunMode.TO_CHAPTER,15,tokenLimit=10000000));f.actions.deleteFrom(f.p.id,10)
        check(!MemoryLedger.retrieve(f.get(),"码头").text.contains("阶段摘要"))
    }
    fun case37SourceScriptAndApiAreUnchanged() {
        val f=F();val before=f.p.settings;f.readyBody();f.run();check(f.get().settings==before)
    }
    fun case38ClosedThreadReplaysAndRollsBack() {
        val f=F(); val plant=MemoryRecord("thread","铜扣","三道刻痕","含义未知","林砚在码头收起铜扣",5,"open")
        val id=plant.identity();val close=plant.copy(value="已查明用途",status="closed",targetId=id)
        val one=Chapter(ordinal=1,title="一",content=body,memoryAfter="摘要",createdAt=0,memoryV2=ChapterMemory(memoryHash(body),listOf(MemoryDelta("种下线索",listOf(plant)))))
        val two=one.copy(id=newId(),ordinal=2,title="二",memoryV2=ChapterMemory(memoryHash(body),listOf(MemoryDelta("解决线索",listOf(close)))))
        f.store.update(f.p.id){it.copy(chapters=listOf(one,two))};check(MemoryLedger.index(f.get())[id]!!.record.status=="closed")
        f.actions.deleteFrom(f.p.id,2);check(MemoryLedger.index(f.get())[id]!!.record.status=="open")
    }
    fun case39IdempotentIndexNoDuplicateOnRetry() {
        val f=F();f.readyBody();f.run();val c=f.get().chapters.single(); val duplicate=c.copy(memoryV2=c.memoryV2!!.copy(parts=c.memoryV2.parts+c.memoryV2.parts))
        check(MemoryLedger.index(f.get().copy(chapters=listOf(duplicate))).size==1)
    }
    fun case40OriginalBibleNeverTrimmedAtCreation() {
        val f=F();val huge=setting.copy(bible="界".repeat(10000),contextTokens=8192,outputTokens=1024)
        f.actions.changeSettings(f.p.id,huge);f.run();check(f.gateway.calls.isEmpty() && f.get().settings.bible==huge.bible)
    }
    fun case41PlanningRetainsConfiguredOutputBudget() {
        val f=F(); f.readyBody()
        check(ContextComposer.build(f.get(),Purpose.PLAN).maxTokens == f.get().settings.outputTokens)
        check(ContextComposer.build(f.get(),Purpose.MEMORY).maxTokens <= 4096)
    }
    @JvmStatic fun main(args: Array<String>) { runAll() }
    fun runAll() {
        val tests=MemoryV2Checks::class.java.declaredMethods.filter{it.name.matches(Regex("case[0-9]{2}[A-Za-z]+")) && it.parameterCount == 0 && !it.isSynthetic}.sortedBy{it.name}
        check(tests.size == 41) { "Expected all 41 regression scenarios, found ${tests.size}" }
        var passed=0
        for(t in tests){ try {t.invoke(this);passed++;println("PASS ${t.name}")} catch(e:Exception){throw AssertionError("FAIL ${t.name}",e.cause ?: e)} }
        println("MEMORY_V2_CHECKS: $passed/${tests.size} passed (synthetic local fixtures, no API)")
    }
}
