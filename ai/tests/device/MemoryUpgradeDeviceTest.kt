/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import android.content.Intent
import android.graphics.Bitmap
import android.view.MenuInflater
import androidx.appcompat.widget.PopupMenu
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ai.core.*
import io.legado.app.data.appDb
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoryUpgradeDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private fun waitText(text: String) {
        var last: Throwable? = null
        repeat(60) {
            try { onView(withText(text)).check(matches(isDisplayed())); return }
            catch (e: Throwable) { last=e; Thread.sleep(250) }
        }
        throw AssertionError("UI missing: $text",last)
    }
    private fun screenshot(name: String) {
        val dir=File(ctx.getExternalFilesDir(null),"memory-test-screens").apply{mkdirs()}
        inst.uiAutomation.takeScreenshot()?.let { bitmap -> File(dir,"$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        };bitmap.recycle() }
    }
    @Test fun nativeCreationEntryAndBackupRepairFlow() {
        AiRuntime.init(ctx)
        val settings=NovelSettings(bible="世界观与角色设定。".repeat(450),targetChars=4000)
        val p=AiRuntime.actions.create("升级验收样本",settings)
        val oldChapters=(1..12).map { n -> Chapter(ordinal=n,title="第${n}章 旧稿",content="第${n}章原文不能丢失。".repeat(100),
            memoryAfter="旧台账：人物仍在码头调查。",createdAt=n.toLong()) }
        val text="林砚在码头收起铜扣，准备核实旧信。\n".repeat(120)
        val old=AiRuntime.store.update(p.id) {it.copy(chapters=oldChapters,draft=Draft(ordinal=13,
            stage=DraftStage.MEMORY,body=text,memoryAfter="{旧记忆被截断"),pendingPublish=true)}
        AiRuntime.reader.publish(old)
        val url=AiRuntime.reader.url(p.id)
        val book=appDb.bookDao.getBook(url)!!
        appDb.bookDao.update(book.copy(durChapterIndex=6,durChapterPos=123))
        val restored=ProjectJson.decode(ProjectJson.encodeBackup(old))
        assertEquals(oldChapters,restored.chapters);assertEquals(text,restored.draft!!.body)
        val intent=Intent(ctx,AiActivity::class.java).putExtra("projectId",p.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<AiActivity>(intent).use { scenario ->
            waitText("只整理记忆，收录本章")
            screenshot("01_saved_draft_memory_pending")
            // The normal bookshelf action remains present in the compiled Android menu resources.
            scenario.onActivity { activity ->
                val popup=PopupMenu(activity,activity.top)
                popup.menuInflater.inflate(R.menu.main_bookshelf,popup.menu)
                assertNotNull(popup.menu.findItem(R.id.menu_ai_creation))
            }
            onView(withText("只读本章草稿（不调用 API）")).perform(click())
            waitText("第13章 · 已保存草稿");screenshot("02_saved_body_preview")
            scenario.onActivity { it.showProject(p.id) }
            waitText("只整理记忆，收录本章")
            val requests=mutableListOf<Purpose>()
            val gateway=object:CompletionGateway {
                override fun complete(r:CompletionRequest,c:Cancellation,cb:(String)->Unit):CompletionResult {
                    requests += r.purpose
                    val json="""{"summary":"林砚在码头调查旧信，收起铜扣。","records":[]}"""
                    cb(json);return CompletionResult(json,"stop",100,200)
                }
            }
            NovelEngine(AiRuntime.store,gateway,AiRuntime.reader).run(p.id,RunLimits())
            val after=AiRuntime.store.get(p.id)!!
            assertEquals(listOf(Purpose.MEMORY),requests)
            assertEquals(oldChapters,after.chapters.take(12));assertEquals(text.trim(),after.chapters.last().content)
            assertEquals(13,after.chapters.size);assertNull(after.draft)
            val newBook=appDb.bookDao.getBook(url)!!
            assertEquals(6,newBook.durChapterIndex);assertEquals(123,newBook.durChapterPos)
            scenario.onActivity{it.showProject(p.id)}
            waitText("写下一章");screenshot("03_chapter13_saved")
            assertTrue(File(ctx.noBackupFilesDir,"ai_projects/upgrade_backups/${p.id}.json").exists())
        }
        AiRuntime.actions.trash(p.id);AiRuntime.actions.erase(p.id)
    }
}
