package com.example.voiceassistant.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 最近一次连通性探测的结果（null = 还没测过） */
data class LlmStatus(val ok: Boolean?, val text: String)

/**
 * 探测结果的进程级广播。
 *
 * 打开首页点「保存并测试」、或者用 adb 启动参数写入配置，都会走到这里，
 * 保证界面上显示的始终是最近一次真实探测，而不是"填了就算接上"。
 */
object LlmStatusBus {

    private val _state = MutableStateFlow<LlmStatus?>(null)
    val state: StateFlow<LlmStatus?> = _state.asStateFlow()

    fun testing() {
        _state.value = LlmStatus(null, "正在测试连接…")
    }

    fun publish(ok: Boolean, text: String) {
        _state.value = LlmStatus(ok, text)
    }
}
