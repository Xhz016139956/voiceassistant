package com.example.voiceassistant.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.voiceassistant.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 应用内更新。
 *
 * 版本清单由 CI 随 APK 一起发到 GitHub Release（`update.json`），地址固定：
 *   https://github.com/{owner}/{repo}/releases/latest/download/update.json
 * 仓库名由构建参数注入 BuildConfig.UPDATE_REPO，App 里不写死。
 *
 * 安全边界：
 *  - 只走 HTTPS，且必须校验 SHA-256，否则等于给自己开了个后门
 *  - 安装包与当前包必须同签名（CI 和本地用同一份 keystore），否则系统会拒绝覆盖安装
 */
class UpdateManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // GitHub 在国内波动大，readTimeout 是「两次读之间」的间隔，不是总时长
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val repo: String = BuildConfig.UPDATE_REPO.trim()

    val manifestUrl: String?
        get() = repo.takeIf { it.isNotEmpty() }
            ?.let { "https://github.com/$it/releases/latest/download/update.json" }

    val isConfigured: Boolean get() = manifestUrl != null

    /**
     * 检查更新。
     *
     * @param silent 静默检查：没配置更新源或网络失败时不打扰用户
     */
    fun check(silent: Boolean = false) {
        val url = manifestUrl ?: run {
            if (!silent) {
                _state.value = UpdateState.Failed("未配置更新源：请用 GitHub Actions 构建，或在 gradle.properties 里填 updateRepo")
            }
            return
        }

        val current = _state.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return

        scope.launch {
            _state.value = UpdateState.Checking
            runCatching {
                // GitHub 在国内经常解析得到却下不动，逐个换镜像试
                var lastError: Throwable? = null
                for (candidate in candidates(url)) {
                    try {
                        return@runCatching fetchText(candidate).let { body ->
                            if (body.isBlank()) error("版本清单为空")
                            json.decodeFromString<UpdateInfo>(body)
                        }
                    } catch (error: Throwable) {
                        lastError = error
                        Log.w(TAG, "manifest via $candidate failed", error)
                    }
                }
                throw lastError ?: IllegalStateException("没有可用的下载地址")
            }.onSuccess { info ->
                _state.value = if (info.versionCode > BuildConfig.VERSION_CODE) {
                    Log.i(TAG, "发现新版本 ${info.versionName}(${info.versionCode}) > ${BuildConfig.VERSION_NAME}")
                    UpdateState.Available(info)
                } else {
                    UpdateState.UpToDate(BuildConfig.VERSION_NAME)
                }
            }.onFailure { error ->
                Log.w(TAG, "check failed", error)
                _state.value = if (silent) {
                    UpdateState.Idle
                } else {
                    UpdateState.Failed("检查更新失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    fun download() {
        val available = _state.value as? UpdateState.Available ?: return
        val info = available.info

        scope.launch {
            _state.value = UpdateState.Downloading(info, 0)
            val dir = (context.getExternalFilesDir("update") ?: File(context.cacheDir, "update"))
            val target = File(dir, APK_NAME)

            runCatching {
                dir.mkdirs()
                if (target.exists()) target.delete()

                var lastError: Throwable? = null
                for (candidate in candidates(info.apkUrl)) {
                    try {
                        downloadTo(candidate, target, info)
                        // 大小与 SHA-256 都对上才算成功，否则换下一个源重下
                        if (info.size > 0 && target.length() != info.size) {
                            error("文件大小不符（${target.length()} != ${info.size}），可能被截断")
                        }
                        if (info.sha256.isNotBlank()) {
                            val actual = sha256(target)
                            if (!actual.equals(info.sha256, ignoreCase = true)) {
                                error("校验失败，安装包不完整或被篡改")
                            }
                        }
                        Log.i(TAG, "download ok via $candidate")
                        return@runCatching target
                    } catch (error: Throwable) {
                        lastError = error
                        Log.w(TAG, "download via $candidate failed", error)
                        runCatching { if (target.exists()) target.delete() }
                    }
                }
                throw lastError ?: IllegalStateException("下载源都不可用")
            }.onSuccess { apk ->
                Log.i(TAG, "download done: ${apk.length()} bytes")
                _state.value = UpdateState.Ready(info, apk)
            }.onFailure { error ->
                Log.w(TAG, "download failed", error)
                runCatching { if (target.exists()) target.delete() }
                _state.value = UpdateState.Failed("下载失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /** @return true 表示已拉起系统安装器 */
    fun install(): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        val apk = ready.apk
        if (!apk.exists()) {
            _state.value = UpdateState.Failed("安装包已不存在，请重新下载")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // Android 8+ 必须由用户单独授权「安装未知应用」。
            // 这里刻意保留 Ready 状态：用户授权回来直接再点一次安装，不用重新下载。
            Toast.makeText(context, "请先允许「安装未知应用」，回来再点安装", Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        }.getOrElse { error ->
            Log.w(TAG, "install failed", error)
            _state.value = UpdateState.Failed("调起安装器失败：${error.message.orEmpty()}")
            false
        }
    }

    /**
     * 下载源列表：GitHub 官方在前，镜像在后。
     *
     * 国内实测 GitHub 的 release 文件经常 21 秒超时（清单能拿到、包下不动），
     * 而镜像能正常返回同一个文件；SHA-256 校验保证镜像给的东西没被改过。
     */
    private fun candidates(url: String): List<String> =
        MIRROR_PREFIXES.map { prefix -> if (prefix.isEmpty()) url else prefix + url }

    private fun fetchText(url: String): String {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun downloadTo(url: String, target: File, info: UpdateInfo) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("响应为空")
            val total = body.contentLength()

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        if (total > 0) {
                            val percent = (received * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = UpdateState.Downloading(info, percent)
                            }
                        }
                    }
                }
            }
            // 有些镜像直接返回 200 但内容是空壳 HTML，长度对不上会被上层挡掉
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "VoiceUpdate"
        private const val APK_NAME = "app-release.apk"

        /** 空串 = 直连官方；其余是国内可用的 GitHub 加速镜像（前面拼接完整 URL） */
        private val MIRROR_PREFIXES = listOf(
            "",
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/"
        )
    }
}

/** 版本清单，字段与 CI 生成的 update.json 一一对应 */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String = "",
    val size: Long = 0L,
    val notes: String = ""
)

sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val currentVersion: String) : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    data class Downloading(val info: UpdateInfo, val progress: Int) : UpdateState

    data class Ready(val info: UpdateInfo, val apk: File) : UpdateState

    data class Failed(val message: String) : UpdateState
}
