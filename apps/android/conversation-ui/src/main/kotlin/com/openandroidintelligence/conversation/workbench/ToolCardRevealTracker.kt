package com.openandroidintelligence.conversation.workbench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.openandroidintelligence.conversation.model.ToolCallPairing
import kotlinx.coroutines.delay

/** 未挂载到视口的待播工具卡片入场资格保留时长，超过后过期以免用户后续滚回时闪烁。 */
private const val PendingToolRevealExpireMillis = 600L

/**
 * 会话级工具卡片入场动画追踪器。
 *
 * 解决的问题：工具卡片需要在本会话「实时首次出现」时播放一次从左到右的入场显现动画，
 * 但以下场景**严禁**播放或重播入场动画：
 * 1. 首次加载历史会话快照（含切换会话后首次 `Loadable.Ready`）；
 * 2. `LazyColumn` 滚动回收后重新进入视口；
 * 3. 工具状态从 `RUNNING -> COMPLETED/FAILED/UNKNOWN` 原位更新；
 * 4. 回合结束后收拢进过程折叠大卡片、用户点击大卡片展开重新挂载子卡片。
 *
 * 与 [TimelineAppendTracker] 保持同一同步差集原则：在展示项更新的当次组合内同步计算增量，
 * 确保首帧组合即可拿到准确的 `animateEntrance = true`，并采用一次性消费防止回收重播。
 */
@Stable
class ToolCardRevealTracker {
    private data class ObservedToolReveal(
        val key: String,
        val animateEligible: Boolean,
        val nativeStepId: String?,
        val isProvisionalGeneration: Boolean,
    )

    private var baselineEstablished = false
    private val knownKeys = hashSetOf<String>()
    private val pendingAnimateKeys = hashSetOf<String>()
    private val provisionalNativeStepIds = hashSetOf<String>()

    /** 每当产生新的待播 key 时递增，供未挂载项定时过期清理。 */
    internal var pendingBatchToken: Int = 0
        private set

    /**
     * 同步当前时间线展示项中的全部工具卡片身份。
     *
     * @param displayItems 当前帧的展示项列表。
     * @param ready 时间线是否已完成首次数据就绪（如 `Loadable.Ready`）。未就绪时不建立基线。
     */
    fun sync(
        displayItems: List<TimelineDisplayItem>,
        ready: Boolean = true,
    ) {
        if (!ready) return

        val observed = collectToolRevealKeys(displayItems)
        if (!baselineEstablished) {
            observed.forEach { item ->
                knownKeys.add(item.key)
                if (item.isProvisionalGeneration && !item.nativeStepId.isNullOrBlank()) {
                    provisionalNativeStepIds.add(item.nativeStepId)
                }
            }
            baselineEstablished = true
            return
        }

        var addedPending = false
        observed.forEach { item ->
            val isNew = knownKeys.add(item.key)
            val normalizedStepId = item.nativeStepId?.takeIf { it.isNotBlank() }
            if (item.isProvisionalGeneration && normalizedStepId != null) {
                provisionalNativeStepIds.add(normalizedStepId)
            }
            val upgradedFromProvisional = !item.isProvisionalGeneration &&
                normalizedStepId != null &&
                provisionalNativeStepIds.remove(normalizedStepId)

            if (isNew && item.animateEligible && !upgradedFromProvisional) {
                if (pendingAnimateKeys.add(item.key)) {
                    addedPending = true
                }
            } else if (!item.animateEligible) {
                // 历史快照或已折叠进已完成回合大卡片的步骤不再保留待播资格，展开大卡时不会误播。
                pendingAnimateKeys.remove(item.key)
            }
        }
        if (addedPending) {
            pendingBatchToken++
        }
    }

    /**
     * 一次性消费指定工具卡片的入场显现资格：
     * 仅对本会话基线建立后首次出现、且当前未被折叠的卡片返回 `true`，消费后立即移除。
     */
    fun consumeEntranceAnimation(revealKey: String): Boolean {
        if (revealKey.isBlank()) return false
        knownKeys.add(revealKey)
        return pendingAnimateKeys.remove(revealKey)
    }

