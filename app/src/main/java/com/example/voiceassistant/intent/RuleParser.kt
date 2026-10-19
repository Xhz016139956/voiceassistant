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

        // 8. 打开应用
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

    private fun normalize(raw: String): String {
        return raw.trim().trimEnd('。', '！', '？', '.', '!', '?', '，', ',', '、')
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
