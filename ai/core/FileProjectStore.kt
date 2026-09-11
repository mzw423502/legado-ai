/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai.core

import java.io.File
import java.io.FileOutputStream

class FileProjectStore(private val root: File) : ProjectStore {
    init { require(root.isDirectory || root.mkdirs()) { "无法建立 AI 小说目录" } }
    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "项目 ID 无效" }
        return File(root, "$id.json")
    }
    @Synchronized override fun get(id: String): Project? {
        val f = file(id); recover(f)
        if (!f.exists()) return null
        require(f.length() <= 64L * 1024 * 1024) { "项目文件超过 64 MiB 限制" }
        return ProjectJson.decode(f.readText(Charsets.UTF_8)).also { require(it.id == id) { "文件 ID 不匹配" } }
    }
    @Synchronized override fun all(): List<Project> {
        val names = root.listFiles().orEmpty().map { it.name }
            .filter { it.endsWith(".json") || it.endsWith(".json.bak") }
            .map { it.removeSuffix(".bak").removeSuffix(".json") }.distinct()
        return names.mapNotNull { get(it) }
    }
    @Synchronized override fun create(project: Project) {
        project.validate(); check(get(project.id) == null) { "项目已存在" }
        write(file(project.id), ProjectJson.encode(project))
    }
    @Synchronized override fun update(id: String, edit: (Project) -> Project): Project {
        val previous = get(id) ?: error("项目不存在")
        val updated = edit(previous).copy(revision = previous.revision + 1)
        require(updated.id == id); updated.validate()
        write(file(id), ProjectJson.encode(updated)); return updated
    }
    @Synchronized override fun erase(id: String, expectedRevision: Long?) {
        if (expectedRevision != null) check(get(id)?.revision == expectedRevision) { "项目刚被修改，已取消彻底删除" }
        val f = file(id)
        for (name in listOf(f, File(f.path + ".bak"), File(f.path + ".new")))
            require(!name.exists() || name.delete()) { "删除失败，内容仍保留在本地" }
    }
    private fun recover(f: File) {
        val old = File(f.path + ".bak")
        if (old.exists()) {
            require(!f.exists() || f.delete()) { "无法恢复上一份完整存档" }
            require(old.renameTo(f)) { "恢复存档失败" }
        }
        File(f.path + ".new").let { if (it.exists()) require(it.delete()) }
    }
    private fun write(f: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 64 * 1024 * 1024) { "项目超过 64 MiB 上限；原存档未改动，请拆分或导出" }
        val tmp = File(f.path + ".new"); val old = File(f.path + ".bak")
        require(!tmp.exists() || tmp.delete())
        if (f.exists()) require(f.renameTo(old)) { "无法保护上一份存档" }
        try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            require(tmp.renameTo(f)) { "无法提交新存档" }
            require(!old.exists() || old.delete()) { "无法完成存档提交" }
        } catch (e: Exception) {
            tmp.delete()
            if (old.exists()) { f.delete(); old.renameTo(f) }
            throw e
        }
    }
}
