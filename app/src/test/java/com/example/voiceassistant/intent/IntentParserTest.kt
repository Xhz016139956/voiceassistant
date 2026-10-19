package com.example.voiceassistant.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单元测试，不依赖 Android 环境：
 *   ./gradlew testDebugUnitTest
 */
class RuleParserTest {

    private val parser = RuleParser()

    @Test
    fun openApp() {
        val action = parser.parse("打开微信")
        assertTrue(action is Action.OpenApp)
        assertEquals("微信", (action as Action.OpenApp).appName)
    }

    @Test
    fun openAppStripsSuffix() {
        val action = parser.parse("帮我打开支付宝应用")
        assertTrue(action is Action.OpenApp)
        assertEquals("支付宝", (action as Action.OpenApp).appName)
    }

    @Test
    fun back() {
        assertTrue(parser.parse("返回") is Action.Back)
    }

    @Test
    fun home() {
        assertTrue(parser.parse("回到桌面") is Action.Home)
    }

    @Test
    fun swipeDown() {
        val action = parser.parse("往下滑") as Action.Swipe
        assertEquals(SwipeDirection.DOWN, action.direction)
    }

    @Test
    fun volumeUp() {
        val action = parser.parse("音量加大一点") as Action.Volume
        assertEquals(VolumeCommand.UP, action.command)
    }

    @Test
    fun mute() {
        val action = parser.parse("静音") as Action.Volume
        assertEquals(VolumeCommand.MUTE, action.command)
    }

    @Test
    fun inputText() {
        val action = parser.parse("输入你好") as Action.InputText
        assertEquals("你好", action.text)
    }

    @Test
    fun clickIndexChinese() {
        val action = parser.parse("点第三个") as Action.ClickIndex
        assertEquals(3, action.index)
    }

    @Test
    fun clickIndexArabic() {
        val action = parser.parse("点击第 12 个") as Action.ClickIndex
        assertEquals(12, action.index)
    }

    @Test
    fun clickText() {
        val action = parser.parse("点一下确定") as Action.ClickText
        assertEquals("确定", action.text)
    }

    @Test
    fun speakScreen() {
        assertTrue(parser.parse("读一下屏幕") is Action.SpeakScreen)
    }

    @Test
    fun remind() {
        val action = parser.parse("提醒我八点开会") as Action.Notify
        assertEquals("八点开会", action.text)
    }

    @Test
    fun unknownSentenceReturnsNull() {
        assertNull(parser.parse("今天天气怎么样"))
    }
}

class IntentJsonValidatorTest {

    @Test
    fun validOpenApp() {
        val result = IntentJsonValidator.parse("""{"action":"open_app","app_name":"微信"}""")
        assertTrue(result is ParseResult.Success)
    }

    @Test
    fun rejectsUnknownAction() {
        val result = IntentJsonValidator.parse("""{"action":"format_disk"}""")
        assertTrue(result is ParseResult.Rejected)
    }

    @Test
    fun rejectsIndexOutOfRange() {
        assertTrue(IntentJsonValidator.parse("""{"action":"click_index","index":999}""") is ParseResult.Rejected)
        assertTrue(IntentJsonValidator.parse("""{"action":"click_index","index":0}""") is ParseResult.Rejected)
    }

    @Test
    fun toleratesMarkdownFence() {
        val raw = "```json\n{\"action\":\"back\"}\n```"
        assertTrue(IntentJsonValidator.parse(raw) is ParseResult.Success)
    }

    @Test
    fun rejectsEmptyObject() {
        assertTrue(IntentJsonValidator.parse("{}") is ParseResult.Rejected)
    }

    @Test
    fun rejectsBadSwipeDirection() {
        assertTrue(
            IntentJsonValidator.parse("""{"action":"swipe","direction":"diagonal"}""") is ParseResult.Rejected
        )
    }

    @Test
    fun nestedConfirm() {
        val raw = """{"action":"confirm","question":"确定删除？","pending":{"action":"back"}}"""
        val result = IntentJsonValidator.parse(raw)
        assertTrue(result is ParseResult.Success)
        assertTrue((result as ParseResult.Success).action is Action.Confirm)
    }
}
