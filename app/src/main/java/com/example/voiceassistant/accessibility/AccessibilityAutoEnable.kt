package com.example.voiceassistant.accessibility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 自用场景下的无障碍自动注册。
 *
 * 原理：WRITE_SECURE_SETTINGS 是 development 级权限，可以用
 *   adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS
 * 授予，之后 App 就有权写 Settings.Secure，把本服务的组件名追加进
 * enabled_accessibility_services，等效于在设置里手动打开开关。
 *
 * 安全约定：
 *  - 只在列表里「追加」自己的组件，绝不覆盖或删除用户已有的其他无障碍服务
 *  - 已经存在则什么都不做
 *  - 没拿到权限就安静返回，不弹窗、不引导
 */
object AccessibilityAutoEnable {

    const val GRANT_COMMAND =
        "adb shell pm grant com.example.voiceassistant android.permission.WRITE_SECURE_SETTINGS"

    enum class Result {
        ALREADY_ENABLED,
        ENABLED,
        NO_PERMISSION,
        FAILED
    }

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    fun isOurServiceEnabled(context: Context): Boolean {
        val current = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return current.split(':').any { it.equals(componentName(context), ignoreCase = true) }
    }

    fun tryEnable(context: Context): Result {
        if (!isGranted(context)) return Result.NO_PERMISSION
        if (isOurServiceEnabled(context)) return Result.ALREADY_ENABLED

        val current = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().trim()

        val ours = componentName(context)
        val next = when {
            current.isEmpty() -> ours
            current.endsWith(":") -> current + ours
            else -> "$current:$ours"
        }

        val written = runCatching {
            Settings.Secure.putString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next
            )
        }.getOrDefault(false)

        runCatching {
            Settings.Secure.putInt(
                context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1
            )
        }

        return if (written) Result.ENABLED else Result.FAILED
    }

    /** 关闭本服务（保留其他人的服务不动） */
    fun disable(context: Context): Boolean {
        if (!isGranted(context)) return false
        val current = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val remaining = current.split(':')
            .filter { it.isNotBlank() && !it.equals(componentName(context), ignoreCase = true) }
            .joinToString(":")
        return runCatching {
            Settings.Secure.putString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, remaining
            )
        }.getOrDefault(false)
    }

    fun componentName(context: Context): String =
        "${context.packageName}/${AssistantAccessibilityService::class.java.name}"
}
