package com.example.voiceassistant.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.voiceassistant.assistant.JarvisPrompts
import com.example.voiceassistant.assistant.WakeWord
import com.example.voiceassistant.asr.AsrEngine
import com.example.voiceassistant.asr.AsrResultFilter
import com.example.voiceassistant.asr.EchoGuard
import com.example.voiceassistant.asr.SystemAsrEngine
import com.example.voiceassistant.data.LogStage
import com.example.voiceassistant.di.ServiceLocator
import com.example.voiceassistant.executor.AccessibilityActionExecutor
import com.example.voiceassistant.intent.Action
import com.example.voiceassistant.intent.CompositeIntentParser
import com.example.voiceassistant.intent.IntentParser
import com.example.voiceassistant.intent.ParseResult
import com.example.voiceassistant.intent.describe
import com.example.voiceassistant.overlay.ConfirmDialogController
import com.example.voiceassistant.overlay.FloatingBallController
import com.example.voiceassistant.state.AssistantStateBus
import com.example.voiceassistant.tts.SystemTtsManager
import com.example.voiceassistant.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 常驻前台服务：悬浮球 + 语音识别。
 *
 * 交互链路：
 *   点悬浮球 → 开启连续识别 → onReady 亮波纹 → onPartial 实时上屏
 *   → onFinal 落日志 → 自动接着听下一句 → 再点悬浮球停止
 */
