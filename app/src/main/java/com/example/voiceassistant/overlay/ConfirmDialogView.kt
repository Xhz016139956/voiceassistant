package com.example.voiceassistant.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 危险动作确认卡片。
 *
 * 和悬浮球一样跑在 overlay window 里，用原生控件拼装，不挂 Compose
 * （overlay 里跑 Compose 需要自己接 ViewTreeLifecycleOwner，收益不抵成本）。
 *
 * 只描述「长什么样 / 用户点了谁」，弹窗时机、超时、结果分发都由
 * [ConfirmDialogController] 与 Service 负责。
 */
class ConfirmDialogView(context: Context) : LinearLayout(context) {

    var onConfirm: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val questionView: TextView
    private val pendingView: TextView
    private val countdownView: TextView

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(14))
        background = cardBackground()

        val title = TextView(context).apply {
            text = "需要确认"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(COLOR_DANGER)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        addView(title, fullWidth())

        questionView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(COLOR_ON_SURFACE)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        addView(questionView, fullWidth(topMargin = 8))

        pendingView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(COLOR_ON_SURFACE_VARIANT)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        addView(pendingView, fullWidth(topMargin = 6))

        countdownView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(COLOR_ON_SURFACE_VARIANT)
        }
        addView(countdownView, fullWidth(topMargin = 10))

        addView(buildActions(), fullWidth(topMargin = 14))
    }

    fun bind(question: String, pendingDescription: String, seconds: Int) {
        questionView.text = question
        pendingView.text = "将执行：$pendingDescription"
        countdownView.text = "${seconds} 秒内未选择将自动取消"
    }

    fun updateCountdown(seconds: Int) {
        countdownView.text = "还剩 $seconds 秒，超时将自动取消"
    }

    private fun buildActions(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(
            buildButton("取消", COLOR_CANCEL_BG, COLOR_ON_SURFACE) { onCancel?.invoke() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(
            buildButton("确认执行", COLOR_DANGER, Color.WHITE) { onConfirm?.invoke() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
        )
    }

    private fun buildButton(
        label: String,
        fill: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(12), dp(8), dp(12))
        background = pill(fill)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun pill(fill: Int): RippleDrawable {
        val radius = dp(22).toFloat()
        val content = GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
        }
        // mask 用同形状，否则水波纹会画出圆角之外
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(48, 0, 0, 0)), content, mask)
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(COLOR_CARD)
        // 极低密度屏上 dp(1) 会取整成 0，setStroke(0) 会抛异常
        setStroke(dp(1).coerceAtLeast(1), COLOR_STROKE)
    }

    private fun fullWidth(topMargin: Int = 0): LayoutParams = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT
    ).apply {
        this.topMargin = topMargin
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private val COLOR_CARD = Color.parseColor("#FFFBFE")
        private val COLOR_STROKE = Color.parseColor("#E7E0EC")
        private val COLOR_ON_SURFACE = Color.parseColor("#1C1B1F")
        private val COLOR_ON_SURFACE_VARIANT = Color.parseColor("#49454F")
        private val COLOR_CANCEL_BG = Color.parseColor("#E7E0EC")
        private val COLOR_DANGER = Color.parseColor("#B3261E")
    }
}
