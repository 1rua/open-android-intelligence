package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.asChoice

/**
 * One command-execution approval card's lifecycle (contract §7.2).
 *
 * The state advances in one direction only: a press becomes [Submitting], and
 * [Resolved] is the end of the road. A press that failed is allowed back to
 * [Waiting] because the user has to be able to try again — everything else is
 * final, because the Gateway has answered and no local opinion outranks it.
 */
sealed interface ApprovalCardState {

    val request: ApprovalRequest

    /** The user has not answered yet; the buttons are live. */
    data class Waiting(override val request: ApprovalRequest) : ApprovalCardState

    /**
     * The press is with the Gateway.
     *
     * This is the "已受理" feedback the user gets immediately: the whole button
     * group locks here, so a second press cannot turn one decision into two. It
     * is explicitly *not* a result — the card is not allowed to read "已允许"
     * until the Gateway says so.
     */
    data class Submitting(
        override val request: ApprovalRequest,
        val choice: ApprovalChoice,
    ) : ApprovalCardState

    /**
     * The Gateway settled the approval.
     *
     * Only a Gateway fact may produce this: the HTTP answer to a press, or the
     * `conversation.approval.resolved` event. A phone never decides on its own
     * that a command was allowed.
     */
    data class Resolved(
        override val request: ApprovalRequest,
        val outcome: ApprovalOutcome,
        val decidedAt: Long? = null,
    ) : ApprovalCardState
}

/** Whether the Gateway has had its say. */
val ApprovalCardState.isSettled: Boolean get() = this is ApprovalCardState.Resolved

/**
 * The tier this card ended on, when the Gateway named one.
 *
 * Both vocabularies carry the same four tiers as their wire tokens, so the
 * translation lives in one place ([asChoice]) instead of a `when` here that has
 * to be kept in step with the enum by hand.
 */
val ApprovalCardState.settledChoice: ApprovalChoice?
    get() = (this as? ApprovalCardState.Resolved)?.outcome?.asChoice

/**
 * How much of the Gateway's window is left, as the phone reads it.
 *
 * The countdown is presentation: it is derived from the Gateway's own
 * `requestedAt`/`timeoutSeconds` and never stored, so re-entering the thread
 * resumes the same number instead of restarting it. Reaching zero disables the
 * buttons locally, but only the Gateway's `timeout` event may write the outcome.
 */
data class ApprovalCountdown(
    val remainingMillis: Long,
    val expired: Boolean,
) {
    /** Whole seconds left, rounded up so "1s" is still visible before it ends. */
    val remainingSeconds: Long get() = ((remainingMillis + 999L) / 1_000L).coerceAtLeast(0L)
}

fun ApprovalRequest.countdownAt(now: Long): ApprovalCountdown {
    val remaining = expiresAt - now
    return ApprovalCountdown(
        remainingMillis = remaining.coerceAtLeast(0L),
        expired = remaining <= 0L,
    )
}

/** The timeline key one card owns: never a message key, so dedup cannot fold it. */
fun approvalTimelineKey(approvalId: String): String = "approval_$approvalId"

/**
 * The notices an approval card can raise.
 *
 * Public so a test asserts the same strings the screen shows: a notice that only
 * the controller knows about cannot be regression-tested.
 */
object ApprovalNotices {
    /** The Gateway cannot take a decision at all (contract §7.2). */
    const val UNSUPPORTED = "APPROVAL_UNSUPPORTED:GATEWAY_UNSUPPORTED"

    /** The window the Gateway gave is over, locally. */
    const val EXPIRED = "APPROVAL_EXPIRED:LOCAL_TIMEOUT"

    /** The press did not reach the Gateway; the card is live again. */
    const val FAILED = "APPROVAL_FAILED:SUBMIT_FAILED"
}
