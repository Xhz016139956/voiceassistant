package com.example.voiceassistant.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voiceassistant.data.LogStage
import com.example.voiceassistant.di.ServiceLocator
import com.example.voiceassistant.llm.LlmUrls
import com.example.voiceassistant.service.AssistantService
import com.example.voiceassistant.state.AssistantStateBus
import com.example.voiceassistant.state.LlmStatusBus
import com.example.voiceassistant.ui.screen.HomeScreen
import com.example.voiceassistant.ui.screen.LogScreen
import com.example.voiceassistant.ui.screen.PermissionScreen
import com.example.voiceassistant.util.PermissionHelper
import com.example.voiceassistant.util.PermissionKind
import com.example.voiceassistant.util.PermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class TabItem(val label: String, val icon: ImageVector)

private val TabItems = listOf(
    TabItem("控制", Icons.Filled.Home),
    TabItem("权限", Icons.Filled.Lock),
    TabItem("日志", Icons.Filled.List)
)

@Composable
fun AppRoot() {
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val logRepository = ServiceLocator.logRepository
    val settingsRepository = ServiceLocator.settingsRepository

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(PermissionHelper.snapshot(context)) }

    val logs by logRepository.logs.collectAsStateWithLifecycle()
    val runtime by AssistantStateBus.state.collectAsStateWithLifecycle()
    val llmStatus by LlmStatusBus.state.collectAsStateWithLifecycle()
    val probeText = llmStatus?.text.orEmpty()
    val probeOk = llmStatus?.ok
    val updateState by ServiceLocator.updateManager.state.collectAsStateWithLifecycle()
    val jarvis by settingsRepository.jarvis.collectAsStateWithLifecycle(
        initialValue = ServiceLocator.jarvisSettings
    )

    // 发音人列表来自 TTS 引擎，引擎是异步初始化的，这里轮询等它就绪
    var ttsVoices by remember { mutableStateOf(ServiceLocator.tts.voices()) }
    var ttsEngines by remember { mutableStateOf(ServiceLocator.tts.engines()) }
    LaunchedEffect(jarvis.ttsEngine) {
        ttsEngines = ServiceLocator.tts.engines()
        // 切引擎是异步重建的：必须等到"在用的引擎"真的换过来了，
        // 否则会读到上一个引擎的发音人清单（表现就是"换了还是老音色"）
        for (attempt in 0 until 20) {
            val switched = ServiceLocator.tts.activeEngine() == jarvis.ttsEngine
            if (switched && ServiceLocator.tts.voices().isNotEmpty()) break
            delay(300)
        }
        ttsVoices = ServiceLocator.tts.voices()
    }

    fun refreshPermissions() {
        permissions = PermissionHelper.snapshot(context)
    }

    // 冷启动时同步开关状态；若上次是开启的（例如进程被系统杀掉），在这里恢复常驻服务
    LaunchedEffect(Unit) {
        val saved = settingsRepository.assistantEnabled.first()
        enabled = saved
        if (saved && PermissionHelper.isMicrophoneGranted(context)) {
            AssistantService.start(context)
        }
        // 静默检查一次更新：没配置更新源或没网都不会打扰用户
        ServiceLocator.updateManager.check(silent = true)
    }

    // 从系统设置页返回时刷新（悬浮窗/无障碍只能在外部页面开启）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshPermissions()
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPermissions()
        logRepository.add(
            LogStage.SYSTEM,
            if (granted) "麦克风权限已授予" else "麦克风权限被拒绝，语音功能不可用",
            ok = granted
        )
        if (granted) {
            // 用户刚刚授权，直接把他刚才想要的开关打开
            enabled = true
            scope.launch { settingsRepository.setAssistantEnabled(true) }
            AssistantService.start(context)
            logRepository.add(LogStage.SYSTEM, "助手已启用")
        }
    }

    fun hasMicPermission(): Boolean =
        permissions.firstOrNull { it.kind == PermissionKind.MICROPHONE }?.granted == true

    fun onToggle(next: Boolean) {
        if (next && !hasMicPermission()) {
            logRepository.add(LogStage.SYSTEM, "需要先授予麦克风权限", ok = false)
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        enabled = next
        scope.launch { settingsRepository.setAssistantEnabled(next) }
        if (next) {
            AssistantService.start(context)
        } else {
            AssistantService.stop(context)
        }
        logRepository.add(LogStage.SYSTEM, if (next) "助手已启用" else "助手已停用")
    }

    fun onRequestPermission(kind: PermissionKind) {
        val launched = when (kind) {
            PermissionKind.MICROPHONE -> {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                true
            }

            PermissionKind.OVERLAY -> PermissionHelper.openOverlaySettings(context)
            PermissionKind.ACCESSIBILITY -> PermissionHelper.openAccessibilitySettings(context)
            PermissionKind.NOTIFICATION -> PermissionHelper.openNotificationSettings(context)
            PermissionKind.BATTERY -> PermissionHelper.openBatterySettings(context)
        }
        if (!launched) {
            logRepository.add(LogStage.SYSTEM, "无法打开「${kind.title}」的设置页", ok = false)
        }
        refreshPermissions()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TabItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    enabled = enabled,
                    permissions = permissions,
                    runtime = runtime,
                    updateState = updateState,
                    jarvis = jarvis,
                    onToggle = { next -> onToggle(next) },
                    onOpenPermissionTab = { selectedTab = 1 },
                    onCheckUpdate = { ServiceLocator.updateManager.check() },
                    onDownloadUpdate = { ServiceLocator.updateManager.download() },
                    onInstallUpdate = { ServiceLocator.updateManager.install() },
                    onToggleWake = { next ->
                        scope.launch { settingsRepository.setWakeWordEnabled(next) }
                        logRepository.add(
                            LogStage.SYSTEM,
                            if (next) "语音唤醒已开启，说「贾维斯」试试" else "语音唤醒已关闭"
                        )
                    },
                    onSaveLlm = { baseUrl, apiKey, model ->
                        scope.launch {
                            LlmStatusBus.testing()
                            val root = LlmUrls.root(baseUrl)
                            // 先存地址再探活，探到的模型名才有地方落
                            settingsRepository.setLlm(root, apiKey, model)

                            val result = ServiceLocator.llmProbe.probe(root, apiKey, model)
                            LlmStatusBus.publish(result.ok, result.message)

                            // 模型名留空或服务端没有这个模型时，自动落到真实存在的那个
                            val finalModel = when {
                                result.ok && model.isNotBlank() -> model.trim()
                                result.suggestedModel != null -> result.suggestedModel
                                else -> model.trim()
                            }
                            // 原地址不通但自动找到了电脑：把地址改成能用的那个，下次直接连
                            val finalRoot = result.suggestedBaseUrl ?: root
                            if (finalModel != model.trim() || finalRoot != root) {
                                settingsRepository.setLlm(finalRoot, apiKey, finalModel)
                            }

                            logRepository.add(
                                LogStage.LLM,
                                buildString {
                                    append(result.message)
                                    if (finalRoot != root) {
                                        append("；地址已更正为 ")
                                        append(finalRoot)
                                    }
                                    if (finalModel.isNotBlank() && finalModel != model.trim()) {
                                        append("；模型名已按服务端校正为 ")
                                        append(finalModel)
                                    }
                                },
                                ok = result.ok
                            )
                        }
                    },
                    onTestLlm = { baseUrl, apiKey, model ->
                        scope.launch {
                            LlmStatusBus.testing()
                            val result = ServiceLocator.llmProbe.probe(baseUrl, apiKey, model)
                            LlmStatusBus.publish(result.ok, result.message)
                            logRepository.add(LogStage.LLM, "连接测试：${result.message}", ok = result.ok)
                        }
                    },
                    onPersistLlm = { baseUrl, apiKey, model ->
                        // 失焦自动落盘：不探测、不打扰，只保证"填了就是填了"
                        scope.launch {
                            settingsRepository.setLlm(LlmUrls.root(baseUrl), apiKey, model)
                        }
                    },
                    probeText = probeText,
                    probeOk = probeOk,
                    ttsVoices = ttsVoices,
                    ttsEngines = ttsEngines,
                    onSaveTts = { engine, voice, pitch, rate ->
                        scope.launch {
                            settingsRepository.setTtsVoice(engine, voice, pitch, rate)
                            ServiceLocator.tts.applyConfig(engine, voice, pitch, rate)
                        }
                    },
                    onPreviewTts = { text -> ServiceLocator.tts.preview(text) },
                    onToggleTtsMuted = { muted ->
                        scope.launch { settingsRepository.setTtsMuted(muted) }
                    }
                )

                1 -> PermissionScreen(
                    permissions = permissions,
                    onRequest = { kind -> onRequestPermission(kind) },
                    onRefresh = { refreshPermissions() }
                )

                else -> LogScreen(
                    logs = logs,
                    onClear = { logRepository.clear() },
                    onCopyAll = {
                        clipboard.setText(AnnotatedString(logRepository.exportText()))
                        logRepository.add(LogStage.SYSTEM, "日志已复制到剪贴板")
                    }
                )
            }
        }
    }
}
