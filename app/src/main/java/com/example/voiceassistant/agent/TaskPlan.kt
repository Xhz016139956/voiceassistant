package com.example.voiceassistant.agent

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 任务分层：**主干由引擎拆，模型只负责单步**。
 *
 * 这是小模型的活法——让 1.7B/4B 自己规划长任务必跑偏，
 * 所以「把一句话拆成几个子目标、记住做到第几个、失败了怎么记」放在引擎里，
 * 模型只在某个子目标内做「看屏幕 → 输出一个动作」。
 */
object TaskPlanner {

    /** 子任务上限：再多就不是"助手"而是"失控" */
    const val MAX_SUB_TASKS = 5

    /**
     * 拆句规则：
     *  - 在「然后 / 接着 / 之后 / 随后 / 顺便 / 同时 / 最后」和逗号句号分号处切
     *  - 切出来的每一段至少 2 个字，否则并回原句
     *  - 不切引号内的内容（"说'好的，然后呢'"这种别散架）
     *
     * 为什么敢按逗号切：调用方是「整句规则没命中」才会来拆；
     * 「提醒我下午三点，开会」「输入你好，我是小明」这种整句早就被规则接走了。
     */
    private val CONNECTOR = Regex(
        """[，,。；;、]|然后|接着|之后|随后|顺便|同时|最后"""
    )

    fun split(utterance: String): List<String> {
        val text = utterance.trim()
        if (text.isEmpty()) return emptyList()

        val firstPass = splitOutsideQuotes(text)
            .map { clean(it) }
            .filter { it.length >= 2 }

        // 第二遍：没逗号的连说也要断，比如「打开deepseek输入怎么使用AI」
        val pieces = ArrayList<String>(firstPass.size)
        for (piece in firstPass) pieces += splitBeforeVerb(piece)

        val result = if (pieces.size <= 1) listOf(clean(text)) else pieces
        return result.take(MAX_SUB_TASKS)
    }

    /** 一句话里有几件事（>1 就不能当单动作执行） */
    fun isMultiStep(utterance: String): Boolean = split(utterance).size > 1

    /**
     * 在「输入 / 打字」前断开：动作动词后面通常跟着参数，
     * 而前面那半截往往已经是另一个动作了。
     */
    private fun splitBeforeVerb(piece: String): List<String> {
        for (verb in VERBS) {
            val at = piece.indexOf(verb)
            if (at < 2) continue
            val head = piece.substring(0, at).trim()
            val tail = piece.substring(at).trim()
            if (head.length >= 2 && tail.length > verb.length) {
                return listOf(head, tail)
            }
        }
        return listOf(piece)
    }

    private val VERBS = listOf("输入", "打字")

    /** 去掉句首客套话，否则「帮我打开微信」匹配不上规则里的「打开…」 */
    private val POLITE = listOf("请帮我", "帮我", "帮忙", "麻烦你", "麻烦", "请你", "请", "给我")

    private fun clean(raw: String): String {
        var text = raw.trim().trim('，', ',', '。', '；', ';', ' ')
        for (prefix in POLITE) {
            if (text.startsWith(prefix) && text.length > prefix.length + 1) {
                text = text.removePrefix(prefix).trim()
            }
        }
        return text
    }

    /** 引号内的连接词不切，避免把"说，然后呢"这种消息内容切碎 */
    private fun splitOutsideQuotes(text: String): List<String> {
        val openers = setOf('「', '“', '"', '\'', '‘')
        val closers = setOf('」', '”', '"', '\'', '’')

        val result = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var index = 0

        while (index < text.length) {
            val ch = text[index]
            if (ch in openers) depth++
            if (ch in closers && depth > 0) depth--

            if (depth == 0) {
                val match = CONNECTOR.matchAt(text, index)
                if (match != null && match.value.isNotEmpty()) {
                    result.add(current.toString())
                    current.setLength(0)
                    index = match.range.last + 1
                    continue
                }
            }

            current.append(ch)
            index++
        }
        result.add(current.toString())
        return result
    }
}

/** 单个子任务：目标 + 状态（状态会落盘，进程被杀也能看到做到哪） */
@Serializable
data class SubTask(
    val goal: String,
    val status: String = PENDING,
    val note: String = ""
) {
    companion object {
        const val PENDING = "pending"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/** 一次任务的完整状态 */
@Serializable
data class TaskState(
    val utterance: String,
    val createdAt: Long,
    val updatedAt: Long,
    val subtasks: List<SubTask>,
    val cursor: Int = 0,
    val finished: Boolean = false
) {
    val unfinished: Boolean get() = !finished && cursor < subtasks.size
    val doneCount: Int get() = subtasks.count { it.status == SubTask.DONE }
}

/**
 * 任务状态落盘。
 *
 * 手机后台随时可能把进程杀掉（尤其中端机跑模型时），
 * 所以每做完一个子任务就写一次；被杀之后还能捡起来接着做。
 */
class TaskStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    fun load(): TaskState? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<TaskState>(file.readText())
        }.getOrElse {
            Log.w(TAG, "任务状态读取失败，丢弃", it)
            null
        }
    }

    fun save(state: TaskState) {
        runCatching {
            // 先写临时文件再改名，避免写到一半被杀留下半个 JSON
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(state))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "任务状态写入失败", it) }
    }

    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }

    companion object {
        private const val TAG = "TaskStore"
        private const val FILE_NAME = "task_state.json"
    }
}
