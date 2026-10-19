package com.example.voiceassistant.intent

/**
 * LLM 调用的最小抽象。模块 9 会用 OkHttp + OpenAI 兼容接口实现它。
 * 现在给出「不可用」实现，保证模块 4 能独立编译、独立测试。
 */
interface LlmGateway {
    /** 返回模型原始文本；不可用或失败时返回 null */
    suspend fun complete(systemPrompt: String, userPrompt: String): String?
}

object UnavailableLlmGateway : LlmGateway {
    override suspend fun complete(systemPrompt: String, userPrompt: String): String? = null
}
