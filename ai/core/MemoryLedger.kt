/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai.core

import java.security.MessageDigest
import kotlin.math.min

/** Immutable, chapter-scoped changes. Deleting a tail cannot leave its facts in the live index. */
data class MemoryRecord(val kind: String, val entity: String, val key: String,
    val value: String, val evidence: String, val importance: Int = 3,
    val status: String = "active", val targetId: String = "") {
    fun identity(): String = when (kind) {
        "state" -> "S-" + memoryHash("$entity|$key").take(16)
        "thread" -> targetId.ifBlank { "T-" + memoryHash("$entity|$key").take(16) }
        else -> "F-" + memoryHash("$kind|$entity|$key|$value").take(16)
    }
}
data class MemoryDelta(val summary: String, val records: List<MemoryRecord> = emptyList(),
    val warningCount: Int = 0)
data class MemoryProgress(val bodyHash: String, val chunkChars: Int, val parts: List<MemoryDelta> = emptyList(),
    val compact: Boolean = false)
data class ChapterMemory(val bodyHash: String, val parts: List<MemoryDelta>) {
    fun summary() = parts.joinToString("\n") { it.summary }
    fun display() = "【本章增量摘要】\n${summary()}\n\n" + parts.flatMap { it.records }
        .joinToString("\n") { "${it.kind} · ${it.entity} · ${it.key}：${it.value} [${it.status}]" }
}
data class IndexedMemory(val id: String, val chapterId: String, val ordinal: Int, val record: MemoryRecord)
data class RetrievedMemory(val text: String, val selectedIds: List<String>)
class MemoryFormatException(message: String) : RuntimeException(message)

fun memoryHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun safePrefix(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (text.length <= limit) return text
    val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
    return text.substring(0, end)
}
fun safeSuffix(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (text.length <= limit) return text
    var start = text.length - limit
    if (Character.isLowSurrogate(text[start])) start++
    return text.substring(start)
}

object MemoryLedger {
    const val DEFAULT_CHUNK = 6000
    const val WORKING_CHARS = 3600
    const val RETRIEVAL_CHARS = 7000
    private val kinds = setOf("state", "fact", "thread", "timeline")
    private val statuses = setOf("active", "open", "closed", "uncertain")
    private val space = Regex("\\s+")
    private fun normalize(s: String) = s.replace(space, "").lowercase()

