/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai.core

data class ApiConfig(val baseUrl: String = "https://api.deepseek.com",
    val writingModel: String = "deepseek-flash", val planningModel: String = "deepseek-flash",
    val deepSeekThinkingField: Boolean = true, val timeoutSeconds: Int = 300) {
    fun validate() {
        ApiAddress.completionUrl(baseUrl)
        require(writingModel.isNotBlank() && planningModel.isNotBlank()) { "请填写模型名称" }
        require(timeoutSeconds in 10..1800) { "超时须为 10—1800 秒" }
    }
}
object CompletionProtocol {
    fun payload(request: CompletionRequest, config: ApiConfig): String {
        config.validate()
        val planning = request.purpose in setOf(Purpose.PLAN, Purpose.MEMORY)
        val data = linkedMapOf<String, Any?>(
            "model" to if (planning) config.planningModel else config.writingModel,
            "messages" to request.messages.map { mapOf("role" to it.role, "content" to it.content) },
            "max_tokens" to request.maxTokens, "stream" to true,
            "stream_options" to mapOf("include_usage" to true))
        if (config.deepSeekThinkingField) data["thinking"] = mapOf("type" to if (planning) "enabled" else "disabled")
        return Json.stringify(data)
    }
    fun nonStreaming(text: String): CompletionResult {
        val o = Json.parse(text).obj()
        require(o["error"] == null) { "接口返回错误对象，请检查接口和模型配置" }
        val choice = o["choices"].arr().firstOrNull()?.obj() ?: error("接口没有 choices")
        val msg = choice["message"].obj(); val usage = o["usage"]?.obj()
        return CompletionResult(msg.str("content"), choice.str("finish_reason", "unknown"),
            usage?.get("prompt_tokens")?.let { (it as Number).toLong() },
            usage?.get("completion_tokens")?.let { (it as Number).toLong() })
    }
    class StreamDecoder(private val onPartial: (String) -> Unit = {}) {
        private val data = ArrayList<String>(); private val body = StringBuilder()
        private var finish = ""; private var input: Long? = null; private var output: Long? = null
        private var done = false; private var lastNotify = 0L; private var eventChars = 0
        fun line(line: String) {
            if (done) return
            require(line.length < 4 * 1024 * 1024) { "流响应单帧过大" }
            when {
                line.isEmpty() -> event()
                line.startsWith("data:") -> {
                    eventChars += line.length
                    require(eventChars <= 4 * 1024 * 1024) { "流事件过大" }
                    data.add(line.substring(5).trimStart())
                }
            }
        }
        private fun event() {
            if (data.isEmpty()) return
            val text = data.joinToString("\n"); data.clear(); eventChars = 0
            if (text == "[DONE]") { done = true; return }
            val o = Json.parse(text).obj()
            require(o["error"] == null) { "流响应包含服务商错误；未自动重试" }
            o["usage"]?.obj()?.let { u ->
                input = (u["prompt_tokens"] as? Number)?.toLong()
                output = (u["completion_tokens"] as? Number)?.toLong()
            }
            val choices = o["choices"] as? List<*> ?: emptyList<Any?>()
            for (item in choices) {
                val c = item.obj(); if (c.num("index") != 0L) continue
                c["finish_reason"]?.let { finish = it as? String ?: "unknown" }
                val delta = c["delta"]?.obj() ?: continue
                val content = delta["content"] as? String ?: ""
                if (content.isNotEmpty()) {
                    body.append(content)
                    require(body.length <= 16 * 1024 * 1024) { "正文响应过大" }
                    val now = System.nanoTime()
                    if (now - lastNotify > 300_000_000L) { onPartial(body.toString()); lastNotify = now }
                }
            }
        }
        fun isDone(): Boolean = done
        fun flushPartial() { if (body.isNotEmpty()) onPartial(body.toString()) }
        fun result(): CompletionResult {
            event(); flushPartial()
            require(done && finish.isNotBlank()) { "流连接提前断开，草稿已保留；不将截断内容自动收录" }
            return CompletionResult(body.toString(), finish, input, output)
        }
    }
}
