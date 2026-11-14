package com.openandroidintelligence.control.remote

enum class ControlRequestState(val wire: String) {
    ACCEPTED("accepted"), AWAITING_CONFIRMATION("awaiting_confirmation"), RUNNING("running"),
    COMPLETED("completed"), FAILED("failed"), DENIED("denied"), CANCELLED("cancelled"), OUTCOME_UNKNOWN("outcome_unknown");
    val terminal: Boolean get() = this in setOf(COMPLETED, FAILED, DENIED, CANCELLED, OUTCOME_UNKNOWN)
}

enum class ControlExecutionStatus(val wire: String) {
    NOT_STARTED("not_started"), SUCCEEDED("succeeded"), FAILED("failed"), UNKNOWN("unknown")
}

/** 该记录只能包含最小执行元数据；正文、树、输入和产物字节没有持久入口。 */
data class ControlRequestMetadata(
    val ownerKey: String,
    val requestId: String,
    val taskSessionId: String,
    val operation: String,
    val parameterDigest: String,
    val state: ControlRequestState,
    val execution: ControlExecutionStatus,
    val executionFence: Boolean = false,
    val cancellationTarget: String? = null,
    val errorCode: String? = null,
) {
    val key: String get() = "$ownerKey:$requestId"
}

/** 实现必须在写入成功后才发布新状态，失败时不能授予副作用执行权。 */
interface ControlMetadataLedger {
    fun <T> transaction(change: (MutableMap<String, ControlRequestMetadata>) -> T): T
}

data class PhoneControlResult(
    val result: ControlValue.Object,
    val artifacts: List<ControlArtifact> = emptyList(),
    val ui: ControlValue.Object? = null,
)

data class ControlArtifact(val id: String, val mimeType: String, val version: String, val bytes: ByteArray)

data class PhoneExecutionContext(
    val scope: ControlRequestScope,
    val access: ControlAccessBinding,
    val parameterDigest: String,
    /** 真正进入原生端口之前调用；原子复核取消并持久记录栅栏。 */
    val beforeDispatch: () -> Unit,
    /** 只投影手机本机确认事实，不向远端提供可回答的审批句柄。 */
    val awaitingConfirmation: (Boolean) -> Unit,
)

interface PhoneControlExecutor {
    suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult
    fun cancel(scope: ControlRequestScope)
    fun capabilities(access: ControlAccessBinding): ControlValue.Object
}
