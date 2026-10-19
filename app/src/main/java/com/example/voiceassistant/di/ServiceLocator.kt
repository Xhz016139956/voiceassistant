package com.example.voiceassistant.di

import android.content.Context
import com.example.voiceassistant.accessibility.AssistantAccessibilityService
import com.example.voiceassistant.data.LogRepository
import com.example.voiceassistant.data.SettingsRepository
import com.example.voiceassistant.update.UpdateManager

/**
 * 极简依赖容器。
 *
 * 没有引入 Hilt/Koin：总纲要求不新增未要求的第三方库，而本项目的依赖量很少，
 * 手工注入足够，且便于单模块调试。
 *
 * 后续模块在此挂载：
 *  - AsrEngine（模块 3）
 *  - TtsManager（模块 6）
 *  - LlmClient（模块 9）
 */
object ServiceLocator {

    private var appContextOrNull: Context? = null

    val appContext: Context
        get() = requireNotNull(appContextOrNull) { "ServiceLocator 未初始化，检查 Application 是否已注册" }

    val logRepository: LogRepository by lazy { LogRepository() }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    /** 应用内更新：检查 GitHub Release 上的 update.json，下载校验后交给系统安装器 */
    val updateManager: UpdateManager by lazy { UpdateManager(appContext) }

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
