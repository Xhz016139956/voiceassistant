package com.example.voiceassistant.intent

/**
 * 动作白名单（与总纲一一对应）。
 *
 * 执行器只认这个 sealed class 的分支；LLM 返回的任何其他动作名都会被 JsonValidator 拒绝。
 * 新增动作必须同时改三处：这里、白名单常量、执行器 when 分支。
 */
sealed class Action {

    /** 原始语句，用于日志回溯 */
    open val raw: String? = null

    /** open_app */
    data class OpenApp(val appName: String, override val raw: String? = null) : Action()

    /** back */
    data class Back(override val raw: String? = null) : Action()

    /** home */
    data class Home(override val raw: String? = null) : Action()

    /** click_text */
    data class ClickText(val text: String, override val raw: String? = null) : Action()

    /** click_index，序号从 1 开始（对用户更自然） */
    data class ClickIndex(val index: Int, override val raw: String? = null) : Action()

    /** swipe */
    data class Swipe(
        val direction: SwipeDirection,
        val distanceRatio: Float = 0.6f,
        override val raw: String? = null
    ) : Action()

    /** input_text */
    data class InputText(val text: String, override val raw: String? = null) : Action()

    /** volume */
    data class Volume(val command: VolumeCommand, override val raw: String? = null) : Action()

    /** speak_screen */
    data class SpeakScreen(override val raw: String? = null) : Action()

    /** notify */
    data class Notify(val text: String, override val raw: String? = null) : Action()

    /**
     * send_message：一句话完成"打开 App → 搜联系人 → 输入 → 发送"。
     *
     * 为什么做成一个复合动作而不是让上层串五个动作：中间的节点名称、等待时长、
     * 失败回滚都强依赖具体 App，放在执行器里才能统一处理；上层只管"发给谁、说什么"。
     */
    data class SendMessage(
        val appName: String,
        val contact: String,
        val content: String,
        override val raw: String? = null
    ) : Action()

    /** confirm：危险动作必须由 UI 层二次确认后才转发 pending */
    data class Confirm(
        val question: String,
        val pending: Action,
        override val raw: String? = null
    ) : Action()

    companion object {
        /** 白名单动作名，JsonValidator 与系统提示词共用同一份 */
        val WHITELIST: Set<String> = setOf(
            "open_app", "back", "home", "click_text", "click_index",
            "swipe", "input_text", "volume", "speak_screen", "notify",
            "send_message", "confirm"
        )
    }
}

enum class SwipeDirection(val label: String) {
    UP("上"), DOWN("下"), LEFT("左"), RIGHT("右");

    companion object {
        fun from(raw: String?): SwipeDirection? = entries.firstOrNull {
            it.name.equals(raw, ignoreCase = true) || it.label == raw
        }
    }
}

enum class VolumeCommand(val label: String) {
    UP("增大"), DOWN("减小"), MUTE("静音"), UNMUTE("取消静音");

    companion object {
        fun from(raw: String?): VolumeCommand? = entries.firstOrNull {
            it.name.equals(raw, ignoreCase = true) || it.label == raw
        }
    }
}

/** 命中即视为危险动作，必须走 Confirm */
object DangerRules {
    private val KEYWORDS = listOf(
        "支付", "付款", "转账", "汇款", "红包",
        "删除", "卸载", "清空", "格式化", "恢复出厂",
        "发送", "群发", "提交订单", "购买", "下单",
        "授权", "登录", "退出登录", "修改密码"
    )

    fun isDangerous(utterance: String): Boolean = KEYWORDS.any { utterance.contains(it) }
}
