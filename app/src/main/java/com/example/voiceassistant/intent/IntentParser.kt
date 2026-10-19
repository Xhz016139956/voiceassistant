package com.example.voiceassistant.intent

/**
 * 意图解析抽象：规则优先、LLM 兜底。
 * 模块 4 会给出 RuleParser / LlmIntentParser / JsonValidator 的实现与组合入口。
 */
interface IntentParser {
    suspend fun parse(utterance: String): ParseResult
}

sealed class ParseResult {
    data class Success(val action: Action) : ParseResult()

    /** 解析不出白名单动作，或 JSON 非法 —— 一律拒绝执行，不做猜测 */
    data class Rejected(val reason: String) : ParseResult()
}
