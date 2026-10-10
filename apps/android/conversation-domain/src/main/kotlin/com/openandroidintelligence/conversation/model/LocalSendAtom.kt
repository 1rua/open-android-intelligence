package com.openandroidintelligence.conversation.model

import com.openandroidintelligence.conversation.ports.AgentMessageErrorCode
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.TimelineMessage

/** 本地提交状态与 Agent 投递状态各自保存，HTTP 接受不代表 Agent 已完成。 */
enum class LocalSubmissionState { PREPARED, ACCEPTED, FAILED, OUTCOME_UNKNOWN }

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
) {
    fun accepted(serverId: String, serverBatchId: String? = batchId): LocalSendAtom {
        require(serverId.isNotBlank()) { "MESSAGE_ID_MISSING" }
        check(messageId == null || messageId == serverId) { "MESSAGE_ID_CONFLICT" }
        return copy(messageId = serverId, batchId = serverBatchId,
            submissionState = LocalSubmissionState.ACCEPTED, status = status ?: AgentMessageStatus.QUEUED,
            submissionErrorCode = null)
    }
}

/** 仅凭明确身份回并发送事实；空快照和无正文的受理查询不能抹掉本地内容。 */
fun mergeLocalSendAtoms(remote: List<TimelineMessage>, atoms: List<LocalSendAtom>): List<TimelineMessage> {
    val rows = remote.associateByTo(linkedMapOf()) { it.id }
    atoms.forEach { atom ->
        val matching = rows.values.firstOrNull { row ->
            (atom.messageId != null && row.id == atom.messageId) || row.clientMessageId == atom.clientMessageId
        }
        if (atom.tombstoneRevision != null) {
            matching?.let { rows.remove(it.id) }
            return@forEach
        }
        val id = matching?.id ?: atom.messageId ?: "local_${atom.clientMessageId.value}"
        rows[id] = TimelineMessage(
            id = id, sender = "user",
            parts = if (atom.contentRevision >= 0L) atom.parts
                else matching?.parts?.takeIf { it.isNotEmpty() } ?: atom.parts,
            timestamp = atom.timestamp.takeIf { it > 0L } ?: matching?.timestamp ?: 0L,
            state = atom.status?.wireValue ?: when (atom.submissionState) {
                LocalSubmissionState.ACCEPTED -> "queued"
                LocalSubmissionState.FAILED -> "FAILED"
                else -> "PENDING"
            },
            conversationId = atom.conversationId,
            errorCode = atom.errorCode?.wireValue,
            batchId = atom.batchId ?: matching?.batchId,
            clientMessageId = atom.clientMessageId,
            localSubmissionFailure = atom.submissionErrorCode.takeIf { atom.submissionState == LocalSubmissionState.FAILED },
        )
    }
    return rows.values.sortedWith(compareBy<TimelineMessage> { it.timestamp }.thenBy { if (it.sender == "user") 0 else 1 })
}
