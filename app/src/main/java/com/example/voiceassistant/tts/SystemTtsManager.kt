package com.example.voiceassistant.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** 一个可选发音人（来自某个已安装的 TTS 引擎） */
data class TtsVoiceOption(
    val engine: String,
    /** 唯一键：部分引擎（如小米）所有中文音色都叫 "zh"，只能用下标区分 */
    val key: String,
    val label: String
)

/** 一个已安装的 TTS 引擎 */
data class TtsEngineOption(val packageName: String, val label: String)

/**
 * 系统 TTS 实现（零依赖，用机型自带引擎）。
 *
 * 支持换引擎、换发音人、调语速/音调 —— "猴哥"这类角色音色取决于手机里
 * 装了哪些引擎和音源，这里只负责把系统提供的东西列出来并应用。
 *
 * 两个必须处理的细节：
 *  1. 播报时麦克风里会有自己的声音，必须在开口前停掉识别，说完再恢复
 *  2. 引擎可能异步初始化失败（缺中文语音包），失败时 [isReady] 保持 false，
 *     由调用方决定降级方式，绝不在这里抛异常
 */
class SystemTtsManager(private val context: Context) : TtsManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val counter = AtomicInteger(0)

    @Volatile
    private var ready = false

    @Volatile
    private var muted = false

    @Volatile
    private var speaking = false

    private var engine: TextToSpeech? = null

    /** 空 = 跟随系统默认引擎 */
    @Volatile
    private var enginePackage: String = ""

    @Volatile
    private var voiceName: String = ""

    @Volatile
    private var pitch = 1f

    @Volatile
    private var rate = 1f

    @Volatile
    private var voicesCache: List<TtsVoiceOption> = emptyList()

    /** 防止"回退默认引擎也失败"时无限重建 */
    private var fallbackTried = false

    /** 每次重建实例自增，用来判断超时回调是否已过期 */
    private var engineGeneration = 0

    /** 一句话播完（或被中断）后回调，主线程 */
    var onFinish: (() -> Unit)? = null

    /** 选中的引擎不可用、已退回系统默认时回调（参数是被放弃的引擎包名） */
    var onFallback: ((String) -> Unit)? = null

    /** 换了引擎但没指定音色时，自动挑了一个，交给上层落盘 */
    var onAutoVoice: ((engine: String, voiceKey: String) -> Unit)? = null

    init {
        createEngine()
    }

    private fun createEngine() {
        runCatching { engine?.shutdown() }
        ready = false
        val pkg = enginePackage.takeIf { it.isNotBlank() }
        val created = TextToSpeech(context.applicationContext, { status -> onInit(status) }, pkg)
        engine = created

        // 有些第三方引擎（比如视频 App 顺带注册的那个）根本不回调初始化结果，
        // 表现为"选完之后再也不出声"，这里加超时兜底
        val token = ++engineGeneration
        if (pkg != null) {
            mainHandler.postDelayed({
                if (engineGeneration == token && !ready) {
                    fallbackToSystemEngine("init timeout")
                }
            }, 5_000L)
        }
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
            }

            override fun onDone(utteranceId: String?) {
                speaking = false
                mainHandler.post { onFinish?.invoke() }
            }

            @Deprecated("被 onError(String?, Int) 取代，但低版本仍会回调")
            override fun onError(utteranceId: String?) {
                speaking = false
                mainHandler.post { onFinish?.invoke() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "speak error: $errorCode")
                speaking = false
                mainHandler.post { onFinish?.invoke() }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                speaking = false
            }
        })
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            fallbackToSystemEngine("init=$status")
            return
        }
        val tts = engine ?: return

        val result = runCatching { tts.setLanguage(Locale.SIMPLIFIED_CHINESE) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "中文语音包不可用: $result")
            ready = false
            fallbackToSystemEngine("lang=$result")
            return
        }

        fallbackTried = false
        ready = true
        // 先枚举发音人（applyVoice 要靠这份排序把持久化的下标还原成 Voice）
        refreshVoices(tts)
        applyVoice(tts)

        // 用户换了引擎但没挑音色：优先自动选第一个男声，并把选择落盘
        if (voiceName.isBlank() && enginePackage.isNotBlank()) {
            val pick = voicesCache.firstOrNull { it.label.contains("男") } ?: voicesCache.firstOrNull()
            if (pick != null) {
                voiceName = pick.key
                applyVoice(tts)
                val engine = enginePackage
                mainHandler.post { onAutoVoice?.invoke(engine, pick.key) }
            }
        }

        Log.i(
            TAG,
            "TTS ready, engine=${enginePackage.ifBlank { "系统默认" }} " +
                "voice=${voiceName.ifBlank { "默认" }} 可选=${voicesCache.joinToString { it.label }}"
        )
    }

    /**
     * 用户可能选了不具备中文能力的引擎（系统里任何声明了 TTS_SERVICE 的 App 都会列出来，
     * 比如腾讯视频那个）。初始化失败就自动退回系统默认引擎，别把播报彻底搞哑。
     */
    private fun fallbackToSystemEngine(reason: String) {
        if (enginePackage.isBlank() || fallbackTried) return
        fallbackTried = true
        val bad = enginePackage
        Log.w(TAG, "engine $bad 不可用($reason)，回退系统默认引擎")
        enginePackage = ""
        createEngine()
        mainHandler.post { onFallback?.invoke(bad) }
    }

    private fun applyVoice(tts: TextToSpeech) {
        runCatching { tts.setPitch(pitch) }
        runCatching { tts.setSpeechRate(rate) }
        val wanted = voiceName
        if (wanted.isBlank()) return
        val target = chineseVoices(tts).firstOrNull { key(it) == wanted } ?: return
        runCatching { tts.voice = target }
    }

    /**
     * 唯一键。部分引擎（小米）所有中文音色都叫 "zh"，只能拿下标区分；
     * 像 MultiTTS 这种会报出 `microsoft_zh-CN-YunxiNeural` 的，直接用名字。
     */
    private fun key(voice: Voice): String =
        if (isPlaceholder(voice.name)) "zh#${sortedCache.indexOf(voice).coerceAtLeast(0)}" else voice.name

    /** 没有区分度的名字：NOT_SET / zh / zh-CN 之类 */
    private fun isPlaceholder(name: String): Boolean {
        if (name.isBlank() || name == "NOT_SET") return true
        return name.replace("-", "").replace("_", "").all { it == 'z' || it == 'h' } ||
            name.length <= 2
    }

    private var sortedCache: List<Voice> = emptyList()

    private fun chineseVoices(tts: TextToSpeech): List<Voice> {
        val list = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { voice -> voice.locale.language.equals("zh", true) }
            .filterNot { it.name == "NOT_SET" } // 占位音色，选了也不出声
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenBy { it.locale.toLanguageTag() }
                    .thenBy { it.name }
            )
        sortedCache = list
        return list
    }

    private fun refreshVoices(tts: TextToSpeech) {
        val list = chineseVoices(tts)
        list.forEachIndexed { index, voice ->
            Log.i(
                TAG,
                "voice[$index] name=${voice.name} locale=${voice.locale.toLanguageTag()} " +
                    "quality=${voice.quality} network=${voice.isNetworkConnectionRequired} " +
                    "features=${voice.features}"
            )
        }
        voicesCache = list.mapIndexed { index, voice -> toOption(voice, index) }
    }

    private fun toOption(voice: Voice, index: Int): TtsVoiceOption =
        TtsVoiceOption(enginePackage, key(voice), describe(voice, index))

    private fun describe(voice: Voice, index: Int): String {
        if (isPlaceholder(voice.name)) {
            val tag = extraTag(voice)
            val base = "中文音色 ${index + 1}"
            return if (tag.isBlank()) base else "$base · $tag"
        }

        // MultiTTS 会把「云希」「男性」写进 features，比读 name 友好得多
        val friendly = voice.features.orEmpty()
            .filter { it.any { ch -> ch.code in 0x4E00..0x9FFF } }
            .joinToString(" · ")
        if (friendly.isNotBlank()) {
            val alias = voice.name.substringAfterLast('_').ifBlank { voice.name }
            return "$friendly（$alias）"
        }

        val tag = extraTag(voice)
        return if (tag.isBlank()) voice.name else "${voice.name} · $tag"
    }

    private fun extraTag(voice: Voice): String {
        val gender = when {
            voice.name.contains("female", true) || voice.name.contains("woman", true) -> "女声"
            voice.name.contains("male", true) || voice.name.contains("man", true) -> "男声"
            else -> ""
        }
        val quality = if (voice.quality >= Voice.QUALITY_HIGH) "高清" else ""
        return listOf(gender, quality).filter { it.isNotBlank() }.joinToString("·")
    }

    /** 当前引擎下可选的发音人（初始化完成前为空） */
    fun voices(): List<TtsVoiceOption> = voicesCache

    /** 真正在用的引擎包名（空 = 系统默认）；切引擎是异步的，界面靠它判断列表是否过期 */
    fun activeEngine(): String = enginePackage

    /** 已安装的全部 TTS 引擎（系统查询，无需初始化） */
    fun engines(): List<TtsEngineOption> = runCatching {
        engine?.getEngines().orEmpty().map { info ->
            TtsEngineOption(info.name, info.label.ifBlank { info.name })
        }
    }.getOrDefault(emptyList())

    /**
     * 应用语音配置。引擎变了就重建实例（系统 TTS 不支持运行时切引擎），
     * 只换发音人/语速/音调则立即生效。
     */
    fun applyConfig(enginePackage: String, voice: String, pitch: Float, rate: Float) {
        val target = enginePackage.trim()
        this.pitch = pitch.coerceIn(0.3f, 2.0f)
        this.rate = rate.coerceIn(0.3f, 2.5f)
        voiceName = voice.trim()

        val current = engine
        if (target == this.enginePackage && current != null) {
            applyVoice(current)
            return
        }
        this.enginePackage = target
        fallbackTried = false
        createEngine()
    }

    /** 试听：用最近的配置念一句，供设置页确认音色 */
    fun preview(text: String) {
        speak(text, interrupt = true)
    }

    override fun speak(text: String, interrupt: Boolean) {
        if (muted) {
            onFinish?.let { mainHandler.post(it) }
            return
        }
        val tts = engine
        if (tts == null || !ready) {
            Log.w(TAG, "speak skipped: not ready")
            mainHandler.post { onFinish?.invoke() }
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            mainHandler.post { onFinish?.invoke() }
            return
        }

        speaking = true
        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = "u${counter.incrementAndGet()}"
        val result = tts.speak(trimmed, queueMode, null, id)
        Log.i(
            TAG,
            "speak -> $result voice=${runCatching { tts.voice?.name }.getOrNull()} " +
                "pitch=$pitch rate=$rate text=${trimmed.take(12)}"
        )
        if (result != TextToSpeech.SUCCESS) {
            speaking = false
            mainHandler.post { onFinish?.invoke() }
        }
    }

    override fun stop() {
        speaking = false
        runCatching { engine?.stop() }
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        if (muted) stop()
    }

    override fun isReady(): Boolean = ready

    fun isSpeaking(): Boolean = speaking

    override fun release() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        speaking = false
        onFinish = null
    }

    companion object {
        private const val TAG = "JarvisTts"
    }
}
