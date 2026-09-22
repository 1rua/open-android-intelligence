package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalOptionStyle
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.ApprovalSeverity
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.state.ApprovalCardState
import com.openandroidintelligence.conversation.state.ApprovalCountdown
import com.openandroidintelligence.conversation.state.countdownAt
import com.openandroidintelligence.conversation.state.isSettled
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.delay

/** Test hooks: a card is asserted through these tags, never by its text alone. */
object ApprovalCardTags {
    const val ROOT = "approval_card"
    const val COUNTDOWN = "approval_countdown"
    const val OUTCOME = "approval_outcome"
    const val UNSUPPORTED_HINT = "approval_unsupported_hint"
    const val UNSUPPORTED_HINT_DISMISS = "approval_unsupported_hint_dismiss"
    fun button(choice: ApprovalChoice): String = "approval_button_${choice.name}"
}

/** How often the countdown redraws. One second is the unit the badge shows. */
const val APPROVAL_COUNTDOWN_TICK_MILLIS = 1_000L

/** Buttons per row before the group wraps: four tiers do not fit one row. */
private const val MAX_BUTTONS_PER_ROW = 2

/**
 * One command-execution approval, as a card in the conversation (contract §7.2).
 *
 * The card draws exactly the tiers the Gateway offered and nothing else: the
 * option list is the Gateway's decision, so a "smart deny" shows two buttons and
 * a full prompt shows four. A press locks the whole group at once and shows the
 * spinner on the tier that was pressed — the "已受理" feedback — and the card
 * never reads "已允许" until the Gateway itself settled it.
 *
 * A state change replaces one part of the card, never the card: the button group
 * is the same group while the press travels, so the tier that was pressed turns
 * into the spinner in place, and only the settled conclusion fades in.
 *
 * The countdown lives inside this composable: it ticks on its own so the rest of
 * the timeline is not recomposed every second, and it is derived from the
 * Gateway's `requestedAt`/`timeoutSeconds` rather than from when the row was
 * first drawn, so leaving the thread and coming back resumes the same number.
 * Inside the card the tick is isolated once more: the badge is the only reader of
 * the clock, and the body watches the single fact that the window has closed.
 */
@Composable
fun ApprovalCard(
    state: ApprovalCardState,
    onDecide: (ApprovalChoice) -> Unit,
    modifier: Modifier = Modifier,
    /** Wall clock, injectable so a test can advance the countdown. */
    clock: () -> Long = { System.currentTimeMillis() },
) {
    val request = state.request
    val settled = state.isSettled
    val waiting = state is ApprovalCardState.Waiting
    val submitting = (state as? ApprovalCardState.Submitting)?.choice
    val settledOutcome = (state as? ApprovalCardState.Resolved)?.outcome
    // The ticker is the only writer of this clock, and nothing in this scope reads
    // it: the badge reads it through the lambda handed to the header, inside the
    // badge's own scope, so a second passing redraws that badge and not the card.
    val now = remember { mutableLongStateOf(clock()) }
    // "The window is closed" reaches the body as a derived boolean rather than as
    // the clock itself: the body then recomposes once, when the window closes,
    // instead of once per tick.
    val windowClosed = remember(request) { derivedStateOf { request.countdownAt(now.longValue).expired } }
    LaunchedEffect(state) {
        if (state.isSettled) return@LaunchedEffect
        while (true) {
            val tick = clock()
            now.longValue = tick
            // Nothing is left to count once the window closed: the card keeps
            // showing that fact instead of ticking at a number that cannot move.
            if (request.countdownAt(tick).expired) return@LaunchedEffect
            delay(APPROVAL_COUNTDOWN_TICK_MILLIS)
        }
    }
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    // A card that already carried its answer when it was first drawn (re-entering
    // a thread past the fact) is shown as it is; only a card watched until the
    // Gateway answered earns the conclusion's fade.
    val settledWhenFirstDrawn = remember(request) { state.isSettled }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ApprovalCardTags.ROOT),
        shape = RoundedCornerShape(AppRadius.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.SpaceMedium),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
        ) {
            CardHeader(
                request = request,
                settled = settled,
                countdown = { request.countdownAt(now.longValue) },
            )
            CommandPreview(request.command)
            if (request.reason.isNotBlank()) {
                Text(
                    text = request.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!settled) {
                // Read once, ahead of the `waiting &&` shortcut below: a press in
                // flight has to notice the window closing too, or the card would
                // keep a spinner over a command that can no longer run.
                val windowIsClosed = windowClosed.value
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    // Silence is a safe no by contract: the Gateway promises an
                    // unanswered command never runs, so this line is a fact the
                    // Gateway published, not a local guess. A press already with the
                    // Gateway is no different — it may take as long as the link does
                    // — and saying the window is over keeps a spinner from reading as
                    // "still waiting" when it is not.
                    if (windowIsClosed) {
                        OutcomeRow(ApprovalOutcome.TIMED_OUT, awaitingGateway = true)
                    }
                    // One group serves the answering card and the in-flight one, and
                    // it sits outside every animation scope: the tier that was pressed
                    // keeps its slot and its identity, so it becomes the spinner in
                    // place while the rest of the group goes inert around it, instead
                    // of the whole row being swapped for another one.
                    OptionGroup(
                        options = request.options,
                        submitting = submitting,
                        enabled = waiting && !windowIsClosed,
                        // Already with the Gateway: the group goes inert around the
                        // spinner rather than listening for a second press.
                        onDecide = if (waiting) onDecide else { _: ApprovalChoice -> },
                    )
                }
            }
            if (settledOutcome != null) {
                // The conclusion is the only part that fades in, and it does so at
                // the moment the Gateway settles the card.
                AnimatedVisibility(
                    visibleState = remember { MutableTransitionState(settledWhenFirstDrawn).apply { targetState = true } },
                    enter = fadeIn(MotionSpecs.fade(reduceMotion)),
                    exit = fadeOut(MotionSpecs.fade(reduceMotion)),
                ) {
                    OutcomeRow(settledOutcome, awaitingGateway = false)
                }
            }
        }
    }
}

