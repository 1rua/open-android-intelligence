package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.control.remote.ControlOrigin
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/** 用户明确选择的 Remote 会话；名称和短命 connectionId 均不是持久投递身份。 */
data class RemotePushTarget(
    val origin: String,
    val accountId: String,
    val accountEpoch: Long,
    val clientDeviceId: String,
    val clientKeyFingerprint: String,
    val hostDeviceId: String,
    val hostKeyFingerprint: String,
    val phoneHostDeviceId: String,
    val phoneHostKeyFingerprint: String,
    val backend: String,
    val workspaceId: String,
    val sessionId: String,
) {
    init {
        ControlOrigin.fromCanonical(origin)
        require(listOf(accountId,clientDeviceId,hostDeviceId,phoneHostDeviceId,backend,workspaceId,sessionId).all {
            it.isNotBlank() && it.length <= 256 && '\u0000' !in it
        }) { "PUSH_REMOTE_TARGET_INVALID" }
        require(accountEpoch >= 0 && clientDeviceId != phoneHostDeviceId) { "PUSH_REMOTE_TARGET_INVALID" }
        require(listOf(clientKeyFingerprint,hostKeyFingerprint,phoneHostKeyFingerprint).all {
            Regex("sha256:[0-9a-f]{64}").matches(it)
        }) { "PUSH_REMOTE_TARGET_KEY_INVALID" }
    }
    fun encode(): JsonValue = Json.of(mapOf("origin" to origin,"accountId" to accountId,"accountEpoch" to accountEpoch,
        "clientDeviceId" to clientDeviceId,"clientKeyFingerprint" to clientKeyFingerprint,
        "hostDeviceId" to hostDeviceId,"hostKeyFingerprint" to hostKeyFingerprint,
        "phoneHostDeviceId" to phoneHostDeviceId,"phoneHostKeyFingerprint" to phoneHostKeyFingerprint,
        "backend" to backend,"workspaceId" to workspaceId,"sessionId" to sessionId))
    fun endpoint(): PushEndpoint = PushEndpoint(origin,accountId,Json.sha256(encode()),accountEpoch+1,this)
    companion object {
        fun decode(value: JsonValue): RemotePushTarget {
            val row=value as? JsonValue.JObject ?: error("PUSH_REMOTE_TARGET_INVALID")
            fun text(key: String)=JsonFields.string(row,key) ?: error("PUSH_REMOTE_TARGET_INVALID")
            return RemotePushTarget(text("origin"),text("accountId"),JsonFields.long(row,"accountEpoch") ?: error("PUSH_REMOTE_TARGET_INVALID"),
                text("clientDeviceId"),text("clientKeyFingerprint"),text("hostDeviceId"),text("hostKeyFingerprint"),
                text("phoneHostDeviceId"),text("phoneHostKeyFingerprint"),text("backend"),text("workspaceId"),text("sessionId"))
        }
    }
}

/** 仅借用当前已选择的真实会话；接口没有连接、切换或创建入口。 */
interface RemotePushSession {
    val target: RemotePushTarget
    val label: String
    fun isCurrent(): Boolean
    suspend fun notify(requestId: String, text: String, beforeSend: () -> Unit)
}

/** 收件能力令牌只送进确切目标会话，不进入发现列表、诊断或普通回执。 */
internal data class RemotePushReceiptRecord(
    val deliveryId: String,
    val endpoint: PushEndpoint,
    val eventId: String,
    val payloadDigest: String,
    val tokenHash: String,
    val expiresAt: Long,
    val consumed: Boolean = false,
    val readerKey: String? = null,
    val dispatchState: String = "prepared",
)
internal class RemotePushDispatch(val receipt: RemotePushReceiptRecord,val token: String?)
