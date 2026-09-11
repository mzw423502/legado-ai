/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai.core

import java.net.URI
import java.nio.charset.StandardCharsets

data class Project(val schema: Int = 1, val id: String = newId(), val title: String,
    val settings: NovelSettings, val createdAt: Long, val updatedAt: Long = createdAt,
    val revision: Long = 0, val chapters: List<Chapter> = emptyList(), val seedMemory: String = "",
    val draft: Draft? = null, val archives: List<ArchivedTail> = emptyList(),
    val receipts: List<Receipt> = emptyList(), val activeRun: ActiveRun? = null,
    val pendingPublish: Boolean = false, val deletedAt: Long? = null, val status: String = "尚未开始创作") {
    fun memory(): String = chapters.lastOrNull()?.memoryAfter ?: seedMemory
    fun validate() {
        require(schema == 1) { "不支持此备份版本" }
        require(id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "项目 ID 无效" }
        require(title.isNotBlank() && title.length <= 200); settings.validate(); activeRun?.limits?.validate()
        require(chapters.map { it.ordinal } == (1..chapters.size).toList()) { "章节顺序不连续" }
        require(chapters.map { it.id }.toSet().size == chapters.size) { "章节 ID 重复" }
        require(chapters.all { it.content.isNotBlank() }) { "正式章节不能为空" }
        require(draft == null || draft.ordinal == chapters.size + 1) { "草稿位置与目录不一致" }
        require(archives.all { it.fromOrdinal >= 1 && it.prefixIds.size == it.fromOrdinal - 1 })
        require(receipts.all { it.reserved >= 0 && it.counted >= 0 })
    }
}
enum class Purpose { PLAN, BODY, MEMORY, CONNECTION_TEST }
data class Message(val role: String, val content: String)
data class CompletionRequest(val requestId: String, val purpose: Purpose,
    val messages: List<Message>, val maxTokens: Int, val inputTokenUpperBound: Long)
data class CompletionResult(val content: String, val finishReason: String,
    val promptTokens: Long? = null, val completionTokens: Long? = null) {
    fun reportedTokens(): Long? {
        val input = promptTokens ?: return null; val output = completionTokens ?: return null
        return if (input >= 0 && output >= 0 && input + output > 0) input + output else null
    }
}
class Paused(message: String) : RuntimeException(message)
class Cancelled : RuntimeException("任务已停止")
object ApiAddress {
    fun completionUrl(base: String): String {
        val u = URI(base.trim())
        require(u.scheme == "https") { "API 地址必须使用 HTTPS" }
        require(!u.host.isNullOrBlank() && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null) {
            "API 地址不能含用户名、Key、查询参数或片段"
        }
        val clean = base.trim().trimEnd('/')
        return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
    }
}
fun tokenUpperBound(messages: List<Message>): Long =
    messages.sumOf { it.content.toByteArray(StandardCharsets.UTF_8).size.toLong() + 64 } + 128
