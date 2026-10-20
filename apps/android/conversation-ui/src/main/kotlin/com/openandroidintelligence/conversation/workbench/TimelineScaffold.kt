package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.delay

/**
 * 时间线的新增项上浮距离（设计规格 §6 M04：淡入 + 8dp 上浮）。
 *
 * 只用于「新消息进入」，历史的滚动进入不重放：是否播放由
 * [rememberFreshTimelineKeys] 判断，普通滚动不会进入这个分支。
 */
private val TimelineItemRise = 8.dp

/**
 * 新增项入口淡入的保持时长（毫秒）。
 *
 * 必须长于淡入动画本身（[MotionSpecs.Exit] = 200ms）：列表项一旦被判定为「不是新增」，
 * `animateItem` 的淡入参数会变回 null；留出余量才能让这一次动画完整播完。
 */
private const val FreshKeyHoldMillis = 600L

/**
 * 找出「刚刚追加」的消息 key。
 *
 * 为什么需要它：`LazyColumn` 的 `animateItem` 在条目第一次组合时就会播放淡入，
 * 而历史消息是滚动进视口的 —— 如果对所有条目都传淡入参数，用户每往上翻一屏，
 * 历史消息就会再「冒出来」一次。这里用整段 key 列表做差集，只把真正追加的条目
 * 标记为新增，其余条目关闭淡入。
 *
 * 关键点：差值必须在「列表状态变化的那一次组合」里同步算出来。如果放到
 * `LaunchedEffect` 里延后一帧更新，新条目第一次组合时还拿不到「我是新增」，
 * `animateItem` 的淡入与 [timelineItemRise] 的位移就都不会播放。
 *
 * 1. `keys == null` 表示时间线还没就绪（加载中/失败），此时不建立基线；
 *    第一次进入「就绪」时一次性种下全部历史 key，历史消息因此不会重放。
 * 2. 入场动画播完后主动失效一次（[FreshKeyHoldMillis]），此后用户再滚回来时
 *    这些消息就是历史，不会二次播放。
 * 3. 切换会话时所有状态随 `threadId` 重置。
 */
@Composable
internal fun rememberFreshTimelineKeys(threadId: String?, keys: List<String>?): Set<String> {
    val tracker = remember(threadId) { TimelineAppendTracker() }
    var generation by remember(threadId) { mutableIntStateOf(0) }
    val fresh = remember(threadId, keys, generation) {
        keys?.let(tracker::consumeAppendedKeys).orEmpty()
    }
    LaunchedEffect(threadId, keys) {
        if (fresh.isEmpty()) return@LaunchedEffect
        delay(FreshKeyHoldMillis)
        // 只推进一次失效版本：下一次组合里同一个 key 不再算「新增」。
        generation++
    }
    return fresh
}

/**
 * 记录上一个已就绪的 key 集合，并返回本轮的增量。
 *
 * 只是组合期使用的纯缓存，不持有 Compose 状态：同一个 key 集合只消费一次，
 * 避免「把历史消息当新消息」。
 */
private class TimelineAppendTracker {
    private var known: Set<String>? = null

    fun consumeAppendedKeys(keys: List<String>): Set<String> {
        val current = keys.toSet()
        val previous = known
        known = current
        if (previous == null) return emptySet()
        return current - previous
    }
}

/**
 * 新消息进入的 8dp 上浮。
 *
 * 位移只在新增条目第一次组合时决定是否播放，之后 `fresh` 因过期变回 false 也不会
 * 打断进行中的动画；减少动态时直接落位（规格 M04 降级分支）。
 * 读取动画值放在 `graphicsLayer` 的绘制 lambda 里，逐帧只失效图层、不触发重组。
 */
@Composable
internal fun Modifier.timelineItemRise(fresh: Boolean, reduced: Boolean): Modifier {
    val shouldRise = remember { fresh && !reduced }
    val rise = remember { Animatable(if (shouldRise) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (shouldRise) {
            rise.animateTo(0f, tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedDecelerateEasing))
        }
    }
    val distancePx = with(LocalDensity.current) { TimelineItemRise.toPx() }
    return graphicsLayer { translationY = rise.value * distancePx }
}

/**
 * 「回到最新」：用户上滑离开尾部后出现（规格 §6 M13）。
 *
 * 150ms 淡入 + 缩放 0.9→1；减少动态时只做短暂淡化，不做缩放。
 */
@Composable
internal fun ScrollToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalMotionPolicy.current.reduceMotion
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MotionSpecs.fade(reduced)) + scaleIn(
            animationSpec = tween(MotionSpecs.Fast, easing = MotionSpecs.EmphasizedDecelerateEasing),
            initialScale = 0.9f,
        ),
        exit = fadeOut(MotionSpecs.fade(reduced)) + scaleOut(
            animationSpec = tween(MotionSpecs.Fast, easing = MotionSpecs.EmphasizedAccelerateEasing),
            targetScale = 0.9f,
        ),
        modifier = modifier,
    ) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.padding(Dimensions.SpaceSmall),
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Spacer(Modifier.width(Dimensions.SpaceSmall))
            Text("回到最新")
        }
    }
}
