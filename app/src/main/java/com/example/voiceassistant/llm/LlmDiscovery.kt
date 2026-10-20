package com.example.voiceassistant.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * 在同一 WiFi 里找跑着 Ollama 的那台电脑。
 *
 * 为什么需要：App 原来靠 `adb reverse` 把手机的 127.0.0.1:11434 映射到电脑，
 * 可 adb 一断（拔线、无线调试关掉、手机重启）隧道就没了，用户看到的就是
 * "连不上网"。这里改成自己扫一遍当前网段，找到就直接用局域网地址。
 */
object LlmDiscovery {

    private const val TAG = "JarvisLlm"

    private val client = OkHttpClient.Builder()
        .connectTimeout(350, TimeUnit.MILLISECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .callTimeout(900, TimeUnit.MILLISECONDS)
        .build()

    /** 手机自己所在网段的 /24 前缀，例如 "192.168.1."；拿不到返回 null */
    fun localSubnet(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
            ?.substringBeforeLast('.')
            ?.plus(".")
    }.getOrNull()

    /**
     * 扫描网段内的 11434 端口，返回第一个能正常响应 `/api/version` 的地址。
     * 254 个地址并发探测，单次超时 350ms，整体通常 1~2 秒。
     */
    suspend fun findOllama(port: Int = 11434): String? = withContext(Dispatchers.IO) {
        val prefix = localSubnet() ?: return@withContext null
        Log.i(TAG, "开始在 ${prefix}0/24 上找 Ollama")

        coroutineScope {
            (1..254).map { host ->
                async {
                    val url = "http://$prefix$host:$port"
                    if (isOllama(url)) url else null
                }
            }.awaitAll().firstOrNull { it != null }
        }?.also { Log.i(TAG, "找到 Ollama：$it") }
    }

    private fun isOllama(baseUrl: String): Boolean = runCatching {
        val request = Request.Builder().url("$baseUrl/api/version").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body?.string().orEmpty()
            body.contains("version")
        }
    }.getOrDefault(false)
}