@Composable
private fun CardHeader(
    request: ApprovalRequest,
    settled: Boolean,
    /**
     * Deferred read: the countdown is evaluated inside [CountdownBadge], so the tick
     * redraws the badge rather than this header, the card, or the timeline.
     */
    countdown: () -> ApprovalCountdown,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(Dimensions.SmallIcon),
        )
        Spacer(Modifier.width(Dimensions.SpaceSmall))
        Text(
            text = "命令需要你的批准",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(Dimensions.SpaceSmall))
        if (!settled) {
            CountdownBadge(countdown)
        }
    }
    if (request.severity == ApprovalSeverity.CRITICAL) {
        Text(
            text = "高危操作",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun CountdownBadge(countdown: () -> ApprovalCountdown) {
    // The only read the clock gets every second, taken in this composable's own
    // scope: a tick rebuilds this badge and nothing above it.
    val current = countdown()
    Surface(
        shape = RoundedCornerShape(AppRadius.Small),
        color = if (current.expired) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (current.expired) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = Modifier.testTag(ApprovalCardTags.COUNTDOWN),
    ) {
        Text(
            // Monospace keeps the badge from twitching as the number shrinks.
            text = if (current.expired) "已超时" else "⏱️ ${current.remainingSeconds}s",
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall, vertical = Dimensions.SpaceTiny),
            maxLines = 1,
        )
    }
}

@Composable
private fun CommandPreview(command: String) {
    if (command.isBlank()) return
    Surface(
        shape = RoundedCornerShape(AppRadius.Small),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = command,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(Dimensions.SpaceSmall),
            maxLines = 6,
        )
    }
}

/**
 * The tiers the Gateway offered, one button each.
 *
 * A press locks the whole group: the chosen button becomes a spinner and every
 * other one goes inert, so a card can never produce two decisions.
 */
