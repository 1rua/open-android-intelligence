package com.openandroidintelligence.conversation.workbench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.openandroidintelligence.conversation.model.ToolCallPairing
import kotlinx.coroutines.delay

/** 未挂载到视口的待播工具卡片入场资格保留时长，超过后过期以免用户后续滚回时闪烁。 */
internal const val PendingToolRevealExpireMillis = 600L

/**
 * 会话级工具卡片入场动画追踪器。
 *
 * 解决的问题：工具卡片需要在本会话「实时首次出现」时播放一次从左到右的入场显现动画，
 * 但以下场景**严禁**播放或重播入场动画：
 * 1. 首次加载历史会话快照（含切换会话后首次 `Loadable.Ready` / `Loadable.Empty`）；
 * 2. `LazyColumn` 滚动回收后重新进入视口；
 * 3. 工具状态从 `RUNNING -> COMPLETED/FAILED/UNKNOWN` 原位更新，或跨 `null`/临时→临时/权威
 *    `generationId` 身份升级（通过会话级归一化 `nativeStepId` 去重）；
 * 4. 回合结束后收拢进过程折叠大卡片、用户点击大卡片展开重新挂载子卡片；
 * 5. 离屏超过 [PendingToolRevealExpireMillis] 的待播卡片（按 key 独立过期时间戳，不被后续新卡延寿）。
 *
 * 与 [TimelineAppendTracker] 保持同一同步差集原则：在展示项更新的当次组合内同步计算增量，
 * 确保首帧组合即可拿到准确的 `animateEntrance = true`，并采用一次性消费防止回收重播。
 */
