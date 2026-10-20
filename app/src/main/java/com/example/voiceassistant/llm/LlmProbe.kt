package com.example.voiceassistant.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 地址归一化。
 *
 * 用户会填成各种样子：`127.0.0.1:11434`、`http://192.168.1.2:11434/`、
 * 甚至直接把 `/v1/models` 或 `/api/tags` 粘进来。全部收敛成 `scheme://host:port`，
 * 否则 OkHttp 会直接抛 "Expected URL scheme" 之类，表现出来就是"填了也连不上"。
 */
object LlmUrls {

    private val STRIP_SUFFIXES = listOf(
        "/v1/chat/completions",
        "/chat/completions",
        "/v1/completions",
        "/v1/models",
        "/api/chat",
        "/api/generate",
        "/api/tags",
        "/v1"
    )

    fun root(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.contains("://")) value = "http://$value"
        value = value.trimEnd('/')
        // 反复剥，允许 "…/v1/chat/completions/" 这种叠起来的
        var changed = true
        while (changed) {
            changed = false
            for (suffix in STRIP_SUFFIXES) {
                if (value.endsWith(suffix)) {
                    value = value.removeSuffix(suffix).trimEnd('/')
                    changed = true
                }
            }
        }
        return value
    }

    fun chatEndpoint(raw: String): String {
        val base = root(raw)
        return if (base.isEmpty()) "" else "$base/v1/chat/completions"
    }

    /** 局域网 Ollama 不带 key；云端才需要 */
    fun looksLocal(raw: String): Boolean {
        val host = root(raw).removePrefix("http://").removePrefix("https://")
        return host.startsWith("127.0.0.1") ||
            host.startsWith("localhost") ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.matches(Regex("172\\.(1[6-9]|2\\d|3[01])\\..*"))
    }
}

/** 探测结果。message 面向用户，可以直接贴到界面上 */
data class LlmProbeResult(
    val ok: Boolean,
    val message: String,
    /** 服务端真实存在的对话模型；探测失败时为空 */
    val models: List<String> = emptyList(),
    /** 建议使用的模型名（用户没填时自动选） */
    val suggestedModel: String? = null,
    /** 自动发现的可用地址（原地址不通时才有值），上层应把它存下来 */
    val suggestedBaseUrl: String? = null
)

/**
 * 真正的连通性探测。
 *
 * 之前"大脑已接"只看两个输入框是否非空，用户填了地址但模型名空着（或填了服务端
 * 没有的模型）时界面照样提示"未接/连不上"，却给不出任何原因。这里补上：
 * 先 GET 模型列表判断地址通不通，再用一次极短的 chat 确认模型真的能答。
 */
class LlmProbeClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun probe(baseUrl: String, apiKey: String, model: String): LlmProbeResult =
        withContext(Dispatchers.IO) {
            val root = LlmUrls.root(baseUrl)
            if (root.isEmpty()) {
                return@withContext LlmProbeResult(false, "地址是空的，先填 http://电脑IP:11434")
            }

            probeRoot(root, apiKey, model)?.let { return@withContext it }

            // 本机地址（adb 隧道）连不上时，自己去同一 WiFi 里把电脑找出来
            if (LlmUrls.looksLocal(root)) {
                val found = LlmDiscovery.findOllama()
                if (found != null) {
                    probeRoot(found, apiKey, model)?.let { viaLan ->
                        return@withContext viaLan.copy(
                            suggestedBaseUrl = found,
                            message = "原地址 $root 不通（adb 隧道多半断了），已自动找到电脑 $found。" +
                                viaLan.message
                        )
                    }
                }
            }

            LlmProbeResult(
                false,
                "连不上 $root（连接被拒绝或超时）。检查：电脑上的 Ollama 是否在跑、" +
                    "手机和电脑是否同一 WiFi、电脑防火墙是否放行 11434"
            )
        }

    /** 探一个具体地址；网络层就不通时返回 null，交给上层决定换地址 */
    private fun probeRoot(root: String, apiKey: String, model: String): LlmProbeResult? {
        lastHttpOk = false
        val listed = fetchModels(root, apiKey) ?: return null

        if (listed.isEmpty()) {
            return LlmProbeResult(
                false,
                "$root 通了，但服务里一个模型都没有。先在电脑上 ollama pull 一个模型"
            )
        }

        val chatModels = listed.filterNot { name ->
            val lower = name.lowercase()
            lower.contains("bge") || lower.contains("embed")
        }.ifEmpty { listed }

        val wanted = model.trim()
        val resolved = when {
            wanted.isEmpty() -> chatModels.first()
            chatModels.any { it == wanted } -> wanted
            chatModels.any { it.substringBefore(':') == wanted.substringBefore(':') } ->
                chatModels.first { it.substringBefore(':') == wanted.substringBefore(':') }
            else -> null
        }

        if (resolved == null) {
            return LlmProbeResult(
                false,
                "$root 通了，但没有模型「$wanted」。可用：${chatModels.joinToString("、")}",
                listed,
                chatModels.first()
            )
        }

        val reply = chatPing(root, apiKey, resolved)
            ?: return LlmProbeResult(
                false,
                "$root 的模型列表正常，但「$resolved」试答失败（多半是模型名不对或显存不足）",
                listed,
                resolved
            )

        return LlmProbeResult(
            true,
            "已连通 · $resolved（试答：${reply.take(24)}）",
            listed,
            resolved
        )
    }

    /** OpenAI 兼容的 /v1/models 优先，Ollama 原生的 /api/tags 兜底；都失败返回 null */
    private fun fetchModels(root: String, apiKey: String): List<String>? {
        fetch("$root/v1/models", apiKey)?.let { body ->
            val names = runCatching {
                json.parseToJsonElement(body).jsonObject["data"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            }.getOrNull()
            if (!names.isNullOrEmpty()) return names
        }
        fetch("$root/api/tags", apiKey)?.let { body ->
            val names = runCatching {
                json.parseToJsonElement(body).jsonObject["models"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            }.getOrNull()
            if (!names.isNullOrEmpty()) return names
        }
        // 两个接口都拿不到；若刚才至少有一次 HTTP 成功过（只是格式不同），返回空列表
        return if (lastHttpOk) emptyList() else null
    }

    @Volatile
    private var lastHttpOk = false

    private fun fetch(url: String, apiKey: String): String? {
        val builder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "GET $url -> ${response.code}")
                    null
                } else {
                    lastHttpOk = true
                    body
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "GET $url failed", error)
            null
        }
    }

    /** 只让它吐一两个 token，够证明模型能接话 */
    private fun chatPing(root: String, apiKey: String, model: String): String? {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("max_tokens", 8)
            put(
                "messages",
                buildJsonArray {
                    addJsonObject {
                        put("role", "user")
                        put("content", "说\"在\"")
                    }
                }
            )
        }

        val builder = Request.Builder()
            .url("$root/v1/chat/completions")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "chat ping http ${response.code}: ${body.take(200)}")
                    null
                } else {
                    // thinking 模型（qwen3 系列）在 max_tokens 很小时 content 为空，
                    // 输出全落在 reasoning 上；只要拿到 message 就说明模型确实能接话
                    val message = json.parseToJsonElement(body).jsonObject["choices"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("message")
                        ?.jsonObject
                    if (message == null) {
                        null
                    } else {
                        val content = message["content"]?.jsonPrimitive?.content.orEmpty().trim()
                        val reasoning = runCatching {
                            message["reasoning"]?.jsonPrimitive?.content.orEmpty().trim()
                        }.getOrDefault("")
                        content.ifBlank { reasoning }
                    }
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "chat ping failed", error)
            null
        }
    }

    companion object {
        private const val TAG = "JarvisLlm"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
