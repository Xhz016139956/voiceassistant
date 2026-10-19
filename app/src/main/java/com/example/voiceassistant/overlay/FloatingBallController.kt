package com.example.voiceassistant.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.example.voiceassistant.data.LogStage
import com.example.voiceassistant.di.ServiceLocator

/**
 * 悬浮球的 WindowManager 宿主。
 *
 * 权限被拒时不会崩溃，只写一条日志并返回 false，由调用方决定提示方式。
 */
class FloatingBallController(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var ballView: FloatingBallView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    val isVisible: Boolean get() = ballView != null

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun show(onTap: () -> Unit): Boolean {
        ballView?.let { existing ->
            existing.onTap = onTap
            return true
        }

        if (!canDrawOverlay()) {
            log("缺少悬浮窗权限，悬浮球未显示", ok = false)
            return false
        }

        val manager = windowManager ?: run {
            log("WindowManager 不可用，悬浮球未显示", ok = false)
            return false
        }

        val view = FloatingBallView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - dp(76)
            // 放在屏幕下三分之一处，避开首页卡片里的文字
            y = (screenHeight() * 2 / 3).coerceAtLeast(dp(80))
        }

        view.onTap = onTap
        view.onDrag = { dx, dy -> moveBy(dx, dy) }

        return runCatching {
            manager.addView(view, params)
            ballView = view
            layoutParams = params
            log("悬浮球已显示，点击开始/停止录音", ok = true)
            true
        }.getOrElse { error ->
            log("悬浮球显示失败：${error.javaClass.simpleName} ${error.message.orEmpty()}", ok = false)
            false
        }
    }

    fun hide() {
        val view = ballView ?: return
        val manager = windowManager ?: return
        runCatching { manager.removeView(view) }
        ballView = null
        layoutParams = null
        log("悬浮球已隐藏", ok = true)
    }

    fun setListening(listening: Boolean) {
        ballView?.setListening(listening)
    }

    fun setLevel(level: Float) {
        ballView?.setLevel(level)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val view = ballView ?: return
        val params = layoutParams ?: return
        val manager = windowManager ?: return

        val maxX = (screenWidth() - view.width).coerceAtLeast(0)
        val maxY = (screenHeight() - view.height).coerceAtLeast(0)

        params.x = (params.x + dx.toInt()).coerceIn(0, maxX)
        params.y = (params.y + dy.toInt()).coerceIn(0, maxY)

        runCatching { manager.updateViewLayout(view, params) }
    }

    private fun screenWidth() = context.resources.displayMetrics.widthPixels

    private fun screenHeight() = context.resources.displayMetrics.heightPixels

    private fun dp(value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    private fun log(message: String, ok: Boolean) {
        ServiceLocator.logRepository.add(LogStage.SYSTEM, message, ok = ok)
    }
}