@Stable
class ToolCardRevealTracker(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class ObservedToolReveal(
        val key: String,
        val animateEligible: Boolean,
        val nativeStepId: String?,
    )

    private var baselineEstablished = false
    private val knownKeys = hashSetOf<String>()
    private val knownNativeStepIds = hashSetOf<String>()
    private val pendingExpireAtMillis = linkedMapOf<String, Long>()

    /** 仅在待播队列从空转为非空时递增，驱动单条过期清理协程而不取消在途计时。 */
    internal var pendingWakeToken: Int = 0
        private set

    /**
     * 同步当前时间线展示项中的全部工具卡片身份。
     *
     * @param displayItems 当前帧的展示项列表。
     * @param ready 时间线是否已完成首次数据就绪（如 `Loadable.Ready` / `Loadable.Empty`）。未就绪时不建立基线。
     */
    fun sync(
        displayItems: List<TimelineDisplayItem>,
        ready: Boolean = true,
    ) {
        if (!ready) return

        val now = clockMillis()
        val wasEmpty = pendingExpireAtMillis.isEmpty()
        pruneExpiredAt(now)

        val observed = collectToolRevealKeys(displayItems)
        if (!baselineEstablished) {
            observed.forEach { item ->
                knownKeys.add(item.key)
                if (item.nativeStepId != null) {
                    knownNativeStepIds.add(item.nativeStepId)
                }
            }
            baselineEstablished = true
            return
        }

        observed.forEach { item ->
            val isNewKey = knownKeys.add(item.key)
            val isNewNativeStep = item.nativeStepId == null || knownNativeStepIds.add(item.nativeStepId)
            val isFreshCard = isNewKey && isNewNativeStep

            if (isFreshCard && item.animateEligible) {
                pendingExpireAtMillis.putIfAbsent(item.key, now + PendingToolRevealExpireMillis)
            } else if (!item.animateEligible) {
                // 历史快照或已折叠进已完成回合大卡片的步骤不再保留待播资格，展开大卡时不会误播。
                pendingExpireAtMillis.remove(item.key)
            }
        }
        if (wasEmpty && pendingExpireAtMillis.isNotEmpty()) {
            pendingWakeToken++
        }
    }

    /**
     * 一次性消费指定工具卡片的入场显现资格：
     * 仅对本会话基线建立后首次出现、当前未被折叠且未过期的卡片返回 `true`，消费后立即移除。
     */
    fun consumeEntranceAnimation(revealKey: String): Boolean {
        if (revealKey.isBlank()) return false
        knownKeys.add(revealKey)
        val expireAt = pendingExpireAtMillis.remove(revealKey) ?: return false
        return expireAt > clockMillis()
    }

    /** 清理已到期的离屏待播 key，防止用户后续滚动到底部时补播旧动画。 */
    internal fun pruneExpired() {
        pruneExpiredAt(clockMillis())
    }

    /** 计算距离下一条待播 key 过期还需等待的毫秒数；队列为空时返回 `null`。 */
    internal fun nextExpirationDelayMillis(): Long? {
        val now = clockMillis()
        pruneExpiredAt(now)
        val earliest = pendingExpireAtMillis.values.minOrNull() ?: return null
        return (earliest - now).coerceAtLeast(1L)
    }

    internal fun snapshotPendingKeys(): Set<String> {
        pruneExpired()
        return if (pendingExpireAtMillis.isEmpty()) emptySet() else pendingExpireAtMillis.keys.toSet()
    }

    private fun pruneExpiredAt(now: Long) {
        if (pendingExpireAtMillis.isEmpty()) return
        val iterator = pendingExpireAtMillis.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= now) {
                iterator.remove()
            }
        }
    }

    private fun collectToolRevealKeys(displayItems: List<TimelineDisplayItem>): List<ObservedToolReveal> {
        val result = mutableListOf<ObservedToolReveal>()
        displayItems.forEach { item ->
            if (item is TimelineDisplayItem.Execution) {
                val run = item.run
                val collapsedIntoTurnCard = shouldCollapseTurnProcessCard(run)
                run.steps.forEach { step ->
                    if (step is ExecutionStep.ToolCall) {
                        result += ObservedToolReveal(
                            key = toolCallRevealKey(step),
                            animateEligible = !collapsedIntoTurnCard && !step.isSnapshot,
                            nativeStepId = ToolCallPairing.normalizeCallId(step.nativeStepId),
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

/**
 * 判断已完成回合是否会折叠进外层过程大卡片的唯一权威判定：
 * - 流式运行中或尚未输出最终回答时不折叠；
 * - 含 1 次及以上工具调用时统一折叠进「N 次工具调用」大卡片；
 * - 0 次工具调用时绝不显示「0 次工具调用」：仅 1 张独立思考卡且无其他过程步时保留单卡直接收起，
 *   多步思考或伴随中间叙述/附件时折叠进「思考过程」大卡片。
 */
internal fun shouldCollapseTurnProcessCard(
    isStreaming: Boolean,
    hasCompletedFinalAnswer: Boolean,
    toolCallCount: Int,
    thoughtCount: Int,
    otherProcessCount: Int,
): Boolean {
    if (isStreaming || !hasCompletedFinalAnswer) return false
    if (toolCallCount >= 1) return true
    if (thoughtCount == 0) return false
    return thoughtCount > 1 || otherProcessCount > 0
}

/** 判断已完成执行段是否折叠进外层过程大卡片，供渲染层与入场追踪器共享单一真相。 */
internal fun shouldCollapseTurnProcessCard(run: AssistantExecutionRun): Boolean {
    val toolCount = run.steps.count { it is ExecutionStep.ToolCall }
    val thoughtCount = run.steps.count { it is ExecutionStep.Thought }
    val otherProcessCount = run.steps.count { it !is ExecutionStep.Thought && it !is ExecutionStep.ToolCall }
    return shouldCollapseTurnProcessCard(
        isStreaming = run.isStreaming,
        hasCompletedFinalAnswer = run.hasCompletedFinalAnswer,
        toolCallCount = toolCount,
        thoughtCount = thoughtCount,
        otherProcessCount = otherProcessCount,
    )
}

/** 当前时间线宿主注入的工具卡片入场追踪器；未注入时默认不自动播入场显现。 */
val LocalToolCardRevealTracker = staticCompositionLocalOf<ToolCardRevealTracker?> { null }

/**
 * 记住并同步当前会话的 [ToolCardRevealTracker]，切换 [threadId] 时重建基线。
 *
 * 仅在 [displayItems] 或 [ready] 实际变化时执行同步遍历，并按单 key 独立到期时间戳清理离屏待播资格。
 */
@Composable
fun rememberToolCardRevealTracker(
    threadId: String?,
    displayItems: List<TimelineDisplayItem>,
    ready: Boolean = true,
): ToolCardRevealTracker {
    val tracker = remember(threadId) { ToolCardRevealTracker() }
    remember(tracker, displayItems, ready) {
        tracker.sync(displayItems = displayItems, ready = ready)
    }
    val wakeToken = tracker.pendingWakeToken
    LaunchedEffect(tracker, wakeToken) {
        if (wakeToken == 0) return@LaunchedEffect
        while (true) {
            val waitMillis = tracker.nextExpirationDelayMillis() ?: break
            delay(waitMillis)
            tracker.pruneExpired()
        }
    }
    return tracker
}
