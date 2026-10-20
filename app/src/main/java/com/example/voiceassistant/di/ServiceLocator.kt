package com.example.voiceassistant.di

import android.content.Context
import com.example.voiceassistant.accessibility.AssistantAccessibilityService
import com.example.voiceassistant.data.JarvisSettings
import com.example.voiceassistant.data.LogRepository
import com.example.voiceassistant.data.SettingsRepository
import com.example.voiceassistant.intent.LlmGateway
import com.example.voiceassistant.llm.LlmConfig
import com.example.voiceassistant.llm.LlmProbeClient
import com.example.voiceassistant.llm.OpenAiCompatibleGateway
import com.example.voiceassistant.tts.SystemTtsManager
import com.example.voiceassistant.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 极简依赖容器。
 *
 * 没有引入 Hilt/Koin：总纲要求不新增未要求的第三方库，而本项目的依赖量很少，
 * 手工注入足够，且便于单模块调试。
 */
object ServiceLocator {

    private var appContextOrNull: Context? = null

    val appContext: Context
        get() = requireNotNull(appContextOrNull) { "ServiceLocator 未初始化，检查 Application 是否已注册" }

    val logRepository: LogRepository by lazy { LogRepository() }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    /** 应用内更新：检查 GitHub Release 上的 update.json，下载校验后交给系统安装器 */
    val updateManager: UpdateManager by lazy { UpdateManager(appContext) }

    /** 聊天大脑：OpenAI 兼容接口，局域网 Ollama 与云端共用一套实现 */
    val llmGateway: LlmGateway by lazy {
        OpenAiCompatibleGateway {
            LlmConfig(
                baseUrl = jarvisSettings.llmBaseUrl,
                apiKey = jarvisSettings.llmApiKey,
                model = jarvisSettings.llmModel
            )
        }
    }

    /** 连通性探测：判断地址通不通、模型存不存在，并给出可读原因 */
    val llmProbe: LlmProbeClient by lazy { LlmProbeClient() }

    /**
     * 播报引擎放到应用级：设置页要列发音人、要试听，服务里也要念，
     * 两边共用同一个实例才能保证换音色是"立刻生效"。
     */
    val tts: SystemTtsManager by lazy { SystemTtsManager(appContext) }

    /** 当前设置快照，由 [startSettingsObserver] 持续刷新 */
    @Volatile
    var jarvisSettings: JarvisSettings = JarvisSettings()
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settingsJob: Job? = null

    /** 进程级设置镜像：让 LLM 网关拿到最新配置，不必每次读 DataStore */
    fun startSettingsObserver() {
        if (settingsJob != null) return
        settingsJob = appScope.launch {
            settingsRepository.jarvis.collect { jarvisSettings = it }
        }
    }

    /** 无障碍服务连接后注册实例，断开时置空；未连接即 null（执行器据此判断可用性） */
    @Volatile
    var accessibilityService: AssistantAccessibilityService? = null
        internal set

    fun init(context: Context) {
        if (appContextOrNull == null) {
            appContextOrNull = context.applicationContext
        }
    }
}
