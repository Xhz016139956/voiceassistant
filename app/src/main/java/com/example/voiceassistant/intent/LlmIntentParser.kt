package com.example.voiceassistant.intent

/**
 * LLM 兜底解析。
 *
 * 产出必须过 [IntentJsonValidator]：动作白名单、字段类型、取值范围全部校验，
 * 任何一项不合法就拒绝执行。LLM 在这里只负责"翻译成人话对应的 JSON"，
 * 不负责决定要不要执行。
 */
class LlmIntentParser(private val gateway: LlmGateway) {

    suspend fun parse(utterance: String, screenSummary: String = ""): ParseResult {
        val userPrompt = buildString {
            append("用户指令：").append(utterance)
            if (screenSummary.isNotBlank()) {
                append("\n\n当前屏幕可点击元素（只能引用这里出现的，不能编造）：\n")
                append(screenSummary)
            }
        }

        val raw = gateway.complete(SYSTEM_PROMPT, userPrompt)
            ?: return ParseResult.Rejected("LLM 未配置或调用失败")

        return IntentJsonValidator.parse(raw)
    }

    companion object {
        val SYSTEM_PROMPT: String = """
            你是手机语音指令解析器。只输出一个 JSON 对象，禁止任何解释文字、禁止 markdown 代码块。

            可用动作与字段：
            - {"action":"open_app","app_name":"微信"}
            - {"action":"back"}
            - {"action":"home"}
            - {"action":"click_text","text":"确定"}
            - {"action":"click_index","index":3}
            - {"action":"swipe","direction":"up|down|left|right","distance":0.6}
            - {"action":"input_text","text":"你好"}
            - {"action":"volume","command":"up|down|mute|unmute"}
            - {"action":"speak_screen"}
            - {"action":"notify","text":"晚上八点开会"}
            - {"action":"send_message","app_name":"微信","contact":"老王","content":"晚上一起吃饭"}

            规则：
            1. 只能输出上面列出的动作，禁止自创动作名。
            2. index 从 1 开始，范围 1-50。
            3. distance 范围 0.1-1.0，不确定就给 0.6。
            4. 涉及支付、转账、删除、发送、卸载、修改密码等敏感操作时，仍然照常输出对应动作，
               客户端会强制走二次确认，你不要自己做判断。
            5. 一句话里同时包含"打开某应用 + 给某人发消息/文字 + 内容"时，用 send_message，
               不要拆成多个动作，也不要把内容写成 app 名。
               例：说"给老王发微信说明天见" → {"action":"send_message","app_name":"微信","contact":"老王","content":"明天见"}
               例：说"用QQ告诉张三我在路上" → {"action":"send_message","app_name":"QQ","contact":"张三","content":"我在路上"}
            6. contact 只填人名/群名本身，不要带"给""发""说"等字。
            7. 完全无法判断时输出 {}。
        """.trimIndent()
    }
}
