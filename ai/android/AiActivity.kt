/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.legado.app.R
import io.legado.app.ai.core.*
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.ColorUtils
import java.io.File

class AiActivity : AppCompatActivity() {
    companion object {
        fun open(context: Context, bookUrl: String? = null) {
            context.startActivity(Intent(context, AiActivity::class.java).putExtra("bookUrl", bookUrl))
        }
    }
    lateinit var content: LinearLayout
    lateinit var top: Toolbar
    private lateinit var root: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    var backAction: (() -> Unit)? = null
    var pageId: String? = null
    var watchId: String? = null
    var statusLabel: TextView? = null
    var statsLabel: TextView? = null
    var draftLabel: TextView? = null
    var lastRunning = false
    var lastChapterCount = -1
    var persistForm: (() -> Unit)? = null
    private var stopped = false
    private val exportFile by lazy { File(cacheDir, "ai_pending_export.txt") }
    private val exportResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) work({
            require(exportFile.exists()) { "导出快照已失效，请重新导出" }
            val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
            output.use { out -> exportFile.inputStream().use { it.copyTo(out) } }
            exportFile.delete()
        }) { info("已保存到你选择的位置") }
    }
    val importResult = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work({
            val input = contentResolver.openInputStream(uri) ?: error("无法读取文件")
            val bytes = input.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = stream.read(buffer); if (n < 0) break
                    require(out.size() + n <= 64 * 1024 * 1024) { "备份超过 64 MiB 限制" }; out.write(buffer, 0, n) }
                out.toByteArray()
            }
            val backup = ProjectJson.decode(bytes.toString(Charsets.UTF_8))
            val p = AiRuntime.actions.restoreBackup(backup)
            AiRuntime.sync(p.id); p
        }) { showProject(it.id) }
    }
    private val polling = object : Runnable {
        override fun run() {
            if (stopped) return
            val id = watchId
            if (id != null) AiRuntime.io.execute {
                val p = runCatching { AiRuntime.store.get(id) }.getOrNull()
                main.post {
                    if (!stopped && watchId == id && p != null) {
                        val running = p.activeRun != null || AiRuntime.runningId == id
                        if (lastRunning != running || lastChapterCount != p.chapters.size || p.deletedAt != null) {
                            if (p.deletedAt == null) showProject(id) else showHome(true)
                        } else {
                            statusLabel?.text = p.status
                            statsLabel?.text = projectStats(p)
                            draftLabel?.text = p.draft?.let { "第${it.ordinal}章草稿 · ${textLength(it.body)}字\n${it.body.takeLast(1200)}" } ?: ""
                        }
                    }
                }
            }
            main.postDelayed(this, 1500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (ColorUtils.isColorLight(primaryColor)) R.style.AppTheme_Light else R.style.AppTheme_Dark)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(backgroundColor) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { persistForm?.invoke(); backAction?.invoke() ?: finish() }
        })
        page("AI 创作"); label("正在打开本地小说…")
        work({ AiRuntime.init(applicationContext) }) {
            val projectId = savedInstanceState?.getString("projectId") ?: intent.getStringExtra("projectId")
            val source = intent.getStringExtra("bookUrl")
            val managed = AiRuntime.reader.projectId(source)
            when {
                projectId != null -> showProject(projectId)
                managed != null -> showProject(managed)
                source != null -> showContinuation(source)
                else -> showHome()
            }
        }
    }
    override fun onResume() { super.onResume(); stopped = false; main.removeCallbacks(polling); main.postDelayed(polling, 1200) }
    override fun onPause() { persistForm?.invoke(); stopped = true; main.removeCallbacks(polling); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("projectId", pageId); super.onSaveInstanceState(outState) }
    fun page(title: String, back: (() -> Unit)? = null, settings: Boolean = true) {
        persistForm?.invoke(); persistForm = null; watchId = null; pageId = null
        statusLabel = null; statsLabel = null; draftLabel = null
        backAction = back
        root.removeAllViews()
        top = Toolbar(this).apply {
            this.title = title; setNavigationIcon(android.R.drawable.ic_menu_revert)
            navigationContentDescription = "返回"
            setNavigationOnClickListener { persistForm?.invoke(); backAction?.invoke() ?: finish() }
            if (settings) menu.add("AI 设置").setOnMenuItemClickListener { showApiSettings(); true }
        }
        root.addView(top, LinearLayout.LayoutParams(-1, dp(56)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(28)) }
        scroll.addView(content, android.view.ViewGroup.LayoutParams(-1, -2)); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    fun dp(n: Int): Int = (resources.displayMetrics.density * n + .5f).toInt()
    fun label(text: String, heading: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = if (heading) 18f else 14f
        if (heading) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(true); setPadding(0, dp(10), 0, dp(8)); content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    fun field(title: String, value: String = "", lines: Int = 1, number: Boolean = false): EditText {
        label(title, true)
        return EditText(this).apply {
            hint = title; textSize = 16f; minLines = lines; maxLines = if (lines > 1) maxOf(lines, 18) else 1
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else if (lines > 1)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else InputType.TYPE_CLASS_TEXT
            gravity = Gravity.TOP or Gravity.START; setText(value); isSaveEnabled = false
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    fun button(text: String, enabled: Boolean = true, action: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; isEnabled = enabled; minHeight = dp(48)
        setOnClickListener { action() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
    }
    fun separator() { content.addView(View(this).apply { alpha = .15f; setBackgroundColor(currentTextColor()) },
        LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(8) }) }
    private fun currentTextColor(): Int = TextView(this).currentTextColor
    fun info(text: String) { if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setMessage(text).setPositiveButton("知道了", null).show() }
    fun confirm(title: String, message: String, positive: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null)
            .setPositiveButton(positive) { _, _ -> action() }.show()
    }
    fun <T> work(block: () -> T, done: (T) -> Unit) {
        AiRuntime.io.execute {
            try { val result = block(); runOnUiThread { if (!isFinishing && !isDestroyed) done(result) } }
            catch (e: Exception) { runOnUiThread { info(e.message ?: "操作未完成，请重试") } }
        }
    }
    fun export(name: String, mime: String, contents: () -> String) {
        work({ exportFile.writeText(contents(), Charsets.UTF_8) }) {
            exportResult.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name.replace(Regex("[/\\\\:*?\"<>|]"), "_")))
        }
    }
    fun showText(title: String, text: String, back: () -> Unit) { page(title, back); label(text) }
}
