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
    fun endpoint(): PushEndpoint = PushEndpoint(origin,accountId,Json.sha256(encode()),null,this)
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
    /** 只传非秘密引用；接口没有token或正文入口。 */
    suspend fun notifyReference(requestId: String, reference: RemotePushReference, beforeSend: () -> Unit)
}

/** 收件回执只保存投递状态；旧版凭证摘要仅供审计，不能继承为新授权。 */
internal data class RemotePushReceiptRecord(
    val deliveryId: String,
    val endpoint: PushEndpoint,
    val eventId: String,
    val payloadDigest: String,
    val expiresAt: Long,
    val consumed: Boolean = false,
    val readerKey: String? = null,
    val dispatchState: String = "prepared",
    val legacyTokenHash: String? = null,
    val legacyReaderKey: String? = null,
    val kind: PushKind? = null,
)
internal class RemotePushDispatch(val receipt: RemotePushReceiptRecord,val shouldNotify: Boolean)

/** 普通会话允许持久化的非秘密通知；读取权仅由手机当前可信主体与本机授权授予。 */
data class RemotePushReference(val phoneHostId: String,val deliveryId: String,val eventId: String,val payloadDigest: String,val expiresAtEpochMs: Long) {
    fun encode(): JsonValue = Json.of(mapOf("type" to "oai.android.delivery","version" to 2,
        "phoneHostId" to phoneHostId,"deliveryId" to deliveryId,"eventId" to eventId,"payloadDigest" to payloadDigest,
        "expiresAtEpochMs" to expiresAtEpochMs,"readTool" to "android_read_delivery","ackTool" to "android_ack_delivery"))
}

/** 仅本机批准写入；键为包含Host/pin/backend/workspace/session的完整端点。 */
internal data class RemotePushRecipientBinding(
    val principalKey: String,
    val configurationDigest: String,
    val active: Boolean = true,
)

/** 确认详情来自本机持久目标和认证通道，远端参数不能覆盖其中任一身份字段。 */
internal data class RemotePushRecipientApproval(
    val deliveryId: String,
    val eventId: String,
    val payloadDigest: String,
    val endpoint: PushEndpoint,
    val reader: com.openandroidintelligence.control.remote.RemoteControlPrincipal,
    val configurationDigest: String,
) {
    fun localParameters(): String {
        val target=checkNotNull(endpoint.remoteTarget)
        return Json.canonical(Json.of(mapOf("controllerDeviceId" to reader.controllerDeviceId,
            "controllerKeyFingerprint" to reader.controllerKeyFingerprint,"origin" to target.origin,
            "accountId" to target.accountId,"hostDeviceId" to target.hostDeviceId,"hostKeyFingerprint" to target.hostKeyFingerprint,
            "backend" to target.backend,"workspaceId" to target.workspaceId,"sessionId" to target.sessionId)))
    }
}
