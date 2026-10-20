package com.example.voiceassistant.asr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「打开微信」被执行/播报两遍的那个 bug，回归测试。
 *
 * 真实日志（小米机）：
 *   final: 打开微信          00:44:28.437
 *   （TTS 播「已打开微信」）
 *   final: 打开微信          00:44:32.169  ← 回声，必须丢掉
 */
class EchoGuardTest {

    @Test
    fun `播报后的同义回声被丢弃`() {
        val guard = EchoGuard()
        guard.onSpoken("已打开微信", now = 1_000L)

        // 播报刚结束，识别器把「已打开微信」听成「打开微信」
        assertEquals(
            EchoGuard.Verdict.ECHO,
            guard.check("打开微信", now = 2_400L, speaking = false)
        )
    }

    @Test
    fun `播报仍在进行时的回声被丢弃`() {
        val guard = EchoGuard()
        guard.onSpoken("已打开微信", now = 1_000L)

        assertEquals(
            EchoGuard.Verdict.ECHO,
            guard.check("打开微信", now = 1_100L, speaking = true)
        )
    }

    @Test
    fun `远超回声窗口后同样的句子允许执行`() {
        val guard = EchoGuard()
        guard.onSpoken("已打开微信", now = 1_000L)

        assertEquals(
            EchoGuard.Verdict.ACCEPT,
            guard.check("打开微信", now = 9_000L, speaking = false)
        )
    }

    @Test
    fun `引擎重放的同一结果只认第一次`() {
        val guard = EchoGuard()
        assertEquals(EchoGuard.Verdict.ACCEPT, guard.check("现在几点", now = 1_000L, speaking = false))
        assertEquals(
            EchoGuard.Verdict.DUPLICATE,
            guard.check("现在几点", now = 1_600L, speaking = false)
        )
    }

    @Test
    fun `不同指令不受去重影响`() {
        val guard = EchoGuard()
        assertEquals(EchoGuard.Verdict.ACCEPT, guard.check("打开微信", now = 1_000L, speaking = false))
        assertEquals(EchoGuard.Verdict.ACCEPT, guard.check("打开支付宝", now = 1_300L, speaking = false))
    }
}
