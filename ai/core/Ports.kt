/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai.core

import java.util.concurrent.atomic.AtomicBoolean

interface ProjectStore {
    fun get(id: String): Project?
    fun all(): List<Project>
    fun create(project: Project)
    fun update(id: String, edit: (Project) -> Project): Project
    fun erase(id: String, expectedRevision: Long? = null)
}
interface CompletionGateway {
    fun complete(request: CompletionRequest, cancellation: Cancellation,
        onPartial: (String) -> Unit): CompletionResult
}
class Cancellation {
    private val stopped = AtomicBoolean(false)
    @Volatile private var onCancel: (() -> Unit)? = null
    fun check() { if (stopped.get()) throw Cancelled() }
    fun cancel() { stopped.set(true); onCancel?.invoke() }
    fun register(action: () -> Unit) { onCancel = action; if (stopped.get()) action() }
    fun clear() { onCancel = null }
}
interface Clock { fun nowMillis(): Long }
object SystemClock : Clock { override fun nowMillis() = System.currentTimeMillis() }
interface DeviceConditions { fun pauseReason(limits: RunLimits): String? }
object UnrestrictedDevice : DeviceConditions { override fun pauseReason(limits: RunLimits): String? = null }
/** Called on a worker. Publication is idempotent and must not reset reading progress. */
interface ReaderPort {
    fun publish(project: Project)
    fun remove(projectId: String)
    fun open(projectId: String)
}
class MemoryStore : ProjectStore {
    private val data = linkedMapOf<String, Project>()
    @Synchronized override fun get(id: String) = data[id]
    @Synchronized override fun all() = data.values.toList()
    @Synchronized override fun create(project: Project) {
        project.validate(); check(!data.containsKey(project.id)); data[project.id] = project
    }
    @Synchronized override fun update(id: String, edit: (Project) -> Project): Project {
        val previous = data[id] ?: error("项目不存在")
        val updated = edit(previous).copy(revision = previous.revision + 1)
        require(updated.id == previous.id); updated.validate(); data[id] = updated; return updated
    }
    @Synchronized override fun erase(id: String, expectedRevision: Long?) {
        if (expectedRevision != null) check(data[id]?.revision == expectedRevision) { "项目刚被修改，已取消彻底删除" }
        data.remove(id)
    }
}
