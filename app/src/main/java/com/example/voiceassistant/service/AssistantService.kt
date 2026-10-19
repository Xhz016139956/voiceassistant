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
import com.example.voiceassistant.asr.AsrEngine
import com.example.voiceassistant.asr.AsrResultFilter
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
    private val resultFilter = AsrResultFilter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

        val shown = floatingBall.show { toggleListening() }
        AssistantStateBus.update {
            it.copy(overlayVisible = shown, statusText = if (shown) "待命中" else "悬浮球不可用")
        }

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
            resultFilter.reset()
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

        asrEngine.setContinuous(true)
        asrEngine.startListening()
        AssistantStateBus.update { it.copy(statusText = "正在启动识别…", partialText = "") }
        ServiceLocator.logRepository.add(LogStage.ASR, "开始连续监听")
    }

    private val asrListener = object : AsrEngine.Listener {

        override fun onReady() {
            floatingBall.setListening(true)
            AssistantStateBus.update {
                it.copy(recording = true, level = 0f, partialText = "", statusText = "请说…")
            }
            updateNotification("正在听… 说出你的指令")
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
            parseIntent(text)
        }

        override fun onError(message: String) {
            floatingBall.setListening(false)
            floatingBall.setLevel(0f)
            AssistantStateBus.update {
                it.copy(recording = false, level = 0f, statusText = message)
            }
            ServiceLocator.logRepository.add(LogStage.ASR, message, ok = false)
            updateNotification()
        }

        override fun onRmsChanged(level: Float) {
            floatingBall.setLevel(level)
        }
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
        // 用户可能根本不在这个 App 里，光靠首页卡片和静音通知等于「没反应」
        toast(execResult.message)
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
        // 进程层面收尾：静默摘掉卡片，不再回调（scope 已 cancel）
        runCatching { confirmDialog.release() }
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
