package com.example.voiceassistant.data

/**
 * 一条执行日志。模块 8 会把它落进 Room，字段顺序保持稳定便于迁移。
 */
data class LogEntry(
    val id: Long,
    val timeMillis: Long,
    val stage: LogStage,
    val input: String? = null,
    val message: String,
    val ok: Boolean = true,
    val costMillis: Long = 0L
)

enum class LogStage(val label: String) {
    ASR("识别"),
    INTENT("解析"),
    CONFIRM("确认"),
    EXECUTE("执行"),
    LLM("模型"),
    SYSTEM("系统")
}
