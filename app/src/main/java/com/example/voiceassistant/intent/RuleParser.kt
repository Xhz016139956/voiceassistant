package com.example.voiceassistant.intent

/**
 * 规则解析器：不联网、不花钱、毫秒级。
 *
 * 覆盖高频短指令（打开/返回/滑动/音量/输入/点击/读屏/提醒）。
 * 匹配不到时返回 null，交给 LLM 兜底 —— 规则永远不猜。
 *
 * 顺序很关键：先匹配带强特征的（第 N 个、音量、输入），再匹配泛化的（打开 X、点 X）。
 */
class RuleParser {

    fun parse(utterance: String): Action? {
        val text = normalize(utterance)
        if (text.isEmpty()) return null

        // 1. 序号点击：「点第三个」「点击第 3 个」
        INDEX_REGEX.find(text)?.let { match ->
            val number = parseNumber(match.groupValues[1])
            if (number != null) return Action.ClickIndex(number, text)
        }

        // 2. 音量
        if (VOLUME_HINT.any { text.contains(it) }) {
            when {
                text.contains("取消静音") || text.contains("打开声音") ->
                    return Action.Volume(VolumeCommand.UNMUTE, text)

                text.contains("静音") -> return Action.Volume(VolumeCommand.MUTE, text)
                text.contains("大") || text.contains("加") || text.contains("高") ->
                    return Action.Volume(VolumeCommand.UP, text)

                text.contains("小") || text.contains("减") || text.contains("低") ->
                    return Action.Volume(VolumeCommand.DOWN, text)
            }
        }

        // 3. 输入文字
        INPUT_REGEX.find(text)?.let { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotEmpty()) return Action.InputText(content, text)
        }

        // 4. 滑动
        SWIPE_RULES.forEach { (keywords, direction) ->
            if (keywords.any { text.contains(it) }) {
                return Action.Swipe(direction, 0.6f, text)
            }
        }

        // 5. 播报屏幕
        if (SPEAK_KEYWORDS.any { text.contains(it) }) return Action.SpeakScreen(text)

        // 6. 返回 / 回桌面
        if (text == "返回" || text.contains("返回") || text.contains("后退") || text.contains("退回去")) {
            return Action.Back(text)
        }
        if (HOME_KEYWORDS.any { text.contains(it) }) return Action.Home(text)