class AssistantService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var floatingBall: FloatingBallController
    private lateinit var confirmDialog: ConfirmDialogController
    private lateinit var asrEngine: SystemAsrEngine
    private lateinit var intentParser: IntentParser
    private lateinit var executor: AccessibilityActionExecutor
    private lateinit var tts: SystemTtsManager
    private val resultFilter = AsrResultFilter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 对话态：唤醒后一直听，直到静默超时 */
    private var chatMode = false

    /** 语音唤醒开关（设置项） */
    private var wakeEnabled = false

    /** 用户手动点悬浮球开启的监听，停止时不能把唤醒模式一起关掉 */
    private var manualListening = false

    /** 前台服务就绪前不启动识别，避免踩 startForeground 的 5 秒红线 */
    private var foregroundReady = false

    private val chatTimeoutTask = Runnable { exitChatMode() }
    private val wakeRearmTask = Runnable { startWakeListening() }

    /** 播报结束后稍等再开麦，避开喇叭余音 */
    private val resumeAfterSpeakTask = Runnable { resumeIfShould() }

    /** 回声与重放过滤 */
    private val echoGuard = EchoGuard()

    /** 上层是否希望麦继续开着（暂停后据此决定要不要恢复），避免暂停变成"永久关麦" */
    private var keepListening = false

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        floatingBall = FloatingBallController(this)
        confirmDialog = ConfirmDialogController(this)

        asrEngine = SystemAsrEngine(this).apply {
            setListener(asrListener)
        }
        intentParser = CompositeIntentParser()
        executor = AccessibilityActionExecutor(this) { ServiceLocator.accessibilityService }

        tts = ServiceLocator.tts.apply {
            onFinish = { onSpeakFinished() }
            onAutoVoice = { engine, voiceKey ->
                // 换了引擎没挑音色时自动选中的那个，落盘，避免每次重建又选一遍
                scope.launch {
                    val current = ServiceLocator.jarvisSettings
                    ServiceLocator.settingsRepository.setTtsVoice(
                        engine = engine,
                        voice = voiceKey,
                        pitch = current.ttsPitch,
                        rate = current.ttsRate
                    )
                    ServiceLocator.logRepository.add(
                        LogStage.SYSTEM,
                        "已自动选用发音人：$voiceKey（引擎 $engine）"
                    )
                }
            }
            onFallback = { badEngine ->
                // 选中的引擎根本不能播中文：把配置改回"系统默认"，别让界面继续骗人
                scope.launch {
                    val current = ServiceLocator.jarvisSettings
                    ServiceLocator.settingsRepository.setTtsVoice(
                        engine = "",
                        voice = "",
                        pitch = current.ttsPitch,
                        rate = current.ttsRate
                    )
                    ServiceLocator.logRepository.add(
                        LogStage.SYSTEM,
                        "「$badEngine」不能播中文，已自动切回系统默认语音引擎",
                        ok = false
                    )
                }
            }
        }

        // 设置变化时同步：唤醒开关、静音、音色/语速
        scope.launch {
            ServiceLocator.settingsRepository.jarvis.collect { settings ->
                val wakeChanged = settings.wakeWordEnabled != wakeEnabled
                wakeEnabled = settings.wakeWordEnabled
                tts.setMuted(settings.ttsMuted)
                Log.i(
                    TAG,
                    "tts config: engine=${settings.ttsEngine.ifBlank { "default" }} " +
                        "voice=${settings.ttsVoice.ifBlank { "default" }} " +
                        "pitch=${settings.ttsPitch} rate=${settings.ttsRate}"
                )
                tts.applyConfig(
                    enginePackage = settings.ttsEngine,
                    voice = settings.ttsVoice,
                    pitch = settings.ttsPitch,
                    rate = settings.ttsRate
                )
                when {
                    wakeEnabled && !chatMode && !tts.isSpeaking() -> startWakeListening()
                    wakeChanged && !wakeEnabled && !chatMode && !manualListening -> stopListeningQuietly()
                }
            }
        }

        val available = asrEngine.isRecognitionAvailable()
        Log.i(TAG, "asrAvailable=$available")
        ServiceLocator.logRepository.add(
            LogStage.ASR,
            if (available) "语音识别引擎就绪" else "系统没有可用的语音识别服务",
            ok = available
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (!startAsForeground()) {
            ServiceLocator.logRepository.add(
                LogStage.SYSTEM,
                "助手服务启动失败：缺少麦克风权限，请先在「权限」页授权",
                ok = false
            )
            stopSelf()
            return START_NOT_STICKY
        }

        foregroundReady = true

        val shown = floatingBall.show { toggleListening() }
        AssistantStateBus.update {
            it.copy(overlayVisible = shown, statusText = if (shown) "待命中" else "悬浮球不可用")
        }
        if (wakeEnabled) startWakeListening()

        updateNotification()
        ServiceLocator.logRepository.add(
            LogStage.SYSTEM,
            if (shown) "助手服务已启动，点悬浮球开始说话" else "助手服务已启动，但悬浮球未显示"
        )
        return START_STICKY
    }

    // ---------------- 语音识别 ----------------

    private fun toggleListening() {
        if (asrEngine.isListening()) {
            asrEngine.setContinuous(false)
            asrEngine.stopListening()
            keepListening = false
            resultFilter.reset()
            manualListening = false
            chatMode = false
            mainHandler.removeCallbacks(chatTimeoutTask)
            tts.stop()
            // 停止监听等于「我不说了」，挂着的确认卡片一并作废
            confirmDialog.cancel()
            floatingBall.setListening(false)
            floatingBall.setLevel(0f)
            AssistantStateBus.update { it.copy(recording = false, level = 0f, statusText = "待命中") }
            updateNotification()
            ServiceLocator.logRepository.add(LogStage.ASR, "已停止监听")
            return
        }

        if (!PermissionHelper.isMicrophoneGranted(this)) {
            ServiceLocator.logRepository.add(LogStage.ASR, "缺少麦克风权限，无法识别", ok = false)
            return
        }

        if (!asrEngine.isRecognitionAvailable()) {
            ServiceLocator.logRepository.add(
                LogStage.ASR, "系统没有语音识别服务，识别不可用", ok = false
            )
            return
        }

        manualListening = true
        keepListening = true
        asrEngine.setContinuous(true)
        asrEngine.startListening()
        AssistantStateBus.update { it.copy(statusText = "正在启动识别…", partialText = "") }
        ServiceLocator.logRepository.add(LogStage.ASR, "开始连续监听")
    }

    private val asrListener = object : AsrEngine.Listener {

        override fun onReady() {
            floatingBall.setListening(true)
            val hint = when {
                chatMode -> "我在听…"
                wakeEnabled -> "说「${WakeWord.NAME}」唤醒我"
                else -> "请说…"
            }
            AssistantStateBus.update {
                it.copy(recording = true, level = 0f, partialText = "", statusText = hint)
            }
            updateNotification(hint)
        }

        override fun onPartial(text: String) {
            AssistantStateBus.update { it.copy(partialText = text) }
        }

        override fun onFinal(text: String) {
            if (!resultFilter.accept(text)) {
                // 小米引擎没听到人声时会反复吐兜底词，这里直接吞掉
                Log.i(TAG, "drop duplicated fallback: $text")
                floatingBall.setListening(false)
                floatingBall.setLevel(0f)
                AssistantStateBus.update { it.copy(recording = false, level = 0f, partialText = "") }
                return
            }

            val verdict = echoGuard.check(
                text,
                SystemClock.elapsedRealtime(),
                tts.isSpeaking()
            )
            if (verdict != EchoGuard.Verdict.ACCEPT) {
                Log.i(TAG, "drop $verdict: $text")
                floatingBall.setListening(false)
                floatingBall.setLevel(0f)
                AssistantStateBus.update { it.copy(recording = false, level = 0f, partialText = "") }
                return
            }

            // 这句已经交给上层：先关麦并禁止自动重启，等播报结束再开，
            // 免得 0.4s 后重开麦把自己的话听回来、同一指令执行两遍
            asrEngine.pause()

            floatingBall.setListening(false)
            floatingBall.setLevel(0f)
            AssistantStateBus.update {
                it.copy(
                    recording = false,
                    level = 0f,
                    partialText = "",
                    lastText = text,
                    statusText = "已识别"
                )
            }
            ServiceLocator.logRepository.add(LogStage.ASR, text)
            updateNotification("已识别：$text")
            dispatchRecognized(text)
        }

        override fun onError(message: String) {
            floatingBall.setListening(false)
            floatingBall.setLevel(0f)

            when {
                chatMode -> AssistantStateBus.update {
                    it.copy(recording = false, level = 0f, statusText = "我在听…")
                }

                wakeEnabled -> AssistantStateBus.update {
                    it.copy(recording = false, level = 0f, statusText = "说「${WakeWord.NAME}」唤醒我")
                }

                else -> {
                    AssistantStateBus.update {
                        it.copy(recording = false, level = 0f, statusText = message)
                    }
                    ServiceLocator.logRepository.add(LogStage.ASR, message, ok = false)
                    updateNotification()
                }
            }

            // 常驻唤醒时识别器会因「连续没听到内容」自行停下，这里续上
            if (wakeEnabled && !tts.isSpeaking() && PermissionHelper.isMicrophoneGranted(this@AssistantService)) {
                mainHandler.removeCallbacks(wakeRearmTask)
                mainHandler.postDelayed(wakeRearmTask, WAKE_REARM_DELAY_MS)
            }
        }

        override fun onRmsChanged(level: Float) {
            floatingBall.setLevel(level)
        }
    }

    // ---------------- 贾维斯：唤醒 / 对话态 ----------------

    /**
     * 一句识别结果的分流：
     *   确认弹窗在等答复 → 先当确认词处理
     *   含唤醒词         → 进对话态（同一句里带指令就直接执行）
     *   已在对话态       → 走聊天/指令
     *   其余             → 单条指令（点悬浮球的用法）
     */
    private fun dispatchRecognized(text: String) {
        if (handleSpokenConfirm(text)) return

        if (WakeWord.matches(text)) {
            val command = WakeWord.strip(text)
            enterChatMode()
            if (command.isNotEmpty()) {
                handleChatTurn(command)
            } else {
                speak(pickWakeReply())
            }
            return
        }

        if (chatMode) {
            handleChatTurn(text)
            return
        }

        parseIntent(text)
    }

    private fun enterChatMode() {
        if (!chatMode) {
            chatMode = true
            ServiceLocator.logRepository.add(LogStage.SYSTEM, "已唤醒：进入对话态")
        }
        armChatTimeout()
    }

    private fun exitChatMode() {
        if (!chatMode) return
        chatMode = false
        mainHandler.removeCallbacks(chatTimeoutTask)
        AssistantStateBus.update { it.copy(statusText = "待命中") }
        ServiceLocator.logRepository.add(LogStage.SYSTEM, "对话超时，回到待命")
        updateNotification()
        if (wakeEnabled) startWakeListening()
    }

    private fun armChatTimeout() {
        mainHandler.removeCallbacks(chatTimeoutTask)
        mainHandler.postDelayed(chatTimeoutTask, CHAT_IDLE_TIMEOUT_MS)
    }

    private fun pickWakeReply(): String =
        JarvisPrompts.WAKE_REPLIES.random()

    /**
     * 对话态下的一轮：先用本地规则（毫秒级、不花钱），规则不认的才问 LLM。
     */
    private fun handleChatTurn(text: String) {
        armChatTimeout()
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { intentParser.parse(text) }
            val cost = SystemClock.elapsedRealtime() - startedAt

            if (result is ParseResult.Success) {
                val action = result.action
                ServiceLocator.logRepository.add(
                    LogStage.INTENT, action.describe(), input = text, costMillis = cost
                )
                if (action is Action.Confirm) {
                    requestConfirmation(action)
                } else {
                    performAction(action)
                }
                return@launch
            }

            // 规则不认 → 聊天
            AssistantStateBus.update { it.copy(statusText = "贾维斯在想…") }
            updateNotification("正在思考…")

            val settings = ServiceLocator.jarvisSettings
            if (!settings.llmConfigured) {
                // 说清楚到底缺哪一项，别只丢一句"未接"
                val missing = buildList {
                    if (settings.llmBaseUrl.isBlank()) add("模型地址")
                    if (settings.llmModel.isBlank()) add("模型名")
                }.joinToString("和")
                ServiceLocator.logRepository.add(LogStage.LLM, "聊天大脑缺少：$missing", ok = false)
                speak(JarvisPrompts.NOT_CONFIGURED)
                return@launch
            }

            val answer = ServiceLocator.llmGateway.complete(JarvisPrompts.SYSTEM, text)
            if (answer.isNullOrBlank()) {
                // 失败时补一次探测，把"连不上"落到具体原因（拒绝连接/模型不存在/超时）
                val probe = withContext(Dispatchers.IO) {
                    ServiceLocator.llmProbe.probe(
                        settings.llmBaseUrl, settings.llmApiKey, settings.llmModel
                    )
                }
                val found = probe.suggestedBaseUrl
                if (found != null) {
                    // 原地址（多半是 adb 隧道）不通，但局域网里找到了电脑：换上并让用户重说一次
                    ServiceLocator.settingsRepository.setLlm(
                        baseUrl = found,
                        apiKey = settings.llmApiKey,
                        model = probe.suggestedModel ?: settings.llmModel
                    )
                    ServiceLocator.logRepository.add(
                        LogStage.LLM,
                        "原地址不通，已自动改用 $found。${probe.message}",
                        input = text,
                        ok = false
                    )
                    speak("刚才连不上，我换了个地址找到电脑了，再说一次")
                    return@launch
                }

                ServiceLocator.logRepository.add(
                    LogStage.LLM,
                    "调用失败：${probe.message}",
                    input = text,
                    ok = false
                )
                speak(JarvisPrompts.LLM_FAILED)
                return@launch
            }

            val cost2 = SystemClock.elapsedRealtime() - startedAt
            ServiceLocator.logRepository.add(
                LogStage.LLM, answer, input = text, costMillis = cost2
            )
            AssistantStateBus.update { it.copy(lastText = text, statusText = "贾维斯：$answer") }
            speak(answer)
        }
    }

    // ---------------- 播报 ----------------

    /** 开口前必须停麦，否则会把自己的话再识别一遍 */
    private fun speak(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return

        // 记下"我说过什么"，用于把随后录到的回声丢掉
        echoGuard.onSpoken(content, SystemClock.elapsedRealtime())

        // 不管当前是否在录音都要挡住自动重启：可能正有一发 0.4s 后的重启在排队
        asrEngine.pause()
        floatingBall.setListening(false)
        floatingBall.setLevel(0f)

        AssistantStateBus.update { it.copy(recording = false, level = 0f, statusText = content) }
        updateNotification(content)
        tts.speak(content)
    }

    private fun onSpeakFinished() {
        // 播报占用的时间不该算进「对话静默」里，否则一句话长一点就被判超时
        if (chatMode) armChatTimeout()
        // 喇叭余音还在，等一小会儿再开麦，否则一开就录到自己的尾音
        mainHandler.removeCallbacks(resumeAfterSpeakTask)
        mainHandler.postDelayed(resumeAfterSpeakTask, ECHO_TAIL_DELAY_MS)
    }

    private fun resumeIfShould() {
        val shouldResume = keepListening || chatMode || wakeEnabled ||
            manualListening || confirmDialog.isShowing
        if (shouldResume) resumeListening()
    }

    private fun resumeListening() {
        if (asrEngine.isListening() || tts.isSpeaking()) return
        if (!PermissionHelper.isMicrophoneGranted(this)) return
        keepListening = true
        asrEngine.setContinuous(true)
        asrEngine.startListening()
    }

    private fun startWakeListening() {
        if (!foregroundReady) return
        if (!wakeEnabled || tts.isSpeaking()) return
        if (!PermissionHelper.isMicrophoneGranted(this)) return
        if (!asrEngine.isRecognitionAvailable()) return
        if (asrEngine.isListening()) return

        keepListening = true
        asrEngine.setContinuous(true)
        asrEngine.startListening()
        AssistantStateBus.update { it.copy(statusText = "说「${WakeWord.NAME}」唤醒我") }
    }

    private fun stopListeningQuietly() {
        if (!asrEngine.isListening()) return
        keepListening = false
        asrEngine.setContinuous(false)
        asrEngine.stopListening()
        floatingBall.setListening(false)
        floatingBall.setLevel(0f)
        AssistantStateBus.update { it.copy(recording = false, level = 0f, statusText = "待命中") }
        updateNotification()
    }

    // ---------------- 意图解析与执行（模块 4-5-7） ----------------

    /**
     * 规则优先、LLM 兜底；危险动作先弹确认卡片，确认后才交给无障碍执行器。
     */
    private fun parseIntent(text: String) {
        // 确认卡片挂在屏幕上时，直接说「确认」「取消」也算回答
        if (handleSpokenConfirm(text)) return

        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) { intentParser.parse(text) }
            val cost = SystemClock.elapsedRealtime() - startedAt

            // 新指令进来，上一句待确认的操作作废（先结算，避免卡片留着挡视线）
            confirmDialog.cancel()

            when (result) {
                is ParseResult.Success -> {
                    val action = result.action
                    ServiceLocator.logRepository.add(
                        LogStage.INTENT,
                        action.describe(),
                        input = text,
                        costMillis = cost
                    )

                    Log.i(TAG, "intent: ${action.describe()} (parse ${cost}ms)")

                    if (action is Action.Confirm) {
                        requestConfirmation(action)
                    } else {
                        performAction(action)
                    }
                }

                is ParseResult.Rejected -> {
                    ServiceLocator.logRepository.add(
                        LogStage.INTENT,
                        "未理解：${result.reason}",
                        ok = false,
                        input = text,
                        costMillis = cost
                    )
                    AssistantStateBus.update { it.copy(statusText = "没听懂") }
                    toast("没听懂：${text}")
                    resumeIfShould()
                }
            }
        }
    }

    // ---------------- 确认弹窗（模块 7） ----------------

    /**
     * 危险动作：先弹确认卡片，用户点「确认执行」（或口头说「确认」）后才真正下发。
     *
     * 没有悬浮窗权限时宁可放弃执行，也不静默执行危险操作。
     */
    private fun requestConfirmation(action: Action.Confirm) {
        val target = unwrapConfirm(action)
        val description = target.describe()

        ServiceLocator.logRepository.add(
            LogStage.CONFIRM,
            "等待确认：${action.question}",
            ok = false
        )

        val shown = confirmDialog.show(
            question = action.question,
            pendingDescription = description
        ) { decision -> onConfirmDecision(target, decision) }

        if (!shown) {
            ServiceLocator.logRepository.add(
                LogStage.CONFIRM,
                "缺少悬浮窗权限，危险操作已取消：$description",
                ok = false
            )
            AssistantStateBus.update {
                it.copy(statusText = "已取消：需要悬浮窗权限才能弹出确认框")
            }
            updateNotification("已取消：需要悬浮窗权限")
            return
        }

        AssistantStateBus.update { it.copy(statusText = "等待确认：${action.question}") }
        updateNotification(action.question)
        // 念出来用户才知道有这么个卡片；播报结束后会自动恢复识别，方便直接说「确认」
        speak("${action.question} 说确认，或者说取消")
    }

    private fun onConfirmDecision(target: Action, decision: ConfirmDialogController.Decision) {
        val description = target.describe()
        when (decision) {
            ConfirmDialogController.Decision.CONFIRMED -> {
                ServiceLocator.logRepository.add(LogStage.CONFIRM, "已确认：$description", ok = true)
                scope.launch { performAction(target) }
            }

            ConfirmDialogController.Decision.CANCELLED -> {
                ServiceLocator.logRepository.add(LogStage.CONFIRM, "已取消：$description", ok = false)
                AssistantStateBus.update { it.copy(statusText = "已取消") }
                updateNotification("已取消：$description")
                toast("已取消")
            }

            ConfirmDialogController.Decision.TIMEOUT -> {
                ServiceLocator.logRepository.add(
                    LogStage.CONFIRM,
                    "超时未确认，已取消：$description",
                    ok = false
                )
                AssistantStateBus.update { it.copy(statusText = "超时未确认，已取消") }
                updateNotification("超时未确认，操作已取消")
                toast("超时未确认，已取消")
            }

            ConfirmDialogController.Decision.REPLACED -> {
                // 已被下一句指令顶掉，状态与通知交给新指令接管，这里只留日志
                ServiceLocator.logRepository.add(
                    LogStage.CONFIRM,
                    "被新指令打断，已取消：$description",
                    ok = false
                )
            }
        }
    }

    /**
     * 口头确认：只在卡片可见且整句就是确认/取消词时生效，
     * 避免「确认一下天气」这类正常指令被误判。
     */
    private fun handleSpokenConfirm(text: String): Boolean {
        if (!confirmDialog.isShowing) return false

        val normalized = text.trim().trim('。', '，', ',', '！', '!', '.', ' ')
        if (normalized.isEmpty()) return false

        return when {
            normalized in CONFIRM_WORDS -> confirmDialog.approve()
            normalized in CANCEL_WORDS -> confirmDialog.cancel()
            else -> false
        }
    }

    /** LLM 理论上能返回嵌套 confirm，执行前统一解到最内层 */
    private fun unwrapConfirm(action: Action): Action =
        if (action is Action.Confirm) unwrapConfirm(action.pending) else action

    // ---------------- 动作执行（模块 5） ----------------

    private suspend fun performAction(action: Action) {
        val description = action.describe()
        AssistantStateBus.update { it.copy(statusText = "执行中：$description") }

        val startedAt = SystemClock.elapsedRealtime()
        val execResult = executor.execute(action)
        val cost = SystemClock.elapsedRealtime() - startedAt

        Log.i(TAG, "execute: ok=${execResult.ok} cost=${cost}ms msg=${execResult.message}")
        ServiceLocator.logRepository.add(
            LogStage.EXECUTE,
            execResult.message,
            ok = execResult.ok,
            costMillis = cost
        )
        AssistantStateBus.update { it.copy(statusText = execResult.message) }
        updateNotification(execResult.message)

        // 用户可能根本不在这个 App 里，光靠首页卡片和静音通知等于「没反应」。
        // TTS 可用就念出来，不可用（或静音）退回 Toast。
        if (tts.isReady() && !ServiceLocator.jarvisSettings.ttsMuted) {
            speak(execResult.message)
        } else {
            toast(execResult.message)
            // 没播报就不会有 onSpeakFinished，这里得自己把麦接回来
            resumeIfShould()
        }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ---------------- 前台服务与通知 ----------------

    private fun startAsForeground(): Boolean {
        if (!PermissionHelper.isMicrophoneGranted(this)) return false

        val notification = NotificationHelper.buildOngoing(this, "待命中，点悬浮球开始说话")
        // API 30 起才有 FOREGROUND_SERVICE_TYPE_MICROPHONE；更低版本传 0 表示沿用 manifest 声明
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        return runCatching {
            ServiceCompat.startForeground(this, NotificationHelper.NOTIFICATION_ID, notification, type)
        }.onFailure { error ->
            Log.e(TAG, "startForeground failed", error)
            ServiceLocator.logRepository.add(
                LogStage.SYSTEM,
                "前台服务启动异常：${error.javaClass.simpleName} ${error.message.orEmpty()}",
                ok = false
            )
        }.isSuccess
    }

    private fun updateNotification(text: String = "待命中，点悬浮球开始说话") {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.buildOngoing(this, text)
            )
        }
    }

    private fun stopSelfSafely() {
        foregroundReady = false
        chatMode = false
        mainHandler.removeCallbacks(chatTimeoutTask)
        mainHandler.removeCallbacks(wakeRearmTask)
        mainHandler.removeCallbacks(resumeAfterSpeakTask)
        keepListening = false
        manualListening = false
        runCatching { tts.stop() }
        runCatching { confirmDialog.cancel() }
        runCatching { asrEngine.release() }
        runCatching { floatingBall.hide() }
        AssistantStateBus.update {
            it.copy(overlayVisible = false, recording = false, level = 0f, statusText = "已停止")
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        ServiceLocator.logRepository.add(LogStage.SYSTEM, "助手服务已停止")
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        chatMode = false
        // 进程层面收尾：静默摘掉卡片，不再回调（scope 已 cancel）
        runCatching { confirmDialog.release() }
        // TTS 是应用级共享实例，这里只停播报，不 release（设置页还要用）
        runCatching { tts.stop() }
        runCatching { asrEngine.release() }
        runCatching { floatingBall.hide() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        AssistantStateBus.update { it.copy(overlayVisible = false, recording = false, level = 0f) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "VoiceService"
        const val ACTION_STOP = "com.example.voiceassistant.action.STOP"

        /** 对话态静默多久自动退出 */
        private const val CHAT_IDLE_TIMEOUT_MS = 15_000L

        /** 唤醒监听被识别器自行停掉后，多久续上 */
        private const val WAKE_REARM_DELAY_MS = 1_200L

        /** 播报结束后等多久再开麦（喇叭余音） */
        private const val ECHO_TAIL_DELAY_MS = 500L

        /** 卡片可见时，整句等于这些词才算作答，避免误伤正常指令 */
        private val CONFIRM_WORDS = setOf("确认", "确定", "执行", "好的", "好", "可以", "是", "对")
        private val CANCEL_WORDS = setOf("取消", "不用", "不用了", "算了", "不要", "别", "否", "不是")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AssistantService::class.java))
        }

        fun stop(context: Context) {
            val intent = Intent(context, AssistantService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
