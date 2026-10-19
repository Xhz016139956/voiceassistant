package com.example.voiceassistant

import android.app.Application
import com.example.voiceassistant.accessibility.AccessibilityAutoEnable
import com.example.voiceassistant.data.LogStage
import com.example.voiceassistant.di.ServiceLocator

class VoiceAssistantApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        autoEnableAccessibility()
    }

    /**
     * 若已通过 adb 授予 WRITE_SECURE_SETTINGS，就把本无障碍服务写进系统设置。
     * 没有权限时静默跳过（界面上无障碍一项仍显示“待开启”）。
     */
    private fun autoEnableAccessibility() {
        Thread {
            val result = AccessibilityAutoEnable.tryEnable(this)
            if (result == AccessibilityAutoEnable.Result.NO_PERMISSION) return@Thread

            val ok = result != AccessibilityAutoEnable.Result.FAILED
            val message = when (result) {
                AccessibilityAutoEnable.Result.ENABLED -> "已自动开启无障碍服务"
                AccessibilityAutoEnable.Result.ALREADY_ENABLED -> "无障碍服务已在系统中启用"
                else -> "无障碍自动开启失败，请在系统设置中手动开启"
            }
            ServiceLocator.logRepository.add(LogStage.SYSTEM, message, ok = ok)
        }.start()
    }
}
