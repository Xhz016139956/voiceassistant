package com.example.voiceassistant.executor

import com.example.voiceassistant.intent.Action

/**
 * 动作执行抽象。模块 5 实现无障碍版执行器。
 * 所有实现都必须：带超时、失败返回可播报的原因、不抛异常给上层。
 */
interface ActionExecutor {
    suspend fun execute(action: Action): ExecResult
}

data class ExecResult(val ok: Boolean, val message: String) {
    companion object {
        fun success(message: String) = ExecResult(true, message)
        fun failure(message: String) = ExecResult(false, message)
    }
}