    /** Split in local memory only, preferring paragraph boundaries; the original body never changes. */
    fun chunks(body: String, maxChars: Int = DEFAULT_CHUNK): List<String> {
        require(maxChars in 500..DEFAULT_CHUNK)
        if (body.isEmpty()) return listOf("")
        val out = ArrayList<String>(); var start = 0
        while (start < body.length) {
            var end = min(start + maxChars, body.length)
            if (end < body.length) {
                val paragraph = body.lastIndexOf('\n', end - 1)
                if (paragraph > start + maxChars / 2) end = paragraph + 1
                if (end > start && Character.isHighSurrogate(body[end - 1])) end--
            }
            out.add(body.substring(start, end)); start = end
        }
        return out
    }
    fun newProgress(p: Project, draft: Draft): MemoryProgress {
        // 3 bytes/CJK is a conservative estimate; leave room for schema, references and output.
        val output = min(p.settings.outputTokens, 4096)
        val chars = ((p.settings.contextTokens - output - 5000) / 3).coerceIn(500, DEFAULT_CHUNK)
        return MemoryProgress(memoryHash(draft.body), chars)
    }
    fun chunk(d: Draft): String {
        val progress = requireNotNull(d.memoryProgress)
        return chunks(d.body, progress.chunkChars).getOrElse(progress.parts.size) { "" }
    }
    fun parseDelta(text: String, bodyChunk: String, allowed: Map<String, IndexedMemory>, compact: Boolean): MemoryDelta {
        try {
            if (text.length > 18000) throw MemoryFormatException("记忆响应超出单段大小限制")
            var raw = text.trim()
            if (raw.startsWith("```")) {
                require(raw.endsWith("```")) { "JSON 代码块未结束" }
                raw = raw.substringAfter('\n').substringBeforeLast("```").trim()
            }
            val o = Json.parse(raw).obj()
            require(o.keys.all { it in setOf("summary", "records") }) { "记忆字段不符合格式" }
            val summary = o.str("summary").trim()
            require(summary.isNotBlank() && textLength(summary) <= if (compact) 350 else 650) { "章节摘要长度不符合限制" }
            val entries = (o["records"] ?: error("缺少 records")).arr()
            require(entries.size <= if (compact) 8 else 20) { "本章记忆条目过多" }
            var warnings = 0
            val normalizedBody = normalize(bodyChunk)
            val records = entries.mapNotNull { entry ->
                val r = entry.obj()
                require(r.keys.all { it in setOf("kind", "entity", "key", "value", "evidence", "importance", "status", "targetId") })
                val item = MemoryRecord(r.str("kind"), r.str("entity").trim(), r.str("key").trim(),
                    r.str("value").trim(), r.str("evidence").trim(), r.num("importance", 3).also { require(it in 1..5) }.toInt(),
                    r.str("status", "active"), r.str("targetId"))
                require(item.kind in kinds && item.status in statuses) { "记忆类型或状态无效" }
                require(item.entity.isNotBlank() && item.entity.length <= 80 && item.key.isNotBlank() && item.key.length <= 100)
                require(item.value.isNotBlank() && item.value.length <= 400 && item.evidence.length in 4..250)
                require(item.importance in 1..5)
                // An exact supporting quote is required. Invalid facts are NOT silently promoted into canon.
                if (!normalizedBody.contains(normalize(item.evidence))) { warnings++; null }
                else if (item.targetId.isNotBlank() && (item.kind != "thread" || allowed[item.targetId]?.record?.kind != "thread")) {
                    warnings++; null
                } else if (item.status == "closed" && (item.kind != "thread" || item.targetId.isBlank())) {
                    warnings++; null
                } else item
            }.distinctBy { "${it.identity()}|${it.value}|${it.status}" }
            return MemoryDelta(summary, records, warnings)
        } catch (e: MemoryFormatException) { throw e }
        catch (e: Exception) { throw MemoryFormatException("记忆 JSON 未通过校验：${e.message.orEmpty().take(120)}") }
    }
    fun index(p: Project): Map<String, IndexedMemory> {
        val result = LinkedHashMap<String, IndexedMemory>()
        for (c in p.chapters) for (part in c.memoryV2?.parts.orEmpty()) for (record in part.records) {
            val id = record.identity()
            // Only events from the live branch are replayed. State changes keep their source chapter.
            if (record.status == "closed" && result[id]?.record?.kind != "thread") continue
            result[id] = IndexedMemory(id, c.id, c.ordinal, record)
        }
        return result
    }
    fun knownReferences(p: Project, query: String, maxChars: Int = 1600): String {
        val records = index(p).values.filter { it.record.kind == "state" || it.record.kind == "thread" }
        val q = terms(query); val normalized = normalize(query)
        return pack(records.map { it to score(it, q, normalized, p.chapters.size) }.sortedByDescending { it.second }.map { pair ->
            val it = pair.first
            "${it.id}|${it.record.kind}|${it.record.entity}|${it.record.key}|${it.record.status}|${it.record.value}"
        }, maxChars)
    }
    private fun terms(text: String): Set<String> {
        val normalized = normalize(safePrefix(text, 18000))
        val result = LinkedHashSet<String>()
        Regex("[a-z0-9_-]{2,}|[\\p{IsHan}]{2,}").findAll(normalized).forEach { m ->
            val value = m.value
            if (value.length <= 3) result.add(value)
            else for (i in 0 until value.length - 1) result.add(value.substring(i, i + 2))
        }
        return result
    }
    private fun score(i: IndexedMemory, queryTerms: Set<String>, q: String, last: Int): Int {
        val r = i.record
        val matches = listOf(r.entity, r.key).count { it.length >= 2 && q.contains(normalize(it)) }
        val overlap = queryTerms.intersect(terms("${r.entity} ${r.key} ${r.value}")).size.coerceAtMost(10)
        val age = (last - i.ordinal).coerceAtLeast(0)
        return matches * 20 + overlap * 3 + r.importance * 2 +
            (if (r.kind == "thread" && r.status == "open") 8 else 0) + (12 - age).coerceAtLeast(0)
    }
    fun pack(lines: Iterable<String>, limit: Int): String {
        val out = StringBuilder()
        for (line in lines) {
            if (line.isBlank() || out.length + line.length + 1 > limit) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }
    fun working(p: Project, limit: Int = WORKING_CHARS): String {
        val lines = ArrayList<String>()
        p.chapters.lastOrNull()?.memoryV2?.summary()?.let {
            lines.add("最近一章摘要（派生索引，以正文为准）：${safePrefix(it, 1100)}")
        }
        val latestNotes = p.chapters.asReversed().firstOrNull { it.authorNote.isNotBlank() }?.let {
            "作者核对记忆（第${it.ordinal}章）：${safePrefix(it.authorNote, 1200)}"
        } ?: p.seedMemory.takeIf { it.isNotBlank() }?.let { "开篇记忆：${safePrefix(it, 1200)}" }
        latestNotes?.let(lines::add)
        lines += index(p).values.filter { it.record.kind == "state" }.sortedByDescending { it.ordinal }.map {
            "${it.id} 第${it.ordinal}章 ${it.record.entity}/${it.record.key}：${it.record.value}"
        }
        return pack(lines, limit)
    }
    fun retrieve(p: Project, query: String, limit: Int = RETRIEVAL_CHARS): RetrievedMemory {
        val idx = index(p); val selected = ArrayList<String>(); val lines = ArrayList<String>(); var used = 0
        fun add(id: String, line: String) {
            if (line.length + used + 1 <= limit) { lines += line; used += line.length + 1; selected += id }
        }
        // Include a bounded recent interval summary. Never all historical arcs.
        p.chapters.asReversed().firstOrNull { it.arcSummary.isNotBlank() }?.let {
            add("arc-${it.id}", "阶段摘要（第${(it.ordinal - 14).coerceAtLeast(1)}—${it.ordinal}章）：${safePrefix(it.arcSummary, 1200)}")
        }
        val queryTerms = terms(query); val normalizedQuery = normalize(query)
        val ranked = idx.values.map { it to score(it, queryTerms, normalizedQuery, p.chapters.size) }
            .sortedWith(compareByDescending<Pair<IndexedMemory, Int>> { it.second }.thenBy { it.first.id })
        for ((item, _) in ranked) {
            val r = item.record
            add(item.id, "${item.id} 第${item.ordinal}章 ${r.kind} ${r.entity}/${r.key} [${r.status}]：${r.value}")
        }
        // Legacy notes are losslessly kept in their original fields; only selected paragraph snippets enter prompts.
        val legacy = p.chapters.asReversed().firstOrNull { it.memoryV2 == null && it.memoryAfter.isNotBlank() }
        val docs = ArrayList<Pair<String, String>>()
        if (legacy != null) chunks(legacy.memoryAfter, 800).forEachIndexed { n, s ->
            docs += "legacy-${legacy.id}-$n" to "旧版台账片段（截至第${legacy.ordinal}章；未经增量校验）：$s"
        }
        p.chapters.forEach { c ->
            if (c.memoryV2 != null) docs += "summary-${c.id}" to "第${c.ordinal}章摘要：${safePrefix(c.memoryV2.summary(), 700)}"
        }
        val q = terms(query)
        for ((id, text) in docs.sortedWith(compareByDescending<Pair<String, String>> {
            q.intersect(terms(it.second)).size
        }.thenBy { it.first })) add(id, text)
        return RetrievedMemory(lines.joinToString("\n"), selected)
    }
    fun status(p: Project): String {
        val indexed = p.chapters.count { it.memoryV2 != null }
        val idx = index(p)
        val open = idx.values.count { it.record.kind == "thread" && it.record.status == "open" }
        val unverified = p.chapters.sumOf { it.memoryV2?.parts?.sumOf { d -> d.warningCount } ?: 0 }
        return "增量记忆：$indexed/${p.chapters.size}章 · ${idx.size}条记录 · ${open}个未回收线索" +
            (if (unverified > 0) "\n${unverified}条提取记录缺少正文证据，未纳入事实库；可在记忆页补充核对。" else "") +
            (if (indexed < p.chapters.size) "\n旧章节与原台账完整保留；旧台账按相关片段检索，不强制重新调用 API。" else "")
    }
}

object MemoryJson {
    fun recordMap(r: MemoryRecord) = mapOf("kind" to r.kind, "entity" to r.entity, "key" to r.key,
        "value" to r.value, "evidence" to r.evidence, "importance" to r.importance, "status" to r.status, "targetId" to r.targetId)
    fun deltaMap(d: MemoryDelta) = mapOf("summary" to d.summary, "records" to d.records.map(::recordMap), "warningCount" to d.warningCount)
    fun delta(o: Map<String, Any?>): MemoryDelta {
        val records = (o["records"] ?: emptyList<Any?>()).arr().map { raw -> raw.obj().let { r ->
            MemoryRecord(r.str("kind"), r.str("entity"), r.str("key"), r.str("value"), r.str("evidence"),
                r.num("importance", 3).toInt(), r.str("status", "active"), r.str("targetId"))
        } }
        require(records.size <= 20 && o.str("summary").length <= 1500 && o.num("warningCount") in 0..20)
        records.forEach { require(it.kind in setOf("state", "fact", "thread", "timeline") && it.importance in 1..5)
            require(it.value.length <= 400 && it.entity.length <= 80 && it.key.length <= 100 && it.evidence.length <= 250)
            require(it.status in setOf("active", "open", "closed", "uncertain")) }
        return MemoryDelta(o.str("summary"), records, o.num("warningCount").toInt())
    }
    fun progressMap(p: MemoryProgress) = mapOf("bodyHash" to p.bodyHash, "chunkChars" to p.chunkChars,
        "parts" to p.parts.map(::deltaMap), "compact" to p.compact)
    fun progress(o: Map<String, Any?>) = MemoryProgress(o.str("bodyHash"), o.num("chunkChars").toInt(),
        o["parts"].arr().map { delta(it.obj()) }, o.bool("compact")).also {
        require(it.chunkChars in 500..MemoryLedger.DEFAULT_CHUNK && it.parts.size <= 1000)
        require(it.bodyHash.matches(Regex("[0-9a-f]{64}")))
    }
    fun chapterMap(p: ChapterMemory) = mapOf("bodyHash" to p.bodyHash, "parts" to p.parts.map(::deltaMap))
    fun chapter(o: Map<String, Any?>) = ChapterMemory(o.str("bodyHash"), o["parts"].arr().map { delta(it.obj()) }).also {
        require(it.bodyHash.matches(Regex("[0-9a-f]{64}")) && it.parts.size <= 1000)
    }
}
