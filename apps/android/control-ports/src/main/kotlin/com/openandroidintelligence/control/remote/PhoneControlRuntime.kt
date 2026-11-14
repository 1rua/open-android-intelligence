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

/** 由可信手机执行器给出的终态；插件的任意 JSON 字段不能直接决定控制账本。 */
enum class PhoneControlCompletion { SUCCEEDED, SMS_SEND_OUTCOME_UNKNOWN }

data class PhoneControlResult(
    val result: ControlValue.Object,
    val artifacts: List<ControlArtifact> = emptyList(),
    val ui: ControlValue.Object? = null,
    val completion: PhoneControlCompletion = PhoneControlCompletion.SUCCEEDED,
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
    /** 短信专用：持久消费本请求唯一发送权；不能复用可重复的普通执行检查。 */
    val consumeSmsSendEffect: () -> Unit = { throw ControlRequestException("SMS_EXECUTION_FENCE_REQUIRED") },
)

interface PhoneControlExecutor {
    /** 仅核对当前可信连接；本机急停不使合法主体失去最小回执查询权。 */
    fun requireCurrentAccess(access: ControlAccessBinding)
    suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult
    fun cancel(scope: ControlRequestScope)
    fun capabilities(access: ControlAccessBinding): ControlValue.Object
}
