package com.example.voiceassistant.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 悬浮球、服务、UI 三方共享的运行时状态。
 *
 * 为什么不用 ViewModel：悬浮球活在 Service 里，没有 Activity 生命周期，
 * 用进程级 StateFlow 最省事，UI 侧 collectAsState 即可。
 */
data class AssistantUiState(
    val overlayVisible: Boolean = false,
    val recording: Boolean = false,
    val level: Float = 0f,
    val partialText: String = "",
    val lastText: String = "",
    val statusText: String = "待命中"
)

object AssistantStateBus {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    fun update(transform: (AssistantUiState) -> AssistantUiState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = AssistantUiState()
    }
}
