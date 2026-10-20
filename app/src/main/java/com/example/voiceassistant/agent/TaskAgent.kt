package com.example.voiceassistant.agent

import com.example.voiceassistant.executor.ActionExecutor
import com.example.voiceassistant.intent.Action
import com.example.voiceassistant.intent.DangerRules
import com.example.voiceassistant.intent.IntentJsonValidator
import com.example.voiceassistant.intent.LlmGateway
import com.example.voiceassistant.intent.ParseResult
import com.example.voiceassistant.intent.describe
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 多步任务执行器（能干活的入口）。
 *
 * 和 IntentParser 的区别：IntentParser 一句话只换一个动作，做完就结束；
 * 这里是闭环——「看一眼屏幕 → 想一个动作 → 做 → 读结果和屏幕 → 再想」，
 * 直到模型说 done / 需要问用户 / 步数用完。
 *
 * 边界（刻意设的）：
 *  - 步数上限 [maxSteps]，绝不无限循环
 *  - 每个动作都过白名单校验（复用 IntentJsonValidator），模型编不出新动作
 *  - 危险动作（发送、支付、删除…）一律先问用户，不确认就中止整个任务
 *  - 连续重复同一动作且屏幕没变 → 视为卡住，直接收手，不空转
 */
class TaskAgent(
    private val gateway: LlmGateway,
    private val executor: ActionExecutor,
    private val snapshot: () -> String,
    private val onStep: (String) -> Unit = {}
) {

    /** 已执行的一步，回喂给模型用 */
    data class Executed(val description: String, val result: String)

    sealed interface Outcome {
        /** 任务做完了，summary 可直接念给用户 */
        data class Completed(val summary: String, val steps: List<Executed>) : Outcome

        /** 信息不够，必须问用户 */
        data class NeedsUserInput(val question: String, val steps: List<Executed>) : Outcome

        /** 做不下去（模型失败 / 用户取消 / 卡住 / 步数用尽） */
        data class Failed(val message: String, val steps: List<Executed>) : Outcome

        /** 这压根不是操作手机的任务，交回上层去聊天 */
        data object NotATask : Outcome
    }

    /**
     * 执行**一个子目标**（不是整句任务——整句已经由 TaskPlanner 拆过）。
     *
     * 只跑有限轮：小模型跑长循环只会越跑越偏，还耗电。
     *
     * @param task 当前子目标
     * @param maxSteps 这个子目标内最多几步
     * @param confirm 危险动作的二次确认；返回 false 表示用户拒绝，立即中止
     */
    suspend fun run(
        task: String,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        confirm: suspend (Action, String) -> Boolean
    ): Outcome {
        val steps = ArrayList<Executed>()
        var invalidOutputs = 0
        var lastSignature = ""
        var repeats = 0
        var index = 0

        while (index < maxSteps) {
            val screen = snapshot()
            val raw = gateway.complete(SYSTEM_PROMPT, buildUserPrompt(task, steps, screen))
                ?: return Outcome.Failed("模型没有响应", steps)

            val step = StepParser.parse(raw)
            if (step == null) {
                invalidOutputs++
                if (invalidOutputs >= 2) return Outcome.Failed("模型输出看不懂", steps)
                steps += Executed("（模型输出不合法）", "请只输出一个 JSON 动作")
                continue
            }
            invalidOutputs = 0

            when (step) {
                is Step.NotATask -> return if (steps.isEmpty()) {
                    Outcome.NotATask
                } else {
                    Outcome.Failed("中途判断这不是操作任务，已停手", steps)
                }

                is Step.Done -> return Outcome.Completed(
                    step.summary.ifBlank { "做完了" }, steps
                )

                is Step.Ask -> return Outcome.NeedsUserInput(
                    step.question.ifBlank { "还差一点信息，再说清楚些" }, steps
                )

                is Step.Wait -> {
                    val ms = step.seconds * 1000L
                    onStep("第${index + 1}步：等 ${step.seconds} 秒")
                    delay(ms)
                    steps += Executed("等待 ${step.seconds} 秒界面刷新", "完成")
                    index++
                }

                is Step.Act -> {
                    val description = step.action.describe()

                    // 卡住判定：同一动作连做三次且屏幕没变，说明点了个没反应的东西
                    val signature = description + "|" + screen.hashCode()
                    if (signature == lastSignature) {
                        repeats++
                        if (repeats >= 2) return Outcome.Failed("界面没反应，卡在「$description」", steps)
                    } else {
                        repeats = 0
                        lastSignature = signature
                    }

                    if (isDangerous(step.action)) {
                        val ok = confirm(step.action, "第${index + 1}步要${description}，确认吗？")
                        if (!ok) return Outcome.Failed("已取消", steps)
                    }

                    val result = executor.execute(step.action)
                    steps += Executed(description, result.message)
                    onStep("第${index + 1}步：$description → ${result.message}")
                    index++

                    // 给界面一点反应时间，否则下一步拿到的还是旧屏幕
                    delay(STEP_SETTLE_MS)
                }
            }
        }

        return Outcome.Failed("已经做了 $maxSteps 步还没完成，先停下", steps)
    }

    private fun isDangerous(action: Action): Boolean =
        action is Action.SendMessage || DangerRules.isDangerous(action.describe())

    private fun buildUserPrompt(task: String, steps: List<Executed>, screen: String): String =
        buildString {
            append("任务：").append(task).append("\n\n")

            if (steps.isEmpty()) {
                append("已执行：无（这是第一步）\n\n")
            } else {
                append("已执行：\n")
                steps.forEachIndexed { i, s -> append("${i + 1}. ${s.description} → ${s.result}\n") }
                append('\n')
            }

            append("当前屏幕：\n")
            append(screen.ifBlank { "（读不到界面，可能无障碍服务没开或在桌面上）" })
            append("\n\n注意：上面的序号只对本次屏幕有效，屏幕一变就要重新看列表。\n")
            append("输出下一步动作（只输出一个 JSON，不要解释）：")
        }

    /** 模型输出的一步 */
    private sealed interface Step {
        data class Act(val action: Action) : Step
        data class Wait(val seconds: Int) : Step
        data class Done(val summary: String) : Step
        data class Ask(val question: String) : Step
        data object NotATask : Step
    }

    private object StepParser {

        fun parse(raw: String): Step? {
            val json = extractObject(raw) ?: return null
            val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
                ?: return null
            val name = obj.string("action")?.trim() ?: return null

            return when (name) {
                "done", "finish" -> Step.Done(obj.string("summary").orEmpty())
                "ask" -> Step.Ask(obj.string("question").orEmpty())
                "none" -> Step.NotATask
                "wait" -> Step.Wait((obj.int("seconds") ?: 2).coerceIn(1, 10))
                else -> when (val parsed = IntentJsonValidator.parse(raw)) {
                    is ParseResult.Success -> Step.Act(parsed.action)
                    is ParseResult.Rejected -> null
                }
            }
        }

        /** 容忍 ```json 代码块和前后解释文字，只抠第一个 { 到最后一个 } */
        private fun extractObject(raw: String): String? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return raw.substring(start, end + 1)
        }

        private fun JsonObject.string(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

        private fun JsonObject.int(key: String): Int? =
            runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
    }

    companion object {
        /** 单个子任务的步数上限：小模型长循环必跑偏，还耗电 */
        const val DEFAULT_MAX_STEPS = 4

        /** 每个动作做完等界面稳定 */
        private const val STEP_SETTLE_MS = 700L

        val SYSTEM_PROMPT: String = """
            你是手机操作员。系统会把任务拆成若干子目标，你**每次只负责其中一个子目标**。
            每一轮只输出一个 JSON 动作；系统执行后会告诉你「执行结果」和「最新屏幕」，你再决定下一步。
            禁止解释、禁止 markdown、禁止一次输出多个动作。

            可用动作（只能选这些）：
            {"action":"open_app","app_name":"微信"}
            {"action":"click_index","index":3}
            {"action":"click_text","text":"确定"}
            {"action":"input_text","text":"晚上一起吃饭"}
            {"action":"swipe","direction":"up|down|left|right","distance":0.6}
            {"action":"back"}
            {"action":"home"}
            {"action":"volume","command":"up|down|mute|unmute"}
            {"action":"speak_screen"}
            {"action":"send_message","app_name":"微信","contact":"老王","content":"晚上一起吃饭"}
            {"action":"wait","seconds":2}
            {"action":"done","summary":"一句话说做了什么"}
            {"action":"ask","question":"要问用户的话"}
            {"action":"none"}

            规则：
            1. 一次只做一个动作，不要提前把后面的步骤做完。
            2. 点东西优先用 click_index，序号必须来自「可点击元素」列表，不能编造；列表里确实没有才用 click_text。
            3. 输入文字前必须先让输入框聚焦：没聚焦就先 click_index 点那个输入框，等结果回来再 input_text。
            4. 界面刚跳转、看着还在加载，用 wait，不要重复上一步。
            5. 「执行结果」显示失败时，换一种做法，不要原样重试。
            6. 给某人发一句话，能一次说清就用 send_message；要先看聊天记录再回才分步做。
            7. 任务完成立即输出 done，summary 一句话，会被念出来，别超过 30 字。
            8. 只有确实缺关键信息（找谁、发什么、第几个）才用 ask。
            9. 用户只是聊天、问问题、不要你动手机 → 输出 {"action":"none"}。
        """.trimIndent()
    }
}
