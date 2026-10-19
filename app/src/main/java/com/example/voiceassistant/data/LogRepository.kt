package com.example.voiceassistant.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 骨架阶段的内存日志仓库（最新的在最前）。
 * 模块 8 会替换为 Room 实现，对外接口保持不变。
 */
class LogRepository {

    private val seq = AtomicLong(0L)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun add(
        stage: LogStage,
        message: String,
        ok: Boolean = true,
        input: String? = null,
        costMillis: Long = 0L
    ) {
        val entry = LogEntry(
            id = seq.incrementAndGet(),
            timeMillis = System.currentTimeMillis(),
            stage = stage,
            input = input,
            message = message,
            ok = ok,
            costMillis = costMillis
        )
        _logs.update { current -> (listOf(entry) + current).take(MAX_ENTRIES) }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    /** 导出为纯文本，方便直接贴给 AI 排查 */
    fun exportText(): String {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return _logs.value.reversed().joinToString(separator = "\n") { entry ->
            val flag = if (entry.ok) "OK " else "ERR"
            val cost = if (entry.costMillis > 0) " ${entry.costMillis}ms" else ""
            val input = entry.input?.let { " << $it" } ?: ""
            "${formatter.format(Date(entry.timeMillis))} [$flag] ${entry.stage.label}$cost$input => ${entry.message}"
        }
    }

    companion object {
        const val MAX_ENTRIES = 1000
    }
}
