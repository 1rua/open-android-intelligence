package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.state.WorkbenchController

/** Owns bubble controllers independently of the main window's selection and drafts. */
internal class BubbleConversationWorkspace(
    private val profileId: String,
    private val accountId: String,
    private val create: (ReplyNotificationTarget) -> WorkbenchController?,
) : AutoCloseable {
    private var closed = false
    private val controllers = mutableSetOf<WorkbenchController>()
    private val detached = mutableSetOf<WorkbenchController>()

    @Synchronized fun open(target: ReplyNotificationTarget): WorkbenchController? {
        if (closed || !accepts(target)) return null
        return create(target)?.also { controllers += it }
    }

    /** 会话 ID 只在同一个 Agent 内唯一，带上 Agent 身份才不会打开另一个 Agent 的同名会话。 */
    fun accepts(target: ReplyNotificationTarget): Boolean =
        target.profileId == profileId && target.accountId == accountId && target.conversationId.isNotBlank()

    /** UI disposal cannot cancel a submission that this session still owns. */
    @Synchronized fun release(controller: WorkbenchController): Boolean {
        if (controller !in controllers) return true
        if (controller.hasActiveWork.value) {
            detached += controller
            return false
        }
        detached -= controller
        controllers.remove(controller)
        controller.close()
        return true
    }

    @Synchronized fun releaseIfIdle(controller: WorkbenchController): Boolean {
        if (controller !in detached || controller.hasActiveWork.value) return false
        return release(controller)
    }

    @Synchronized override fun close() {
        closed = true
        controllers.forEach(WorkbenchController::close)
        controllers.clear()
        detached.clear()
    }
}
