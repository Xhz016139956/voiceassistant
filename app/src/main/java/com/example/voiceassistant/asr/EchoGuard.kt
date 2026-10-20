package com.example.voiceassistant.asr

/**
 * 判断一条识别结果该不该执行。
 *
 * 现实里踩到的两个坑（都是"同一句指令执行两遍"）：
 *  1. 回声：TTS 播完「已打开微信」，识别器重开麦后把它听成「打开微信」；
 *  2. 重放：识别引擎重启时会把上一句 final 再吐一次。
 *
 * 这里只做纯字符串/时间判断，不碰 Android API，方便单测。
 */
class EchoGuard(
    private val echoWindowMs: Long = 3_000L,
    private val duplicateWindowMs: Long = 2_000L
) {

    enum class Verdict {
        ACCEPT,
        /** 和自己刚说的话重合，是回声 */
        ECHO,
        /** 和上一条结果完全相同且间隔太短 */
        DUPLICATE
    }

    private var spokenText: String = ""
    private var spokenAt: Long = Long.MIN_VALUE / 2
    private var lastFinalText: String = ""
    private var lastFinalAt: Long = Long.MIN_VALUE / 2

    /** 每次开口播报前调用 */
    fun onSpoken(text: String, now: Long) {
        spokenText = normalize(text)
        spokenAt = now
    }

    /** 一句话被识别出来时调用；ACCEPT 之外都应丢弃 */
    fun check(text: String, now: Long, speaking: Boolean): Verdict {
        val heard = normalize(text)

        if (heard.isNotEmpty() && spokenText.isNotEmpty()) {
            val inWindow = speaking || now - spokenAt <= echoWindowMs
            if (inWindow && (spokenText.contains(heard) || heard.contains(spokenText))) {
                return Verdict.ECHO
            }
        }

        if (heard.isNotEmpty() && heard == lastFinalText && now - lastFinalAt <= duplicateWindowMs) {
            return Verdict.DUPLICATE
        }

        lastFinalText = heard
        lastFinalAt = now
        return Verdict.ACCEPT
    }

    private fun normalize(text: String): String = text.replace(NOISE, "")

    private companion object {
        val NOISE = Regex("[\\s，。！？、,.!?\"'「」：:；;]")
    }
}
