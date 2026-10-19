package com.example.voiceassistant.intent

/**
 * 组合解析：规则优先，LLM 兜底，最后统一过危险动作闸门。
 *
 * 危险判定放在最后而不是各解析器内部：规则和 LLM 谁产出都一样，
 * 只要原句命中危险关键词，就必须包一层 Confirm，任何路径都绕不过去。
 */
class CompositeIntentParser(
    private val ruleParser: RuleParser = RuleParser(),
    private val llmParser: LlmIntentParser = LlmIntentParser(UnavailableLlmGateway)
) : IntentParser {

    override suspend fun parse(utterance: String): ParseResult {
        val trimmed = utterance.trim()
        if (trimmed.isEmpty()) return ParseResult.Rejected("指令为空")

        val ruleAction = ruleParser.parse(trimmed)
        val result = if (ruleAction != null) {
            ParseResult.Success(ruleAction)
        } else {
            llmParser.parse(trimmed)
        }

        if (result is ParseResult.Success &&
            result.action !is Action.Confirm &&
            DangerRules.isDangerous(trimmed)
        ) {
            return ParseResult.Success(
                Action.Confirm(
                    question = "「$trimmed」涉及敏感操作，确定执行吗？",
                    pending = result.action,
                    raw = trimmed
                )
            )
        }

        return result
    }
}
