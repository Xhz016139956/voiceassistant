package com.example.voiceassistant.asr

/**
 * 云 ASR 占位实现。
 *
 * 何时需要它：系统 SpeechRecognizer 不可用（部分无 GMS 也无自家引擎的机型）、
 * 或需要长语音 / 更高准确率。
 *
 * 接入方式（TODO）：
 *  1. AudioRecord 采集 16k/16bit 单声道 PCM
 *  2. 按 40~100ms 分片推流给云厂商（讯飞 / 百度 / 阿里）
 *  3. 回调形状与 [SystemAsrEngine] 保持一致，直接替换即可
 *
 * 现在留空是为了保证「换引擎不改调用方」这个约束成立。
 */
class CloudAsrEngine(
    private val endpoint: String = "",
    private val apiKey: String = ""
) : AsrEngine {

    private var listener: AsrEngine.Listener? = null

    override fun setListener(listener: AsrEngine.Listener?) {
        this.listener = listener
    }

    override fun startListening() {
        listener?.onError("云识别未配置，请先填写 endpoint 与 apiKey")
    }

    override fun stopListening() = Unit

    override fun release() = Unit

    @Suppress("unused")
    fun isConfigured(): Boolean = endpoint.isNotBlank() && apiKey.isNotBlank()
}
