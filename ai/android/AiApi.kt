/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import io.legado.app.ai.core.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AiApiSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("ai_connection", Context.MODE_PRIVATE)
    private val keyFile = AtomicFile(File(context.noBackupFilesDir, "ai_api_key.enc"))
    private val alias = "${context.packageName}.ai.api.key"
    fun config() = ApiConfig(prefs.getString("base", "https://api.deepseek.com")!!,
        prefs.getString("writer", "deepseek-v4-flash")!!, prefs.getString("planner", "deepseek-v4-flash")!!,
        prefs.getBoolean("thinking", true), prefs.getInt("timeout", 300))
    fun save(config: ApiConfig, newKey: String?) {
        config.validate()
        if (newKey != null) saveKey(newKey)
        check(prefs.edit().putString("base", config.baseUrl.trim()).putString("writer", config.writingModel.trim())
            .putString("planner", config.planningModel.trim()).putBoolean("thinking", config.deepSeekThinkingField)
            .putInt("timeout", config.timeoutSeconds).commit()) { "连接设置保存失败" }
    }
    @Synchronized private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun hasKey() = keyFile.baseFile.exists()
    @Synchronized fun key(): String {
        if (!hasKey()) return ""
        return try {
            val parts = keyFile.readFully().toString(Charsets.UTF_8).split(':')
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw Paused("本机 Key 无法解密，请重新填写。小说内容不受影响。") }
    }
    @Synchronized private fun saveKey(value: String) {
        val key = value.trim()
        require(key.isNotBlank() && key.length <= 4096 && '\n' !in key && '\r' !in key) { "Key 格式不正确" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret())
        val data = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val out = keyFile.startWrite()
        try { out.write(data.toByteArray(Charsets.UTF_8)); keyFile.finishWrite(out) }
        catch (e: Exception) { keyFile.failWrite(out); throw e }
    }
    @Synchronized fun forgetKey() { keyFile.delete() }
    fun isDemo(id: String) = prefs.getBoolean("demo_$id", false)
    fun setDemo(id: String, value: Boolean) { prefs.edit().putBoolean("demo_$id", value).apply() }
}

/** This client does not share Legado cookies, credentials, redirects or source scripts. */
class AiHttpGateway(private val settings: AiApiSettings) : CompletionGateway {
    override fun complete(request: CompletionRequest, cancellation: Cancellation,
        onPartial: (String) -> Unit): CompletionResult {
        val config = settings.config(); config.validate(); cancellation.check()
        val key = settings.key()
        require(key.isNotBlank()) { "请先在 AI 设置中填写 Key" }
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS).build()
        val http = Request.Builder().url(ApiAddress.completionUrl(config.baseUrl))
            .header("Authorization", "Bearer $key").header("Accept", "text/event-stream")
            .post(CompletionProtocol.payload(request, config).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newCall(http); cancellation.register { call.cancel() }
        try {
            return call.execute().use { response ->
                cancellation.check()
                if (!response.isSuccessful) throw Paused(when (response.code) {
                    401, 403 -> "API 身份验证失败（${response.code}），请检查 Key 和模型权限"
                    429 -> "服务商限流或额度不足（429），已暂停，不自动重试"
                    in 300..399 -> "接口返回重定向，已拒绝向其他地址转发 Key"
                    else -> "API 请求失败（${response.code}），已暂停；请检查接口和模型名称"
                })
                val body = response.body ?: throw Paused("接口返回空响应")
                val source = body.source()
                if (response.header("Content-Type").orEmpty().contains("text/event-stream", true)) {
                    val decoder = CompletionProtocol.StreamDecoder(onPartial)
                    try {
                        while (!source.exhausted()) {
                            cancellation.check()
                            val line = source.readUtf8LineStrict(4L * 1024 * 1024)
                            decoder.line(line)
                            if (decoder.isDone()) break
                        }
                        decoder.result()
                    } catch (e: Exception) {
                        if (!call.isCanceled()) runCatching { decoder.flushPartial() }
                        throw e
                    }
                } else {
                    require(!source.request(16L * 1024 * 1024 + 1)) { "接口响应过大" }
                    CompletionProtocol.nonStreaming(source.readUtf8()).also { onPartial(it.content) }
                }
            }
        } catch (e: Exception) {
            cancellation.check()
            if (e is Paused || e is IllegalArgumentException) throw e
            throw Paused("连接中断或超时，草稿已保留，未自动重试。请检查网络后继续。")
        } finally { cancellation.clear(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
