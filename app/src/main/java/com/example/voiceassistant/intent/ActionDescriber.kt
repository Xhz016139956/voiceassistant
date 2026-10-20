package com.example.voiceassistant.intent

/**
 * 把动作转成一句能直接播报 / 写日志的中文。
 * 日志页、TTS、确认弹窗共用同一份描述，避免三处措辞不一致。
 */
fun Action.describe(): String = when (this) {
    is Action.OpenApp -> "打开应用「$appName」"
    is Action.Back -> "返回上一页"
    is Action.Home -> "回到桌面"
    is Action.ClickText -> "点击文字「$text」"
    is Action.ClickIndex -> "点击第 $index 个可点击元素"
    is Action.Swipe -> "向${direction.label}滑动 ${(distanceRatio * 100).toInt()}%"
    is Action.InputText -> "输入「$text」"
    is Action.Volume -> "音量${command.label}"
    is Action.SpeakScreen -> "播报当前屏幕文字"
    is Action.Notify -> "提醒：$text"
    is Action.SendMessage -> "在「$appName」里发给「$contact」：$content"
    is Action.Confirm -> "等待确认：$question"
}
