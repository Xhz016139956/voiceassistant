package com.example.voiceassistant.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/**
 * 危险动作确认框的 WindowManager 宿主。
 *
 * 生命周期约定：
 *  - 同一时刻最多一张卡片，新的确认请求会先结算旧卡（回调 [Decision.REPLACED]）
 *  - 每个请求必然回调且只回调一次：确认 / 取消 / 超时 / 被打断
 *  - 没有悬浮窗权限时 [show] 返回 false，由调用方决定降级策略（绝不静默执行）
 *  - 所有方法都必须在主线程调用（Service 的执行链路本来就在 Main 上）
 */
class ConfirmDialogController(private val context: Context) {

    enum class Decision { CONFIRMED, CANCELLED, TIMEOUT, REPLACED }

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var dialogView: ConfirmDialogView? = null
    private var countdown: CountDownTimer? = null
    private var pendingCallback: ((Decision) -> Unit)? = null

    val isShowing: Boolean get() = dialogView != null

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    /**
     * 弹出确认卡片。
     *
     * @param timeoutSeconds <= 0 表示不自动取消（当前调用方固定传正数）
     * @return false 表示没有悬浮窗权限 / WindowManager 不可用 / addView 失败
     */
    fun show(
        question: String,
        pendingDescription: String,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        onDecision: (Decision) -> Unit
    ): Boolean {
        // 先把上一张卡结掉，避免两张卡片叠在一起、回调丢失
        val previous = pendingCallback
        removeViews()
        previous?.invoke(Decision.REPLACED)

        if (!canDrawOverlay()) return false
        val manager = windowManager ?: return false

        val view = ConfirmDialogView(context).apply {
            bind(question, pendingDescription, timeoutSeconds.coerceAtLeast(0))
            onConfirm = { resolve(Decision.CONFIRMED) }
            onCancel = { resolve(Decision.CANCELLED) }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            // 抬到底部导航条上方，避免和系统手势区打架
            y = dp(72)
            horizontalMargin = 16f * context.resources.displayMetrics.density
        }

        return runCatching {
            manager.addView(view, params)
            dialogView = view
            pendingCallback = onDecision
            startCountdown(timeoutSeconds)
            true
        }.getOrElse {
            removeViews()
            false
        }
    }

    /** 用户口头说「确认」时走这里，效果等同点按钮 */
    fun approve(): Boolean {
        if (!isShowing) return false
        resolve(Decision.CONFIRMED)
        return true
    }

    /** 用户口头说「取消」、点悬浮球停止、服务销毁时走这里 */
    fun cancel(): Boolean {
        if (!isShowing) return false
        resolve(Decision.CANCELLED)
        return true
    }

    /** 静默清理，不触发回调（进程退出等场景） */
    fun release() {
        removeViews()
    }

    private fun startCountdown(seconds: Int) {
        countdown?.cancel()
        countdown = null
        if (seconds <= 0) return

        countdown = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remain = ((millisUntilFinished + 999) / 1000).toInt()
                dialogView?.updateCountdown(remain)
            }

            override fun onFinish() {
                resolve(Decision.TIMEOUT)
            }
        }.start()
    }

    private fun resolve(decision: Decision) {
        val callback = pendingCallback ?: return
        removeViews()
        callback(decision)
    }

    private fun removeViews() {
        pendingCallback = null
        countdown?.cancel()
        countdown = null

        val view = dialogView ?: return
        dialogView = null
        runCatching { windowManager?.removeView(view) }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 15
    }
}
