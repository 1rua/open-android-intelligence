package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.control.remote.ControlAccessBinding
import com.openandroidintelligence.control.remote.ControlOperation
import com.openandroidintelligence.control.remote.ControlRequestException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 复用手机本机确认；远端只提交公开引用，无法批准或选择可信收件主体。 */
class RemotePushRecipientAuthorizer(private val runtime: DevicePushRuntime) {
    suspend fun authorize(access: ControlAccessBinding,operation: ControlOperation.Delivery,
        requireCurrent: () -> Unit,awaitingConfirmation: (Boolean) -> Unit,
        confirmLocally: suspend (String) -> Boolean) {
        requireCurrent()
        val approval=runtime.recipientApproval(operation,access.principal) ?: return
        awaitingConfirmation(true)
        try {
            if (!confirmLocally(approval.localParameters())) throw ControlRequestException("LOCAL_USER_REJECTED")
            currentCoroutineContext().ensureActive()
            runtime.approveRemoteRecipient(approval,requireCurrent)
        } finally { awaitingConfirmation(false) }
        requireCurrent()
    }
}
