package com.example.voiceassistant.agent

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 一轮对话：用户说了什么、回了他什么 */
@Serializable
data class ConversationTurn(
    val user: String,
    val reply: String,
    val at: Long
)

/**
 * 指代消解（纯函数，可单测）。
 *
 * 人说话大量用「它」「那个」，靠上文补全；没有这层，助手就永远听不懂省略句。
 * 这里只做**保守替换**：只在明确是"打开/关掉/进入 <代词>"、"发给 <代词>"时替换，
 * 宁可不认，也不乱替换——替错了比不替更糟。
 */
object ReferenceResolver {

    private val PRONOUNS = listOf("它", "那个", "这个", "刚才那个", "刚才那个应用", "刚才那个软件")
    private val OPEN_VERBS = listOf("打开", "关掉", "关闭", "进入", "启动", "卸载")
    private val SEND_VERBS = listOf("发给", "给", "告诉", "跟", "和")

    fun resolve(text: String, lastApp: String?, lastContact: String?): String {
        var result = text
        if (!lastApp.isNullOrBlank()) {
            for (verb in OPEN_VERBS) {
                for (pronoun in PRONOUNS) {
                    result = result.replace("$verb$pronoun", "$verb$lastApp")
                }
            }
        }
        if (!lastContact.isNullOrBlank()) {
            for (pronoun in PRONOUNS) {
                result = result.replace("发给$pronoun", "发给$lastContact")
                result = result.replace("给${pronoun}发", "给${lastContact}发")
            }
        }
        return result
    }
}

/**
 * 短期对话记忆。
 *
 * 只存最近 N 轮短句 + 最近提到的应用/联系人，落盘，进程重启还在。
 * 目的不是"更聪明"，是让省略句、指代、连续操作能接得上——这是"像人"的底座。
 */
class ConversationMemory(context: Context, private val limit: Int = 20) {

    @Serializable
    private data class Snapshot(
        val turns: List<ConversationTurn> = emptyList(),
        val lastApp: String? = null,
        val lastContact: String? = null
    )

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val turns = ArrayDeque<ConversationTurn>()

    @Volatile
    var lastApp: String? = null
        private set

    @Volatile
    var lastContact: String? = null
        private set

    init {
        load()
    }

    fun record(user: String, reply: String) {
        val ask = user.trim()
        if (ask.isEmpty()) return
        turns.addLast(ConversationTurn(ask, reply.trim(), System.currentTimeMillis()))
        while (turns.size > limit) turns.removeFirst()
        save()
    }

    fun noteApp(name: String) {
        val value = name.trim()
        if (value.isNotEmpty()) {
            lastApp = value
            save()
        }
    }

    fun noteContact(name: String) {
        val value = name.trim()
        if (value.isNotEmpty()) {
            lastContact = value
            save()
        }
    }

    /** 把「打开它」还原成「打开微信」 */
    fun resolveReferences(text: String): String =
        ReferenceResolver.resolve(text, lastApp, lastContact)

    /** 拼进模型 prompt 的上文；只给最近几轮，别把上下文撑爆 */
    fun promptContext(count: Int = 4): String {
        val recent = turns.toList().takeLast(count)
        if (recent.isEmpty()) return ""
        return buildString {
            append("刚才的对话（参考，不要重复执行）：\n")
            for (turn in recent) {
                append("用户：").append(turn.user.take(40)).append('\n')
                if (turn.reply.isNotBlank()) append("你：").append(turn.reply.take(40)).append('\n')
            }
        }
    }

    fun clear() {
        turns.clear()
        lastApp = null
        lastContact = null
        runCatching { if (file.exists()) file.delete() }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            json.decodeFromString<Snapshot>(file.readText())
        }.onSuccess { snapshot ->
            turns.clear()
            turns.addAll(snapshot.turns.takeLast(limit))
            lastApp = snapshot.lastApp
            lastContact = snapshot.lastContact
        }.onFailure { Log.w(TAG, "对话记忆读取失败", it) }
    }

    private fun save() {
        runCatching {
            val snapshot = Snapshot(turns.toList(), lastApp, lastContact)
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "对话记忆写入失败", it) }
    }

    companion object {
        private const val TAG = "ConvMemory"
        private const val FILE_NAME = "conversation.json"
    }
}
