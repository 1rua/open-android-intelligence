package com.openandroidintelligence.conversation.model

/** 宿主工具调用事实；正文、时间和列表位置不能替代这些原生身份。 */
data class NativeExecutionStep(
    val stepId: String,
    val eventId: String,
    val toolName: String,
    val arguments: String,
    val output: String? = null,
    val status: NativeExecutionStatus = NativeExecutionStatus.UNKNOWN,
    val revision: Long = 0,
)

/** COMPLETED 表示宿主返回了结果，不从结果正文推断执行成功。 */
enum class NativeExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    UNKNOWN,
}
