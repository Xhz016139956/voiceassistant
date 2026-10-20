package com.example.voiceassistant.assistant

/**
 * 唤醒词识别。
 *
 * 系统识别引擎对中文专有名词的转写不稳定，「贾维斯」实测可能变成
 * 「贾维丝 / 加维斯 / 贾伟斯 / 假维斯」，所以这里做同音容错，
 * 而不是死磕两个字。
 */
object WakeWord {

    const val NAME = "贾维斯"

    private val ALIASES = listOf(
        "贾维斯", "贾维丝", "贾伟斯", "加维斯", "假维斯", "佳维斯",
        "jarvis", "javis", "贾维思", "贾维系"
    )

    private val LEADING_NOISE = listOf("喂", "嗨", "嘿", "你好", "嗯", "那个", "请")

    fun matches(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        return ALIASES.any { normalized.contains(it) }
    }

    /**
     * 去掉唤醒词，取出同一句里跟的指令：
     * 「贾维斯，打开微信」→「打开微信」；只说「贾维斯」→ 空串。
     */
    fun strip(text: String): String {
        var result = normalize(text)
        for (alias in ALIASES) {
            val index = result.indexOf(alias)
            if (index >= 0) {
                result = result.removeRange(index, index + alias.length)
                break
            }
        }
        result = result.trim().trimStart('，', ',', '。', '、', '！', '!', '：', ':')
        for (noise in LEADING_NOISE) {
            if (result.startsWith(noise)) {
                result = result.removePrefix(noise).trim()
                break
            }
        }
        return result.trim()
    }

    private fun normalize(raw: String): String = raw
        .lowercase()
        .replace(" ", "")
        .replace("\u3000", "")
        .trim()
}