@Composable
private fun OptionGroup(
    options: List<ApprovalOption>,
    submitting: ApprovalChoice?,
    enabled: Boolean,
    onDecide: (ApprovalChoice) -> Unit,
) {
    if (options.isEmpty()) return
    // `chunked` already leaves a short tail as its own row, so the cap alone is the
    // whole rule: a group smaller than a row is simply one row of that size.
    val rows = options.chunked(MAX_BUTTONS_PER_ROW)
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                row.forEach { option ->
                    ApprovalButton(
                        option = option,
                        submitting = submitting == option.choice,
                        enabled = enabled,
                        onClick = { onDecide(option.choice) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalButton(
    option: ApprovalOption,
    submitting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = option.label?.takeIf { it.isNotBlank() } ?: choiceLabel(option.choice)
    val content: @Composable () -> Unit = {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimensions.SmallIcon),
                strokeWidth = Dimensions.StrokeStitch,
            )
        } else {
            Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val buttonModifier = modifier
        .heightIn(min = Dimensions.MinimumTouchTarget)
        .testTag(ApprovalCardTags.button(option.choice))
    when (option.style) {
        ApprovalOptionStyle.PRIMARY -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            content = { content() },
        )

        ApprovalOptionStyle.SECONDARY -> FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            content = { content() },
        )

        ApprovalOptionStyle.DANGER -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            content = { content() },
        )

        ApprovalOptionStyle.NEUTRAL -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            content = { content() },
        )
    }
}

/** How one approval ended. A settled card keeps this line and loses its buttons. */
@Composable
private fun OutcomeRow(outcome: ApprovalOutcome, awaitingGateway: Boolean) {
    val (icon, tint, text) = when (outcome) {
        ApprovalOutcome.ALLOWED_ONCE -> Triple(Icons.Default.Check, MaterialTheme.colorScheme.primary, "已允许这一次执行")
        ApprovalOutcome.ALLOWED_SESSION -> Triple(Icons.Default.Check, MaterialTheme.colorScheme.primary, "已在本次会话内允许")
        ApprovalOutcome.ALLOWED_ALWAYS -> Triple(Icons.Default.Check, MaterialTheme.colorScheme.primary, "已始终允许这类命令")
        ApprovalOutcome.DENIED -> Triple(Icons.Default.Close, MaterialTheme.colorScheme.error, "已拒绝，命令不会执行")
        ApprovalOutcome.TIMED_OUT -> Triple(
            Icons.Default.Schedule,
            MaterialTheme.colorScheme.onSurfaceVariant,
            // Contract §7.2: silence is a safe no, so this is the Gateway's own
            // promise rather than the phone concluding anything.
            if (awaitingGateway) "审批已超时，命令不会再执行" else "审批已超时，命令没有执行",
        )
        ApprovalOutcome.WITHDRAWN -> Triple(Icons.Default.Close, MaterialTheme.colorScheme.onSurfaceVariant, "审批已撤回，命令没有执行")
        ApprovalOutcome.UNKNOWN -> Triple(Icons.Default.Schedule, MaterialTheme.colorScheme.onSurfaceVariant, "审批结果未知，请以 Gateway 记录为准")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.Small))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Dimensions.SpaceSmall, vertical = Dimensions.SpaceTiny)
            .testTag(ApprovalCardTags.OUTCOME),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(Dimensions.SmallIcon))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one line a Gateway without approval cards gets.
 *
 * No card is drawn in that case: a press that cannot be delivered would leave
 * the command blocked while the screen claims it was answered. The text command
 * stays the honest way to reply.
 */
@Composable
fun ApprovalUnsupportedHint(onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceTiny)
            .testTag(ApprovalCardTags.UNSUPPORTED_HINT),
        shape = RoundedCornerShape(AppRadius.Small),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = Dimensions.SpaceSmall, top = Dimensions.SpaceTiny, bottom = Dimensions.SpaceTiny),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Text(
                text = "该网关不支持审批卡片，请用 /approve 文本命令回复。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                // A permanent banner about a Gateway that simply has no such
                // feature would be noise; the user dismisses it once and the
                // convention is theirs from then on.
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(ApprovalCardTags.UNSUPPORTED_HINT_DISMISS)) {
                    Text("知道了", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** The phone's own wording for a tier the Gateway did not label. */
fun choiceLabel(choice: ApprovalChoice): String = when (choice) {
    ApprovalChoice.ONCE -> "允许一次"
    ApprovalChoice.SESSION -> "本次会话允许"
    ApprovalChoice.ALWAYS -> "始终允许"
    ApprovalChoice.DENY -> "拒绝"
    ApprovalChoice.UNKNOWN -> "未知"
}
