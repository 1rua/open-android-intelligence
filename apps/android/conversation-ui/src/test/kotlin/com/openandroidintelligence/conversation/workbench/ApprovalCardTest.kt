package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalOptionStyle
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.state.ApprovalCardState
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The card the user actually presses.
 *
 * These tests pin what a user notices first: the badge shows the time the
 * Gateway really gave, a press locks the whole group so one decision cannot be
 * sent twice, and a closed window leaves nothing clickable.
 *
 * The countdown's per-second arithmetic is asserted where it lives — on the
 * request itself, in `ApprovalStateMachineTest` — rather than by driving the
 * Compose clock from here: the ticking is presentation, and a test that depends
 * on how a test framework virtualises `delay` would be testing the framework.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalCardTest {

    @get:Rule
    val compose = createComposeRule()

    private var virtualNow = REQUESTED_AT

    private fun request(
        options: List<ApprovalOption> = DEFAULT_OPTIONS,
        timeoutSeconds: Long = TIMEOUT_SECONDS,
    ) = ApprovalRequest(
        approvalId = ApprovalId("apr_1"),
        conversationId = ConversationId("conv_1"),
        command = "python3 -c \"print(1)\"",
        reason = "内联解释器执行",
        severity = "elevated",
        options = options,
        timeoutSeconds = timeoutSeconds,
        requestedAt = REQUESTED_AT,
    )

    private fun setCard(
        state: ApprovalCardState,
        onDecide: (ApprovalChoice) -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    ApprovalCard(state = state, onDecide = onDecide, clock = { virtualNow })
                }
            }
        }
    }

    @Test
    fun theCountdownBadgeShowsTheTimeTheGatewayGave() {
        // The badge reads the Gateway's own deadline, so the number on screen is
        // the time the approval really has left — not a per-screen timer.
        virtualNow = REQUESTED_AT + 15_000L
        setCard(ApprovalCardState.Waiting(request()))

        compose.onNodeWithTag(ApprovalCardTags.COUNTDOWN).assertIsDisplayed()
        compose.onNodeWithText("⏱️ 285s").assertIsDisplayed()
    }

    @Test
    fun theCardOffersExactlyTheTiersTheGatewaySent() {
        setCard(
            ApprovalCardState.Waiting(
                request(
                    options = listOf(
                        ApprovalOption(ApprovalChoice.ONCE, label = "Allow Once", style = ApprovalOptionStyle.PRIMARY),
                        ApprovalOption(ApprovalChoice.DENY, label = "Deny", style = ApprovalOptionStyle.DANGER),
                    ),
                ),
            ),
        )

        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.ONCE)).assertIsDisplayed()
        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.DENY)).assertIsDisplayed()
        // A tier the Gateway did not offer has no button at all.
        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.ALWAYS)).assertDoesNotExist()
        // The Gateway's own wording wins over the phone's.
        compose.onNodeWithText("Allow Once").assertIsDisplayed()
        compose.onNodeWithText("python3 -c \"print(1)\"").assertIsDisplayed()
        compose.onNodeWithText("内联解释器执行").assertIsDisplayed()
    }

    @Test
    fun aPressLocksTheWholeGroupAndSpinsThePressedTier() {
        var decided: ApprovalChoice? = null
        compose.setContent {
            var state by remember { mutableStateOf<ApprovalCardState>(ApprovalCardState.Waiting(request())) }
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    ApprovalCard(
                        state = state,
                        onDecide = { choice ->
                            decided = choice
                            state = ApprovalCardState.Submitting(state.request, choice)
                        },
                        clock = { virtualNow },
                    )
                }
            }
        }

        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.ONCE)).performClick()
        compose.waitForIdle()

        kotlin.test.assertEquals(ApprovalChoice.ONCE, decided)
        // The pressed tier is busy; every other tier is inert.
        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.ALWAYS)).assertIsNotEnabled()
        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.DENY)).assertIsNotEnabled()
        compose.onNodeWithText("允许一次").assertDoesNotExist()
    }

    @Test
    fun aWindowThatHasAlreadyClosedGreysEveryButtonAndSaysSo() {
        // The window closing is a fact about the Gateway's deadline, so the card
        // reaches this state on its own — it does not need the Gateway to answer
        // first, and it never leaves a live button under a command that will not
        // run.
        virtualNow = REQUESTED_AT + TIMEOUT_SECONDS * 1_000L + 1L
        setCard(ApprovalCardState.Waiting(request()))

        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.ONCE)).assertIsNotEnabled()
        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.DENY)).assertIsNotEnabled()
        compose.onNodeWithText("已超时").assertIsDisplayed()
        compose.onNodeWithText("审批已超时，命令不会再执行").assertIsDisplayed()
    }

    @Test
    fun aPressInFlightPastTheWindowSaysTheWindowIsOver() {
        virtualNow = REQUESTED_AT + TIMEOUT_SECONDS * 1_000L + 1L
        setCard(
            ApprovalCardState.Submitting(
                request = request(),
                choice = ApprovalChoice.ONCE,
            ),
        )

        compose.onNodeWithText("审批已超时，命令不会再执行").assertIsDisplayed()
        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.DENY)).assertIsNotEnabled()
    }

    @Test
    fun aSettledCardLosesItsButtonsAndKeepsItsConclusion() {
        setCard(
            ApprovalCardState.Resolved(
                request = request(),
                outcome = ApprovalOutcome.DENIED,
                decidedAt = REQUESTED_AT + 2_000L,
            ),
        )

        compose.onNodeWithTag(ApprovalCardTags.OUTCOME).assertIsDisplayed()
        compose.onNodeWithText("已拒绝，命令不会执行").assertIsDisplayed()
        compose.onNodeWithTag(ApprovalCardTags.button(ApprovalChoice.ONCE)).assertDoesNotExist()
        // A settled card does not keep counting.
        compose.onNodeWithTag(ApprovalCardTags.COUNTDOWN).assertDoesNotExist()
    }

    @Test
    fun aGatewayWithoutTheCapabilityGetsAHintInsteadOfACard() {
        var dismissed = false
        compose.setContent {
            MaterialTheme { ApprovalUnsupportedHint(onDismiss = { dismissed = true }) }
        }

        compose.onNodeWithTag(ApprovalCardTags.UNSUPPORTED_HINT).assertIsDisplayed()
        compose.onNodeWithText("该网关不支持审批卡片，请用 /approve 文本命令回复。").assertIsDisplayed()
        compose.onNodeWithTag(ApprovalCardTags.UNSUPPORTED_HINT_DISMISS).performClick()

        kotlin.test.assertTrue(dismissed, "提示条必须可以被读过的用户收起")
    }

    private companion object {
        const val REQUESTED_AT = 1_780_000_000_000L
        const val TIMEOUT_SECONDS = 300L

        val DEFAULT_OPTIONS = listOf(
            ApprovalOption(ApprovalChoice.ONCE, style = ApprovalOptionStyle.PRIMARY),
            ApprovalOption(ApprovalChoice.ALWAYS, style = ApprovalOptionStyle.SECONDARY),
            ApprovalOption(ApprovalChoice.DENY, style = ApprovalOptionStyle.DANGER),
        )
    }
}
