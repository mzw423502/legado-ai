/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.widget.CheckBox
import androidx.core.content.ContextCompat
import io.legado.app.ai.core.*

fun AiActivity.requestStart(id: String, limits: RunLimits) {
    work({
        limits.validate()
        val p = AiRuntime.store.get(id) ?: error("小说不存在")
        check(p.activeRun == null && AiRuntime.runningId == null) { "已有任务正在运行，请先停止" }
        require(p.deletedAt == null) { "小说已在回收站" }
        p.settings.validate()
        require(p.draft?.needsReview != true) { "请先检查上次未完成的草稿" }
        val demo = AiRuntime.settings.isDemo(id)
        if (!demo) { AiRuntime.settings.config().validate(); require(AiRuntime.settings.key().isNotBlank()) { "请先通过右上角 AI 设置填写 API Key" } }
        p to demo
    }) { (p, demo) ->
        val mode = when (limits.mode) {
            RunMode.ONE_CHAPTER -> "完成当前这一章后停止"
            RunMode.TO_CHAPTER -> "写到总共 ${limits.targetChapter} 章"
            RunMode.CONTINUOUS -> "持续连载，直到你停止或达到本轮上限"
        }
        val destination = if (demo) "离线演示，不连接网络、不产生 API 费用。示例输出不是模型实际生成结果。"
            else "设定、必要前文和剧情记忆将发送到：\n${AiRuntime.settings.config().baseUrl}\n正文模型：${AiRuntime.settings.config().writingModel}"
        confirm(if (demo) "开始离线体验" else "开始本轮创作", "$destination\n\n$mode\n每章目标约 ${p.settings.targetChars} 字\n本轮最多 ${limits.requestLimit} 次请求、${limits.tokenLimit} Token、${limits.maxMinutes} 分钟\n未充电且低于 ${limits.minBattery}% 电量时暂停。\n\nToken 为本机保守控制，不是服务商金额上限；已发出的请求可能在停止后仍计费。", "开始") {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 710)
            AiWritingService.start(this, id, limits)
            showProject(id)
        }
    }
}
fun AiActivity.showRunSetup(id: String) {
    page("连续创作", { showProject(id) }); pageId = id
    val infinite = CheckBox(this).apply { text = "持续连载，不设置总章数"; content.addView(this) }
    val target = field("目标总章数", "100", number = true)
    infinite.setOnCheckedChangeListener { _, checked -> target.isEnabled = !checked }
    label("自动暂停条件", true)
    val requests = field("本轮请求次数上限", "600", number = true)
    val tokens = field("本轮 Token 上限（保守控制）", "3000000", number = true)
    val minutes = field("本轮最长运行分钟数", "60", number = true)
    val battery = field("低电量暂停阈值 · 百分比", "15", number = true)
    label("章节完成即自动保存。锁屏后使用有停止按钮的前台通知；系统仍可能因省电、内存或服务时限而中断，重新打开不会自动续费。")
    button("确认连续创作设置") {
        val limits = RunLimits(if (infinite.isChecked) RunMode.CONTINUOUS else RunMode.TO_CHAPTER,
            target.text.toString().toIntOrNull() ?: 100,
            requests.text.toString().toIntOrNull() ?: error("请求次数无效"),
            tokens.text.toString().toLongOrNull() ?: error("Token 上限无效"),
            minutes.text.toString().toIntOrNull() ?: error("时间上限无效"),
            battery.text.toString().toIntOrNull() ?: error("电量阈值无效"))
        limits.validate(); requestStart(id, limits)
    }
}
fun AiActivity.showApiSettings() {
    val previous = pageId
    page("AI 设置", { if (previous != null) showProject(previous) else showHome() }, settings = false)
    pageId = previous
    val config = AiRuntime.settings.config()
    label("Key 只在本机加密保存，不进入小说备份。只有测试连接或明确开始创作时才会请求 API。")
    val base = field("API 地址", config.baseUrl)
    val key = field(if (AiRuntime.settings.hasKey()) "API Key（已保存；留空则保持不变）" else "API Key")
    key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    if (Build.VERSION.SDK_INT >= 26) key.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
    val writing = field("正文模型", config.writingModel)
    val planning = field("章节规划模型", config.planningModel)
    val thinking = CheckBox(this).apply {
        text = "使用 DeepSeek 思考参数：规划开启，正文与增量记忆关闭"
        isChecked = config.deepSeekThinkingField; content.addView(this)
    }
    label("第三方兼容接口不支持 thinking 参数时，取消勾选。模型名以你所用服务商为准。")
    val timeout = field("单次请求超时 · 秒", config.timeoutSeconds.toString(), number = true)
    fun capture(): Pair<ApiConfig, String?> {
        require(AiRuntime.runningId == null) { "请先停止当前创作，再更换 API 设置" }
        val next = ApiConfig(base.text.toString().trim(), writing.text.toString().trim(), planning.text.toString().trim(),
            thinking.isChecked, timeout.text.toString().toIntOrNull() ?: error("超时无效"))
        next.validate()
        return next to key.text.toString().trim().takeIf { it.isNotEmpty() }
    }
    button("保存设置（不调用 API）") {
        val (next, newKey) = capture()
        work({ AiRuntime.settings.save(next, newKey) }) {
            key.setText(""); if (previous != null) showProject(previous) else showHome()
        }
    }
    button("保存并测试连接（1次请求）") {
        val (next, newKey) = capture()
        confirm("测试连接", "保存本页设置，并向 ${next.baseUrl} 发起一次小型模型请求。可能产生少量费用。", "测试") {
            work({
                AiRuntime.settings.save(next, newKey)
                val messages = listOf(Message("system", "只回复连接成功，不要输出其他内容。"), Message("user", "测试连接"))
                val result = AiHttpGateway(AiRuntime.settings).complete(CompletionRequest(newId(), Purpose.CONNECTION_TEST,
                    messages, 256, tokenUpperBound(messages)), Cancellation()) {}
                require(result.content.isNotBlank() && result.finishReason == "stop") { "接口有响应但测试未正常完成，请核对模型或输出设置" }
            }) { key.setText(""); info("连接测试成功。可以回到小说开始创作。") }
        }
    }
    if (AiRuntime.settings.hasKey()) button("删除本机保存的 Key") {
        confirm("删除 Key", "只删除加密保存的 API Key，不删除小说或其他设置。", "删除 Key") {
            require(AiRuntime.runningId == null) { "请先停止当前创作" }
            work({ AiRuntime.settings.forgetKey() }) { showApiSettings() }
        }
    }
}
