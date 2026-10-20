package com.example.voiceassistant.agent

import com.example.voiceassistant.intent.Action
import com.example.voiceassistant.intent.RuleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlannerTest {

    @Test
    fun `逗号也是分隔符`() {
        assertEquals(
            listOf("打开deepseek", "输入怎么使用AI", "发送"),
            TaskPlanner.split("打开deepseek，输入怎么使用AI，然后发送")
        )
    }

    @Test
    fun `应用名不吃掉逗号后面的内容`() {
        val action = RuleParser().parse("打开deepseek，输入怎么使用AI")
        assertTrue("应为打开应用，实际 $action", action is Action.OpenApp)
        assertEquals("deepseek", (action as Action.OpenApp).appName)
    }

    @Test
    fun `单独说发送就是点发送按钮`() {
        val action = RuleParser().parse("发送")
        assertTrue(action is Action.ClickText)
        assertEquals("发送", (action as Action.ClickText).text)
    }

    @Test
    fun `顺序词切成两个子目标`() {
        assertEquals(
            listOf("打开微信", "给老王发消息说晚上吃饭"),
            TaskPlanner.split("打开微信，然后给老王发消息说晚上吃饭")
        )
    }

    @Test
    fun `去掉句首客套话`() {
        assertEquals(
            listOf("把音量调小"),
            TaskPlanner.split("帮我 把音量调小")
        )
    }

    @Test
    fun `引号里的连接词不切`() {
        assertEquals(
            listOf("给老王发消息说「好的，然后呢」"),
            TaskPlanner.split("给老王发消息说「好的，然后呢」")
        )
    }

    @Test
    fun `单句原样返回`() {
        assertEquals(listOf("打开微信"), TaskPlanner.split("打开微信"))
    }

    @Test
    fun `子任务数量有上限`() {
        val text = (1..9).joinToString("，然后") { "打开应用$it" }
        assertEquals(TaskPlanner.MAX_SUB_TASKS, TaskPlanner.split(text).size)
    }
}
