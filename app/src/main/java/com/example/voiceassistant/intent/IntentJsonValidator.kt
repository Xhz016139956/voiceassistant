package com.example.voiceassistant.intent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * LLM 输出的唯一入口校验器。
 *
 * 原则：宁可拒绝，也不猜。任何字段缺失、类型不符、取值范围越界、动作不在白名单，
 * 一律返回 Rejected，绝不做"大概是想打开微信吧"这种补全。
 */
object IntentJsonValidator {

    private const val MAX_TEXT_LENGTH = 500
    private const val MAX_INDEX = 50
    private const val MAX_DEPTH = 2

    fun parse(raw: String): ParseResult {
        val jsonText = extractJsonObject(raw)
            ?: return ParseResult.Rejected("没有找到 JSON 对象")

        val element = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()
            ?: return ParseResult.Rejected("不是合法 JSON")

        return parseAction(element, depth = 0)
    }

    private fun parseAction(element: JsonElement, depth: Int): ParseResult {
        val obj = element as? JsonObject
            ?: return ParseResult.Rejected("动作必须是 JSON 对象")

        val actionName = obj["action"].asString()
            ?: return ParseResult.Rejected("缺少 action 字段")

        if (actionName !in Action.WHITELIST) {
            return ParseResult.Rejected("动作「$actionName」不在白名单内")
        }

        return when (actionName) {
            "open_app" -> {
                val appName = obj["app_name"].asString()
                if (appName.isNullOrBlank()) {
                    ParseResult.Rejected("open_app 缺少 app_name")
                } else {
                    ParseResult.Success(Action.OpenApp(appName.trim()))
                }
            }

            "back" -> ParseResult.Success(Action.Back())

            "home" -> ParseResult.Success(Action.Home())

            "click_text" -> {
                val text = obj["text"].asString()
                when {
                    text.isNullOrBlank() -> ParseResult.Rejected("click_text 缺少 text")
                    text.length > MAX_TEXT_LENGTH -> ParseResult.Rejected("click_text 文本过长")
                    else -> ParseResult.Success(Action.ClickText(text.trim()))
                }
            }

            "click_index" -> {
                val index = obj["index"].asInt()
                when {
                    index == null -> ParseResult.Rejected("click_index 缺少 index")
                    index < 1 || index > MAX_INDEX ->
                        ParseResult.Rejected("click_index 超出范围 1..$MAX_INDEX")

                    else -> ParseResult.Success(Action.ClickIndex(index))
                }
            }

            "swipe" -> {
                val direction = SwipeDirection.from(obj["direction"].asString())
                    ?: return ParseResult.Rejected("swipe 的 direction 非法")
                val ratio = obj["distance"].asFloat() ?: 0.6f
                if (ratio < 0.1f || ratio > 1.0f) {
                    ParseResult.Rejected("swipe 的 distance 超出范围 0.1..1.0")
                } else {
                    ParseResult.Success(Action.Swipe(direction, ratio))
                }
            }

            "input_text" -> {
                val text = obj["text"].asString()
                when {
                    text.isNullOrEmpty() -> ParseResult.Rejected("input_text 缺少 text")
                    text.length > MAX_TEXT_LENGTH -> ParseResult.Rejected("input_text 文本过长")
                    else -> ParseResult.Success(Action.InputText(text))
                }
            }

            "volume" -> {
                val command = VolumeCommand.from(obj["command"].asString())
                    ?: return ParseResult.Rejected("volume 的 command 非法")
                ParseResult.Success(Action.Volume(command))
            }

            "speak_screen" -> ParseResult.Success(Action.SpeakScreen())

            "notify" -> {
                val text = obj["text"].asString()
                if (text.isNullOrBlank()) {
                    ParseResult.Rejected("notify 缺少 text")
                } else {
                    ParseResult.Success(Action.Notify(text.trim()))
                }
            }

            "send_message" -> {
                val appName = obj["app_name"].asString()?.trim()
                val contact = obj["contact"].asString()?.trim()
                val content = obj["content"].asString()?.trim()
                when {
                    appName.isNullOrBlank() -> ParseResult.Rejected("send_message 缺少 app_name")
                    contact.isNullOrBlank() -> ParseResult.Rejected("send_message 缺少 contact")
                    content.isNullOrBlank() -> ParseResult.Rejected("send_message 缺少 content")
                    content.length > MAX_TEXT_LENGTH -> ParseResult.Rejected("send_message 内容过长")
                    else -> ParseResult.Success(Action.SendMessage(appName, contact, content))
                }
            }

            "confirm" -> {
                if (depth >= MAX_DEPTH) {
                    return ParseResult.Rejected("confirm 嵌套过深")
                }
                val question = obj["question"].asString()
                val pendingElement = obj["pending"]
                if (question.isNullOrBlank() || pendingElement == null) {
                    return ParseResult.Rejected("confirm 需要 question 与 pending")
                }
                when (val pending = parseAction(pendingElement, depth + 1)) {
                    is ParseResult.Rejected -> ParseResult.Rejected("confirm 内部动作非法：${pending.reason}")
                    is ParseResult.Success -> ParseResult.Success(
                        Action.Confirm(question.trim(), pending.action)
                    )
                }
            }

            else -> ParseResult.Rejected("未实现的动作「$actionName」")
        }
    }

    /** 容忍 LLM 把 JSON 包在 ```json 代码块里，或前后带解释文字 */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private fun JsonElement?.asString(): String? =
        runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonElement?.asInt(): Int? =
        runCatching { this?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun JsonElement?.asFloat(): Float? =
        runCatching { this?.jsonPrimitive?.floatOrNull }.getOrNull()
}