        // 7. 提醒
        REMIND_REGEX.find(text)?.let { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotEmpty()) return Action.Notify(content, text)
        }

        // 8. 发消息（必须排在"打开应用"和"点击"前面，
        //    否则「打开微信给老王发消息说晚上吃饭」会被当成打开一个叫「微信给老王…」的应用）
        parseSendMessage(text)?.let { return it }

        // 9. 打开应用
        OPEN_REGEX.find(text)?.let { match ->
            val name = cleanTarget(match.groupValues[1])
            if (name.isNotEmpty()) return Action.OpenApp(name, text)
        }

        // 9. 点击屏幕文字
        CLICK_REGEX.find(text)?.let { match ->
            val target = cleanTarget(match.groupValues[1])
            if (target.isNotEmpty()) return Action.ClickText(target, text)
        }

        return null
    }

    /**
     * "发消息"类复合指令。
     *
     * 覆盖四种常见说法（顺序不能乱，先匹配信息最全的）：
     *   1. 微信给老王发消息说晚上吃饭 / 打开微信，给老王发个消息说明天见
     *   2. 发条消息给老王说晚上吃饭
     *   3. 给老王发微信说明天见
     *   4. 跟老王说明天见
     */
    private fun parseSendMessage(text: String): Action? {
        MSG_APP_FIRST.find(text)?.let { m ->
            buildSendMessage(m.groupValues[1], m.groupValues[2], m.groupValues[3], text)?.let { return it }
        }
        MSG_VERB_FIRST.find(text)?.let { m ->
            buildSendMessage(m.groupValues[1], m.groupValues[2], m.groupValues[3], text)?.let { return it }
        }
        MSG_CONTACT_FIRST.find(text)?.let { m ->
            buildSendMessage(m.groupValues[2], m.groupValues[1], m.groupValues[3], text)?.let { return it }
        }
        // 5. 用QQ告诉李四，我在路上（有标点时人名和内容才分得开，没标点交给 LLM）
        MSG_TELL_APP.find(text)?.let { m ->
            buildSendMessage(m.groupValues[1], m.groupValues[2], m.groupValues[3], text)?.let { return it }
        }
        MSG_TELL.find(text)?.let { m ->
            val contact = m.groupValues[1].trim()
            if (contact.isNotEmpty() && contact != "我" && contact != "自己") {
                buildSendMessage(null, contact, m.groupValues[2], text)?.let { return it }
            }
        }
        return null
    }

    private fun buildSendMessage(
        appRaw: String?,
        contactRaw: String,
        contentRaw: String,
        raw: String
    ): Action? {
        val contact = cleanTarget(contactRaw).trim().trim('，', ',', '：', ':')
        val content = contentRaw.trim()
            .trimStart('，', ',', '：', ':', '。', ' ')
            .removePrefix("说")
            .removePrefix("告诉")
            .trim()
        if (contact.isEmpty() || content.isEmpty()) return null
        if (contact.length > 20 || content.length > 200) return null
        return Action.SendMessage(normalizeApp(appRaw), contact, content, raw)
    }

    /** 语音识别经常把"微信"听成"威信/薇信"，这里统一纠正 */
    private fun normalizeApp(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return when {
            value.isEmpty() -> DEFAULT_MESSAGE_APP
            value.contains("微信") || value.contains("威信") || value.contains("薇信") ||
                value.contains("V信") || value.contains("v信") || value.contains("维信") -> "微信"

            value.contains("QQ", true) || value.contains("扣扣") || value.contains("球球") -> "QQ"
            value.contains("短信") -> "短信"
            value.contains("钉钉") -> "钉钉"
            value.contains("消息") || value.contains("信息") -> DEFAULT_MESSAGE_APP
            else -> value
        }
    }

    private fun normalize(raw: String): String {
        // 语音识别的高频误听：先把同音字纠正，后面所有规则都受益
        return raw.trim()
            .replace("威信", "微信")
            .replace("薇信", "微信")
            .replace("维信", "微信")
            .replace("V信", "微信")
            .replace("v信", "微信")
            .replace("扣扣", "QQ")
            .trimEnd('。', '！', '？', '.', '!', '?', '，', ',', '、')
    }

    private fun cleanTarget(raw: String): String {
        var value = raw.trim().trimEnd('。', '！', '？', '.', '!', '?', '，', ',', '吧', '呗', '呀', '啊', '哦')
        for (suffix in TARGET_SUFFIXES) {
            if (value.endsWith(suffix) && value.length > suffix.length) {
                value = value.removeSuffix(suffix)
            }
        }
        return value.trim()
    }

    private fun parseNumber(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }

        val digits = mapOf(
            '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        return when {
            raw == "十" -> 10
            raw.length == 1 -> digits[raw[0]]
            raw.length == 2 && raw[0] == '十' -> digits[raw[1]]?.plus(10)
            raw.length == 2 && raw[1] == '十' -> digits[raw[0]]?.times(10)
            else -> null
        }
    }

    companion object {
        private val INDEX_REGEX = Regex("""第\s*([0-9一二两三四五六七八九十]+)\s*个""")
        private val INPUT_REGEX = Regex("""^(?:帮我|请|麻烦)?\s*(?:输入|打字)\s*(.+)$""")
        private val OPEN_REGEX = Regex("""^(?:帮我|请|麻烦)?\s*(?:打开|启动|运行|进入)\s*(?:一下)?\s*(.+)$""")
        private val CLICK_REGEX = Regex("""^(?:点击|点一下|点|按一下|按|选择)\s*(.+)$""")
        private val REMIND_REGEX = Regex("""^(?:提醒我|设个提醒|提醒|记一下)\s*(.+)$""")

        private const val DEFAULT_MESSAGE_APP = "微信"

        // 1. 微信给老王发消息说晚上吃饭 / 打开微信，给老王发个消息说明天见 / 用QQ告诉李四我在路上
        private val MSG_APP_FIRST = Regex(
            """^(?:帮我|请|麻烦)?\s*(?:打开|用|在)?\s*(微信|威信|薇信|v信|V信|qq|QQ|扣扣|短信|钉钉)\s*[，,]?\s*(?:给|跟|和)?\s*([^，,]{1,20}?)\s*(?:发|说|告诉)\s*(?:一条|个|条)?\s*(?:消息|信息)?\s*[，,]?\s*(?:说|告诉)?\s*(.+)$"""
        )

        // 2. 发条消息给老王说晚上吃饭（"说/告诉/："必须有，否则分不清人名和内容）
        private val MSG_VERB_FIRST = Regex(
            """^(?:帮我|请|麻烦)?\s*发\s*(?:一条|个|条)?\s*(微信|威信|qq|QQ|短信|消息|信息)?\s*给\s*([^，,]{1,20}?)\s*(?:说|告诉|，|,|：|:)\s*(.+)$"""
        )

        // 3. 给老王发微信说明天见（没有"发什么"就不算消息指令，交给 LLM 追问）
        private val MSG_CONTACT_FIRST = Regex(
            """^(?:帮我|请|麻烦)?\s*给\s*([^，,]{1,20}?)\s*(?:发|说)\s*(?:一条|个|条)?\s*(微信|威信|qq|QQ|短信|消息|信息|钉钉)\s*[，,]?\s*(?:说|告诉)?\s*(.+)$"""
        )

        // 4. 用QQ告诉李四，我在路上（必须带标点，否则人名和内容分不开）
        private val MSG_TELL_APP = Regex(
            """^(?:帮我|请|麻烦)?\s*(?:打开|用)?\s*(微信|威信|qq|QQ|短信|钉钉)\s*(?:告诉|跟|和|对)\s*([^，,]{1,20})\s*[，,：:]\s*(.+)$"""
        )

        // 5. 跟老王说明天见
        private val MSG_TELL = Regex(
            """^(?:帮我|请|麻烦)?\s*(?:跟|和|给)\s*([^，,]{1,20}?)\s*(?:说|讲)\s*[，,:：]?\s*(.+)$"""
        )

        // 「静音」不含「音量/声音」，必须单独列进来
        private val VOLUME_HINT = listOf("音量", "声音", "静音")
        private val SPEAK_KEYWORDS = listOf("读屏", "念一下", "读一下", "屏幕内容", "念屏幕", "朗读屏幕", "念给我听")
        private val HOME_KEYWORDS = listOf("回到桌面", "回桌面", "回主屏", "主屏幕", "回到主页", "回主页")

        private val SWIPE_RULES = listOf(
            listOf("往下滑", "向下滑", "下滑", "往下翻", "向下翻", "下一页", "往下滚") to SwipeDirection.DOWN,
            listOf("往上滑", "向上滑", "上滑", "往上翻", "上一页", "往上滚") to SwipeDirection.UP,
            listOf("左滑", "向左滑") to SwipeDirection.LEFT,
            listOf("右滑", "向右滑") to SwipeDirection.RIGHT
        )

        private val TARGET_SUFFIXES = listOf("这个应用", "这个软件", "应用", "软件", "app", "App", "APP")
    }
}
