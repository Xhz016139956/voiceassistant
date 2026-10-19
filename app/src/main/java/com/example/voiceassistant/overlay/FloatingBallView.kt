package com.example.voiceassistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 悬浮球。
 *
 * 纯 Canvas 绘制，不依赖 Compose —— overlay window 里跑 Compose 需要自己接
 * ViewTreeLifecycleOwner/SavedStateRegistryOwner，坑多且没必要。
 *
 * 交互：
 *  - 点击（位移小于 touchSlop）→ onTap
 *  - 拖动 → onDrag(dx, dy)，由控制器更新 WindowManager.LayoutParams
 */
class FloatingBallView(context: Context) : View(context) {

    var onTap: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val ballRadius = dp(28f)
    private val borderWidth = dp(2f)

    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E66750A4")
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = Color.parseColor("#806750A4")
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#556750A4")
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false

    private var listening = false
    private var level = 0f

    fun setListening(value: Boolean) {
        if (listening == value) return
        listening = value
        invalidate()
    }

    fun setLevel(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (abs(clamped - level) < 0.02f) return
        level = clamped
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (ballRadius * 2 + dp(12f)).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        if (listening) {
            // 两层呼吸波纹，幅度跟着麦克风电平走
            val wave1 = ballRadius * (1.15f + 0.35f * level)
            val wave2 = ballRadius * (1.45f + 0.55f * level)
            wavePaint.alpha = 90
            canvas.drawCircle(cx, cy, wave2, wavePaint)
            wavePaint.alpha = 140
            canvas.drawCircle(cx, cy, wave1, wavePaint)
        }

        canvas.drawCircle(cx, cy, ballRadius, ballPaint)
        canvas.drawCircle(cx, cy, ballRadius, ringPaint)

        drawMicIcon(canvas, cx, cy)
    }

    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float) {
        // 话筒头：竖着的圆角矩形
        val headWidth = dp(7f)
        val headHeight = dp(15f)
        val headTop = cy - dp(11f)
        val headRect = RectF(
            cx - headWidth / 2,
            headTop,
            cx + headWidth / 2,
            headTop + headHeight
        )
        canvas.drawRoundRect(headRect, headWidth / 2, headWidth / 2, iconPaint)

        // 支架：下方半圆
        val cradle = RectF(
            cx - dp(9f),
            cy - dp(6f),
            cx + dp(9f),
            cy + dp(7f)
        )
        canvas.drawArc(cradle, 0f, 180f, false, strokePaint)

        // 底座竖线
        canvas.drawLine(cx, cy + dp(7f), cx, cy + dp(12f), strokePaint)

        if (listening) {
            // 录音时在右侧画三条跳动的声波线
            for (i in 0 until 3) {
                val offsetX = cx + dp(13f) + i * dp(4f)
                val half = dp(2f) + dp(5f) * level * (1f - i * 0.2f)
                canvas.drawLine(offsetX, cy - half, offsetX, cy + half, strokePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = downRawX
                lastRawY = downRawY
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val movedX = abs(event.rawX - downRawX)
                    val movedY = abs(event.rawY - downRawY)
                    if (movedX > touchSlop || movedY > touchSlop) dragging = true
                }
                if (dragging) {
                    onDrag?.invoke(event.rawX - lastRawX, event.rawY - lastRawY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) onTap?.invoke()
                dragging = false
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