    /** 清理超时仍未被视口消费的离屏待播 key，防止用户后续滚动到底部时补播旧动画。 */
    internal fun expireUnconsumed(snapshotKeys: Set<String>) {
        pendingAnimateKeys.removeAll(snapshotKeys)
    }

    internal fun snapshotPendingKeys(): Set<String> =
        if (pendingAnimateKeys.isEmpty()) emptySet() else pendingAnimateKeys.toSet()

    private fun collectToolRevealKeys(displayItems: List<TimelineDisplayItem>): List<ObservedToolReveal> {
        val result = mutableListOf<ObservedToolReveal>()
        displayItems.forEach { item ->
            if (item is TimelineDisplayItem.Execution) {
                val run = item.run
                val collapsedIntoTurnCard = shouldCollapseTurnProcessCard(run)
                val isProvisional = ToolCallPairing.isProvisionalGenerationId(run.generationId)
                run.steps.forEach { step ->
                    if (step is ExecutionStep.ToolCall) {
                        result += ObservedToolReveal(
                            key = toolCallRevealKey(step),
                            animateEligible = !collapsedIntoTurnCard && !step.isSnapshot,
                            nativeStepId = ToolCallPairing.normalizeCallId(step.nativeStepId),
                            isProvisionalGeneration = isProvisional,
                        )
                    }
                }
            }
        }
        return result
    }
}

/** 统一计算执行段内工具调用卡片的稳定入场追踪 key，与 [MarkdownToolCallView] 严格一致。 */
internal fun toolCallRevealKey(step: ExecutionStep.ToolCall): String = step.stepKey

/** 统一计算直渲有序块内工具调用卡片的稳定入场追踪 key。 */
internal fun orderedToolCallRevealKey(
    entryKey: String,
    item: OrderedTimelineBlock.ToolCall,
    blockIndex: Int,
): String = item.nativeStepId?.let { "$entryKey:$it" } ?: "$entryKey#$blockIndex"

/** 判断已完成回合是否会折叠进外层过程大卡片，供渲染层与追踪器共享单一真相。 */
internal fun shouldCollapseTurnProcessCard(run: AssistantExecutionRun): Boolean {
    if (run.isStreaming || !run.hasCompletedFinalAnswer) return false
    val toolCount = run.steps.count { it is ExecutionStep.ToolCall }
    if (toolCount >= 1) return true
    val thoughtCount = run.steps.count { it is ExecutionStep.Thought }
    if (thoughtCount == 0) return false
    val otherProcessCount = run.steps.count { it !is ExecutionStep.Thought && it !is ExecutionStep.ToolCall }
    // 仅有 1 张独立思考卡且无工具与其他过程步时保留单卡直接收起，避免折叠卡套折叠卡。
    return thoughtCount > 1 || otherProcessCount > 0
}

/** 当前时间线宿主注入的工具卡片入场追踪器；未注入时默认不自动播入场显现。 */
val LocalToolCardRevealTracker = staticCompositionLocalOf<ToolCardRevealTracker?> { null }

/**
 * 记住并同步当前会话的 [ToolCardRevealTracker]，切换 [threadId] 时重建基线。
 */
@Composable
fun rememberToolCardRevealTracker(
    threadId: String?,
    displayItems: List<TimelineDisplayItem>,
    ready: Boolean = true,
): ToolCardRevealTracker {
    val tracker = remember(threadId) { ToolCardRevealTracker() }
    tracker.sync(displayItems = displayItems, ready = ready)
    val batchToken = tracker.pendingBatchToken
    LaunchedEffect(tracker, batchToken) {
        val pendingSnapshot = tracker.snapshotPendingKeys()
        if (pendingSnapshot.isEmpty()) return@LaunchedEffect
        delay(PendingToolRevealExpireMillis)
        tracker.expireUnconsumed(pendingSnapshot)
    }
    return tracker
}
