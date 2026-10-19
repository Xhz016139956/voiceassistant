package com.example.voiceassistant.tts

/**
 * 播报抽象。模块 6 实现：
 *  - 中文优先、队列 + 打断
 *  - 引擎缺失 / 初始化失败降级 Toast
 *  - 静音开关
 */
interface TtsManager {

    fun speak(text: String, interrupt: Boolean = true)

    fun stop()

    fun setMuted(muted: Boolean)

    fun isReady(): Boolean

    fun release()
}
