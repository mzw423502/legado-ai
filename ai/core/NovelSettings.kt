/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai.core

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun textLength(s: String): Int = s.codePointCount(0, s.length)

data class NovelSettings(
    val bible: String = "", val outline: String = "", val director: String = "",
    val targetChars: Int = 4000, val contextTokens: Int = 128000,
    val outputTokens: Int = 16384, val recentChapters: Int = 3, val maxParts: Int = 8
) {
    fun validate() {
        require(targetChars in 500..30000) { "章长应在 500—30000 字之间" }
        require(contextTokens in 8192..2_000_000) { "请填写模型实际支持的上下文上限" }
        require(outputTokens in 1024..131072 && outputTokens < contextTokens) { "单次输出上限无效" }
        require(recentChapters in 0..5 && maxParts in 1..20)
        require(bible.isNotBlank()) { "请先填写世界观和人设总设定" }
    }
}
enum class DraftStage { PLAN, BODY, MEMORY, READY }
data class Draft(val chapterId: String = newId(), val ordinal: Int,
    val stage: DraftStage = DraftStage.PLAN, val plan: String = "", val body: String = "",
    val memoryAfter: String = "", val completedParts: Int = 0,
    val needsReview: Boolean = false, val note: String = "")
data class Chapter(val id: String = newId(), val ordinal: Int, val title: String,
    val content: String, val memoryAfter: String, val createdAt: Long)
data class ArchivedTail(val id: String = newId(), val fromOrdinal: Int,
    val prefixIds: List<String>, val chapters: List<Chapter>, val draft: Draft?, val createdAt: Long)
enum class RunMode { ONE_CHAPTER, TO_CHAPTER, CONTINUOUS }
data class RunLimits(val mode: RunMode = RunMode.ONE_CHAPTER, val targetChapter: Int = 100,
    val requestLimit: Int = 600, val tokenLimit: Long = 3_000_000,
    val maxMinutes: Int = 60, val minBattery: Int = 15) {
    fun validate() {
        require(targetChapter in 1..100000); require(requestLimit in 1..100000)
        require(tokenLimit in 1024..1_000_000_000L); require(maxMinutes in 1..1440)
        require(minBattery in 5..95)
    }
}
data class ActiveRun(val id: String = newId(), val limits: RunLimits,
    val startedAt: Long, val initialChapterCount: Int)
enum class ReceiptStatus { RESERVED, REPORTED, ESTIMATED }
data class Receipt(val id: String = newId(), val runId: String, val purpose: Purpose,
    val reserved: Long, val counted: Long = reserved, val status: ReceiptStatus = ReceiptStatus.RESERVED,
    val startedAt: Long, val completedAt: Long? = null)
