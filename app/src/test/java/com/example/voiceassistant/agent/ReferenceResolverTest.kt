package com.example.voiceassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceResolverTest {

    @Test
    fun `打开它还原成上次提到的应用`() {
        assertEquals("打开微信", ReferenceResolver.resolve("打开它", "微信", null))
        assertEquals("进入DeepSeek", ReferenceResolver.resolve("进入那个", "DeepSeek", null))
    }

    @Test
    fun `发给那个还原成上次的联系人`() {
        assertEquals("发给老王", ReferenceResolver.resolve("发给那个", null, "老王"))
    }

    @Test
    fun `没提过就原样返回`() {
        assertEquals("打开它", ReferenceResolver.resolve("打开它", null, null))
    }

    @Test
    fun `不相干的句子不乱替换`() {
        assertEquals("今天天气怎么样", ReferenceResolver.resolve("今天天气怎么样", "微信", "老王"))
        // 没有动词时不做替换，避免把"这个多少钱"改成"微信多少钱"
        assertEquals("这个怎么用", ReferenceResolver.resolve("这个怎么用", "微信", null))
    }
}
