package com.openandroidintelligence.conversation.model

import com.openandroidintelligence.conversation.ports.AgentMessageErrorCode
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.MessageDeliveryMode
import com.openandroidintelligence.conversation.ports.TimelineMessage

/** 本地提交状态与 Agent 投递状态各自保存，HTTP 接受不代表 Agent 已完成。 */
enum class LocalSubmissionState { PREPARED, QUEUED, ACCEPTED, FAILED, OUTCOME_UNKNOWN }

/** 每次明确提交的持久记录；会话和客户端身份在重试、切换和重启后保持稳定。 */
data class LocalSendAtom(
    val conversationId: ConversationId,
    val clientMessageId: ClientMessageId,
    val parts: List<MessagePart>,
    val timestamp: Long,
    val messageId: String? = null,
    val batchId: String? = null,
    val submissionState: LocalSubmissionState = LocalSubmissionState.PREPARED,
    val status: AgentMessageStatus? = null,
    val statusRevision: Long = -1L,
    val errorCode: AgentMessageErrorCode? = null,
    val submissionErrorCode: String? = null,
    val contentRevision: Long = -1L,
    val tombstoneRevision: Long? = null,
    /** 快照明确删除属于身份事实；分页摘要不能用作单条内容修订。 */
    val snapshotDeleted: Boolean = false,
    /** Keep steering and queued corrections distinguishable after recovery. */
    val delivery: MessageDeliveryMode = MessageDeliveryMode.NORMAL,
) {
    val isDeleted: Boolean get() = snapshotDeleted || tombstoneRevision != null

    fun accepted(serverId: String, serverBatchId: String? = batchId): LocalSendAtom {
        require(serverId.isNotBlank()) { "MESSAGE_ID_MISSING" }
        check(messageId == null || messageId == serverId) { "MESSAGE_ID_CONFLICT" }
        return copy(messageId = serverId, batchId = serverBatchId,
            submissionState = LocalSubmissionState.ACCEPTED, status = status ?: AgentMessageStatus.QUEUED,
            submissionErrorCode = null)
    }

    /**
     * 事件驱动的身份确认：同一 clientMessageId 可能先后带来客户端占位 id 与服务端真实 id。
     *
     * 客户端 id 只是受理前的占位身份，服务端一旦给出真实 id 就应当升级；而已经绑定到
     * 另一个服务端身份的原子不再改写 id（否则同一条消息会在时间线里被拆成两行），
     * 但状态与投递事实仍需推进。**这里绝不抛异常**：事件链路上的身份分叉不能让进程崩溃。
     */
    fun identified(serverId: String, serverBatchId: String? = batchId): LocalSendAtom {
        if (serverId.isBlank()) return this
        val provisional = messageId == null ||
            messageId == clientMessageId.value ||
            (delivery == MessageDeliveryMode.STEER && isLegacySyntheticSteerId(messageId, clientMessageId))
        val adopt = provisional || messageId == serverId
        return copy(
            messageId = if (adopt) serverId else messageId,
            batchId = serverBatchId ?: batchId,
            submissionState = LocalSubmissionState.ACCEPTED,
            status = status ?: AgentMessageStatus.QUEUED,
            submissionErrorCode = null,
        )
    }

    companion object {
        fun isLegacySyntheticSteerId(id: String?, clientId: ClientMessageId): Boolean =
            id != null && id == "steer_${clientId.value}"
    }
}

/** 只认明确删除与稳定身份，不将空页或缺正文的受理查询当作删除。 */
fun reconcileSnapshotDeletions(remote: List<TimelineMessage>, atoms: List<LocalSendAtom>): List<LocalSendAtom> =
    atoms.map { atom ->
        val deleted = findMatchingRemoteRow(remote, atom)?.takeIf { it.isDeleted }
        if (deleted == null) atom else atom.identified(deleted.id, deleted.batchId ?: atom.batchId)
            .copy(parts = emptyList(), snapshotDeleted = true)
    }

private fun TimelineMessage.isInAtomScope(atom: LocalSendAtom): Boolean {
    return sender == "user" && (conversationId == null || conversationId == atom.conversationId)
}

