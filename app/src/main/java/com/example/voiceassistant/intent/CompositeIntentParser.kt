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

        // 发消息一律二次确认：发出去就撤不回，而且这是最容易因为听错人/听错字翻车的动作
        val needsConfirm = result is ParseResult.Success &&
            result.action !is Action.Confirm &&
            (DangerRules.isDangerous(trimmed) || result.action is Action.SendMessage)

        if (needsConfirm) {
            val action = (result as ParseResult.Success).action
            val question = if (action is Action.SendMessage) {
                "要发给「${action.contact}」：${action.content}。确定发送吗？"
            } else {
                "「$trimmed」涉及敏感操作，确定执行吗？"
            }
            return ParseResult.Success(Action.Confirm(question, action, trimmed))
        }

        return result
    }
}
