package com.example.voiceassistant.llm

import android.util.Log
import com.example.voiceassistant.intent.LlmGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class LlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = ""
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/**
 * OpenAI 兼容接口（/v1/chat/completions）。
 *
 * 同时覆盖两种后端：
 *  - 局域网 Ollama：http://192.168.x.x:11434（自动补 /v1）
 *  - 云端：DeepSeek / OpenAI / 任意兼容服务，填 baseUrl + apiKey
 *
 * 失败一律返回 null，不抛异常给上层；调用方据此降级成"说一句没连上"。
 */
class OpenAiCompatibleGateway(
    private val configProvider: () -> LlmConfig
) : LlmGateway {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 本地 4B 模型首 token 可能慢，读超时给足
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun complete(systemPrompt: String, userPrompt: String): String? =
        withContext(Dispatchers.IO) {
            val config = configProvider()
            if (!config.configured) {
                Log.w(TAG, "llm not configured")
                return@withContext null
            }

            val payload = buildJsonObject {
                put("model", config.model)
                put("stream", false)
                put("temperature", 0.6)
                put(
                    "messages",
                    buildJsonArray {
                        addJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", userPrompt)
                        }
                    }
                )
            }

            val requestBuilder = Request.Builder()
                .url(endpoint(config.baseUrl))
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("Content-Type", "application/json")

            if (config.apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
            }

            runCatching {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "http ${response.code}: ${body.take(200)}")
                        return@use null
                    }
                    extractContent(body)
                }
            }.getOrElse { error ->
                Log.w(TAG, "request failed", error)
                null
            }
        }

    private fun extractContent(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null

        root["error"]?.let { err ->
            Log.w(TAG, "server error: ${err.toString().take(200)}")
        }

        val content = root["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?: return null

        // qwen3 这类会思考的模型偶尔把思维链塞在正文里（...<｜end▁of▁thinking｜>真正回答），
        // 直接念出来会很吵；只保留最后的答案
        val answer = content.substringAfterLast("<｜end▁of▁thinking｜>").trim()
        return answer.takeIf { it.isNotEmpty() }
    }

    /** 允许用户只填到端口、漏写 http://、或误贴接口路径，统一收敛成 chat 端点 */
    private fun endpoint(raw: String): String = LlmUrls.chatEndpoint(raw)

    companion object {
        private const val TAG = "JarvisLlm"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
