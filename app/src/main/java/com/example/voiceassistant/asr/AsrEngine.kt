package com.example.voiceassistant.asr

/**
 * 语音识别抽象。两个实现：
 *  - [SystemAsrEngine]：Android SpeechRecognizer（本机走小米 AsrService）
 *  - [CloudAsrEngine]：云 ASR 预留空实现
 *
 * 回调线程约定：实现方保证所有回调都在主线程，调用方可直接更新 UI。
 */
interface AsrEngine {

    interface Listener {
        /** 中间结果，用于实时上屏 */
        fun onPartial(text: String)

        /** 最终结果，一条完整命令 */
        fun onFinal(text: String)

        /** 识别失败，message 要能直接播报给用户 */
        fun onError(message: String)

        /** 归一化音量 0f..1f，驱动悬浮球波形 */
        fun onRmsChanged(level: Float) {}

        /** 麦克风已就绪，可以提示用户说话 */
        fun onReady() {}
    }

    fun setListener(listener: Listener?)

    fun startListening()

    fun stopListening()

    fun release()
}
