package com.example.voiceassistant.asr

/**
 * 识别结果去重。
 *
 * 实测：小米 AsrService 在没听到人声时会反复吐「小爱同学」这类兜底文本，
 * 连续模式下一分钟能刷几十条日志。同一句话在 [duplicateWindowMs] 内重复出现，
 * 判定为引擎兜底，直接丢弃。
 *
 * 空转保护不在这里做 —— 那个属于 [SystemAsrEngine] 的重启策略。
 */
class AsrResultFilter(private val duplicateWindowMs: Long = 3_000L) {

    private var lastText: String = ""
    private var lastAt: Long = 0L

    /** 返回 true 表示这条结果值得处理 */
    fun accept(text: String, now: Long = System.currentTimeMillis()): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false

        val isDuplicate = normalized == lastText && now - lastAt < duplicateWindowMs
        if (isDuplicate) return false

        lastText = normalized
        lastAt = now
        return true
    }

    fun reset() {
        lastText = ""
        lastAt = 0L
    }
}
