package com.example.voiceassistant.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.voiceassistant.accessibility.AssistantAccessibilityService

enum class PermissionKind(val title: String, val description: String, val actionLabel: String) {
    MICROPHONE(
        title = "麦克风",
        description = "语音识别需要持续收音，只在你点击录音时开启",
        actionLabel = "授予权限"
    ),
    OVERLAY(
        title = "悬浮窗",
        description = "悬浮球和危险动作确认框需要显示在其他应用上层",
        actionLabel = "去开启"
    ),
    ACCESSIBILITY(
        title = "无障碍服务",
        description = "点击文字、滑动页面、读取屏幕内容都依赖它",
        actionLabel = "去设置"
    ),
    NOTIFICATION(
        title = "通知",
        description = "前台服务需要常驻通知，否则容易被系统回收",
        actionLabel = "去开启"
    ),
    BATTERY(
        title = "忽略电池优化",
        description = "避免息屏或后台一段时间后助手被系统杀掉",
        actionLabel = "去设置"
    )
}

data class PermissionState(val kind: PermissionKind, val granted: Boolean)

object PermissionHelper {

    fun snapshot(context: Context): List<PermissionState> = listOf(
        PermissionState(PermissionKind.MICROPHONE, isMicrophoneGranted(context)),
        PermissionState(PermissionKind.OVERLAY, isOverlayGranted(context)),
        PermissionState(PermissionKind.ACCESSIBILITY, isAccessibilityEnabled(context)),
        PermissionState(PermissionKind.NOTIFICATION, isNotificationGranted(context)),
        PermissionState(PermissionKind.BATTERY, isIgnoringBatteryOptimizations(context))
    )

    fun isMicrophoneGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isOverlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isNotificationGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 判断本 App 的无障碍服务是否已启用。
     *
     * 顺序很重要：HyperOS / MIUI 上 AccessibilityManager.getEnabledAccessibilityServiceList()
     * 经常返回空或不完整（实测系统已绑定但该接口查不到），所以先读 Settings.Secure 字符串，
     * 只有在读不到时才回落到 AccessibilityManager。
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val masterOn = runCatching {
            Settings.Secure.getInt(
                context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
            ) == 1
        }.getOrDefault(false)

        val enabledList = runCatching {
            Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        }.getOrDefault("")

        val ours = "${context.packageName}/${AssistantAccessibilityService::class.java.name}"
        if (enabledList.split(':').any { it.equals(ours, ignoreCase = true) }) {
            // 列表里有我们，但用户可能把总开关关了
            if (masterOn) return true
        }

        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info -> info.resolveInfo?.serviceInfo?.packageName == context.packageName }
    }

    /** 以下跳转都返回是否成功拉起，失败由调用方决定是否提示用户 */
    fun openOverlaySettings(context: Context): Boolean = safeStart(
        context,
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    )

    fun openAccessibilitySettings(context: Context): Boolean =
        safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openNotificationSettings(context: Context): Boolean = safeStart(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )

    fun openBatterySettings(context: Context): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        return safeStart(context, intent)
    }

    private fun safeStart(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
