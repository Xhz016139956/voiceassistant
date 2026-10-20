package com.example.voiceassistant.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 基于 Android SpeechRecognizer 的识别实现。
 *
 * 关键约束（踩过的坑）：
 *  1. SpeechRecognizer 必须在主线程创建、调用，否则直接抛异常
 *  2. 它自己会占用麦克风，所以不能和 AudioRecord 同时录音（模块 2 的
 *     RecordingLevelSource 此时必须停掉），波形改用 onRmsChanged
 *  3. 连续重启太快会拿到 ERROR_RECOGNIZER_BUSY，这里固定延迟 400ms
 *  4. 部分机型不会回调 onResults，靠 15 秒看门狗兜底
 */
class SystemAsrEngine(private val context: Context) : AsrEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listener: AsrEngine.Listener? = null

    @Volatile
    private var listening = false

    @Volatile
    private var continuous = false

    @Volatile
    private var released = false

    /**
     * 暂停态：识别结果已交出、上层正在思考或播报，期间不允许自动重启。
     * 否则会把自己 TTS 的声音录回来，同一句指令执行两遍。
     */
    @Volatile
    private var paused = false

    /** 连续"没听到内容"的轮数，用于退避与自动停止 */
    private var emptyRounds = 0

    override fun setListener(listener: AsrEngine.Listener?) {
        this.listener = listener
    }

    fun isRecognitionAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)

    fun isListening(): Boolean = listening

    /** 连续模式：一次说完自动接着听下一句，直到 [stopListening] */
    fun setContinuous(enabled: Boolean) {
        continuous = enabled
    }

    override fun startListening() {
        runOnMain {
            if (released || listening) return@runOnMain
            val engine = obtainRecognizer() ?: return@runOnMain

            // 主动开麦即视为解除暂停（恢复监听走的是这条路）
            paused = false
            listening = true
            mainHandler.removeCallbacks(timeoutTask)
            mainHandler.postDelayed(timeoutTask, TIMEOUT_MS)

            runCatching { engine.startListening(buildIntent()) }
                .onFailure { error ->
                    listening = false
                    mainHandler.removeCallbacks(timeoutTask)
                    Log.w(TAG, "startListening failed", error)
                    listener?.onError("启动识别失败：${error.message.orEmpty()}")
                }
        }
    }

    /**
     * 交出一句结果后调用：立刻关麦并禁止自动重启，等上层播报结束再 [startListening]。
     * 不这么做，小米识别器会在 400ms 后重开麦，把 TTS 的尾音录成"又一次同样指令"。
     */
    fun pause() {
        paused = true
        mainHandler.removeCallbacks(timeoutTask)
        runOnMain {
            mainHandler.removeCallbacks(timeoutTask)
            if (!listening) return@runOnMain
            listening = false
            runCatching { recognizer?.cancel() }
        }
    }

    override fun stopListening() {
        continuous = false
        runOnMain {
            mainHandler.removeCallbacks(timeoutTask)
            if (!listening) return@runOnMain
            listening = false
            runCatching { recognizer?.stopListening() }
        }
    }

    override fun release() {
        released = true
        continuous = false
        runOnMain {
            mainHandler.removeCallbacks(timeoutTask)
            listening = false
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    private fun obtainRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }

        if (!isRecognitionAvailable()) {
            listener?.onError("系统里没有可用的语音识别服务")
            return null
        }

        return runCatching {
            SpeechRecognizer.createSpeechRecognizer(context).also { created ->
                created.setRecognitionListener(recognitionListener)
                recognizer = created
                Log.i(TAG, "SpeechRecognizer created")
            }
        }.getOrElse { error ->
            Log.e(TAG, "create SpeechRecognizer failed", error)
            listener?.onError("初始化语音识别失败：${error.message.orEmpty()}")
            null
        }
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "ready for speech")
            listener?.onReady()
        }

        override fun onBeginningOfSpeech() {
            Log.i(TAG, "beginning of speech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB 实测区间约 -2..10，映射到 0..1
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            listener?.onRmsChanged(level)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            Log.i(TAG, "end of speech")
        }

        override fun onError(error: Int) {
            mainHandler.removeCallbacks(timeoutTask)
            listening = false
            val message = mapError(error)
            Log.w(TAG, "recognizer error: $error -> $message")
            listener?.onError(message)

            val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (continuous && !released) {
                if (!recoverable) {
                    continuous = false
                } else {
                    // 连续无有效语音时逐步退避，避免 0.4s 空转刷满日志
                    emptyRounds++
                    if (emptyRounds >= MAX_EMPTY_ROUNDS) {
                        emptyRounds = 0
                        continuous = false
                        listener?.onError("连续多次没有听到内容，已自动停止监听")
                    } else {
                        scheduleRestart(backoffDelay())
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            mainHandler.removeCallbacks(timeoutTask)
            listening = false

            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()

            if (text.isNotEmpty()) {
                emptyRounds = 0
                Log.i(TAG, "final: $text")
                listener?.onFinal(text)
            } else {
                Log.w(TAG, "empty result")
                listener?.onError("没有识别到内容")
            }

            if (continuous && !released) scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (text.isNotEmpty()) listener?.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private val timeoutTask = Runnable {
        if (!listening) return@Runnable
        Log.w(TAG, "recognition timeout")
        listening = false
        runCatching { recognizer?.cancel() }
        listener?.onError("识别超时，没有听到有效语音")
        if (continuous && !released) scheduleRestart()
    }

    private fun scheduleRestart(delayMs: Long = RESTART_DELAY_MS) {
        mainHandler.postDelayed({
            if (continuous && !released && !paused && !listening) startListening()
        }, delayMs)
    }

    /** 连续没听到内容时指数退避：0.4s → 0.8s → 1.6s → 3.2s → 5s 封顶 */
    private fun backoffDelay(): Long {
        val shift = emptyRounds.coerceIn(0, 4)
        return (RESTART_DELAY_MS * (1L shl shift)).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun mapError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误，检查是否联网"
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，麦克风可能被占用"
        SpeechRecognizer.ERROR_SERVER -> "识别服务异常"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到说话"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听懂，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙，稍后再试"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "不支持该语言"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "中文识别暂不可用"
        else -> "识别失败（错误码 $code）"
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    companion object {
        private const val TAG = "VoiceAsr"
        private const val LANGUAGE_TAG = "zh-CN"
        private const val TIMEOUT_MS = 15_000L
        private const val RESTART_DELAY_MS = 400L
        private const val MAX_BACKOFF_MS = 5_000L
        private const val MAX_EMPTY_ROUNDS = 5
    }
}
