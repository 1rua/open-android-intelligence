package com.openandroidintelligence.conversation.remote

import java.util.Collections

/** 统一界面的操作语义，由各真实 carrier 的已确认能力映射而来。 */
enum class SessionCapability {
    LIST, HISTORY, CREATE, SEND, STREAM, RENAME, ARCHIVE, UNARCHIVE, DELETE,
    APPROVAL, CANCEL, STEER, COMMANDS, MODEL_SELECTION, EFFORT_SELECTION,
    WORKSPACE_UPLOAD, IMAGE_INPUT, FILE_INPUT,
}

/** 只在获得真实会话能力后建立；目录不存在与已确认空能力集合分别表达。 */
class SessionCapabilityDirectory(
    val owner: SessionOwner,
    supported: Set<SessionCapability>,
) {
    val supported: Set<SessionCapability> = Collections.unmodifiableSet(supported.toSet())

    fun supports(capability: SessionCapability, current: SessionOwner): Boolean =
        owner.isCurrent(current) && capability in supported
}
