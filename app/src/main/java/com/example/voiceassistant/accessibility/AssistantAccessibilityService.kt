package com.example.voiceassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.example.voiceassistant.data.LogStage
import com.example.voiceassistant.di.ServiceLocator

/**
 * 全局操作执行器（骨架）。
 *
 * 模块 5 在这里实现：back / home / click_text / click_index / swipe / input_text / speak_screen。
 * 骨架阶段只做「连接状态登记」，让 UI 能判断无障碍是否真的可用
 * （仅靠 Settings 判断有时与系统实际状态不同步）。
 *
 * 隐私约定：不打印整棵节点树，只按需提取文本，避免日志泄露。
 */
class AssistantAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        ServiceLocator.accessibilityService = this
        ServiceLocator.logRepository.add(LogStage.SYSTEM, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 模块 5 会在这里维护「当前窗口节点快照」，本阶段不监听内容
    }

    override fun onInterrupt() {
        ServiceLocator.logRepository.add(LogStage.SYSTEM, "无障碍服务被中断", ok = false)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        if (ServiceLocator.accessibilityService === this) {
            ServiceLocator.accessibilityService = null
        }
        ServiceLocator.logRepository.add(LogStage.SYSTEM, "无障碍服务已断开", ok = false)
        return super.onUnbind(intent)
    }

    companion object {
        /** 同一进程内可直接读取；跨进程才需要走 Settings 查询 */
        @Volatile
        var isConnected: Boolean = false
            private set
    }
}