private fun findMatchingRemoteRow(rows: Collection<TimelineMessage>, atom: LocalSendAtom): TimelineMessage? {
    val scopedRows = rows.filter { it.isInAtomScope(atom) }
    atom.messageId?.let { id -> scopedRows.firstOrNull { it.id == id }?.let { return it } }

    val hasProvisionalIdentity = atom.messageId == null ||
        atom.messageId == atom.clientMessageId.value ||
        (atom.delivery == MessageDeliveryMode.STEER && LocalSendAtom.isLegacySyntheticSteerId(atom.messageId, atom.clientMessageId))
    if (!hasProvisionalIdentity) return null

    return scopedRows.filter { row ->
        !row.localPlaceholder && row.clientMessageId == atom.clientMessageId
    }.singleOrNull()
}

private fun localPlaceholderId(rows: Map<String, TimelineMessage>, atom: LocalSendAtom): String {
    val baseId = "local_${atom.clientMessageId.value}"
    if (baseId !in rows) return baseId

    var suffix = 1
    while ("${baseId}_$suffix" in rows) suffix++
    return "${baseId}_$suffix"
}

/** 仅凭明确身份回并发送事实；空快照和无正文的受理查询不能抹掉本地内容。 */
fun mergeLocalSendAtoms(remote: List<TimelineMessage>, atoms: List<LocalSendAtom>): List<TimelineMessage> {
    val rows = remote.associateByTo(linkedMapOf()) { it.id }
    atoms.forEach { atom ->
        val matching = findMatchingRemoteRow(rows.values, atom)
        if (atom.isDeleted || matching?.isDeleted == true) {
            matching?.let { rows.remove(it.id) }
            return@forEach
        }
        val isSyntheticSteerId = atom.delivery == MessageDeliveryMode.STEER &&
            LocalSendAtom.isLegacySyntheticSteerId(atom.messageId, atom.clientMessageId)
        val proposedId = matching?.id ?: (if (isSyntheticSteerId) null else atom.messageId) ?: "local_${atom.clientMessageId.value}"
        val idConflict = matching == null && proposedId in rows
        val id = if (idConflict) localPlaceholderId(rows, atom) else proposedId
        // Core 历史中的真实 user 行可以证明消息存在，但不等于 Agent 已接收。
        // 只有显式 Agent 状态可覆盖它；本地占位行没有这项权威。
        val authoritativeRemoteState = matching
            ?.takeUnless { it.localPlaceholder }
            ?.state
            ?.takeIf(String::isNotBlank)
        rows[id] = TimelineMessage(
            id = id,
            sender = "user",
            parts = if (atom.contentRevision >= 0L) atom.parts
                else matching?.parts?.takeIf { it.isNotEmpty() } ?: atom.parts,
            timestamp = atom.timestamp.takeIf { it > 0L } ?: matching?.timestamp ?: 0L,
            state = atom.status?.wireValue ?: authoritativeRemoteState ?: when (atom.submissionState) {
                LocalSubmissionState.ACCEPTED -> "queued"
                LocalSubmissionState.FAILED -> "FAILED"
                else -> "PENDING"
            },
            conversationId = atom.conversationId,
            errorCode = atom.errorCode?.wireValue,
            batchId = atom.batchId ?: matching?.batchId,
            clientMessageId = atom.clientMessageId,
            // 本地投递终态不等于取得权威消息 ID；保留来源，避免占位行被当成另一条原生输入。
            localPlaceholder = matching?.localPlaceholder ?:
                (atom.messageId == null ||
                 atom.messageId == atom.clientMessageId.value ||
                 isSyntheticSteerId ||
                 idConflict),
            generationId = matching?.generationId,
            localSubmissionFailure = atom.submissionErrorCode.takeIf { atom.submissionState == LocalSubmissionState.FAILED },
            contentRevision = atom.contentRevision.takeIf { it >= 0L } ?: matching?.contentRevision,
        )
    }
    return rows.values.filterNot { it.isDeleted }
        .sortedBy { it.timestamp }
}
