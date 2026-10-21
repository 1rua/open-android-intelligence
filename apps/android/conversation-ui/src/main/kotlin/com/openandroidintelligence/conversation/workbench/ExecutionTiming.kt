package com.openandroidintelligence.conversation.workbench

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 执行过程耗时记录器。
 *
 * 语义（规格 §3.2 / §8.5）：
 * - **只记录真实观察到的运行区间**：卡片在组合期间调用 [observe] 开始计时，
 *   状态变为结束后结算一次；没有观察到开始的步骤 [durationOf] 返回 null，
 *   标题因此省略耗时，绝不编造。
 * - **会话级生命周期**：由宿主在会话级 `remember`，通过 [LocalExecutionTiming] 下发，
 *   保证实时卡片收进聚合面板时耗时状态不丢。
 * - **不持久化**：换成正式 id 或重建会话后耗时归零，这是既有 key 语义。
 */
class ExecutionTimingStore(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /** 单步观察状态：开始时刻 + 已结算的累计时长。 */
    private class Observation(
        var startedAt: Long? = null,
        var totalMillis: Long = 0L,
        var finished: Boolean = false,
    )

    private val observations = mutableMapOf<String, Observation>()

    /**
     * 已完成观察的时长。放在可观察状态里：卡片正文读取它，结算后标题自动刷新，
     * 不需要宿主再推一次数据。
     */
    private val settledDurations = mutableStateMapOf<String, Long>()

    /**
     * 记录一次观察。
     *
     * 幂等：重复 `observe(key, true)` 不会重置开始时刻；重复 `observe(key, false)`
     * 在已经结算后不再累加。若同一步骤重新开始运行（例如重试），会清空旧时长重新计时。
     */
    fun observe(stepKey: String, running: Boolean) {
        if (stepKey.isBlank()) return
        val observation = observations.getOrPut(stepKey) { Observation() }
        if (running) {
            if (observation.finished) {
                observation.finished = false
                observation.totalMillis = 0L
                settledDurations.remove(stepKey)
            }
            if (observation.startedAt == null) {
                observation.startedAt = clock()
            }
            return
        }

        val startedAt = observation.startedAt ?: return
        observation.startedAt = null
        observation.totalMillis += (clock() - startedAt).coerceAtLeast(0L)
        observation.finished = true
        settledDurations[stepKey] = observation.totalMillis
    }

    /**
     * 卡片离开组合时结算当前区间，但保留「未真正完成」的语义：
     * 再次进入且仍在运行时从新的观察点继续累计，而不是把已观察的时长清零。
     */
    fun settleOnDispose(stepKey: String) {
        if (stepKey.isBlank()) return
        val observation = observations[stepKey] ?: return
        val startedAt = observation.startedAt ?: return
        observation.startedAt = null
        observation.totalMillis += (clock() - startedAt).coerceAtLeast(0L)
        settledDurations[stepKey] = observation.totalMillis
    }

    /** 已结算的耗时（毫秒）；从未观察到开始或尚未结束时返回 null。 */
    fun durationOf(stepKey: String): Long? = settledDurations[stepKey]

    /** 清空全部观察记录（宿主销毁/会话切换时调用，释放已结束步骤的内存）。 */
    fun clear() {
        observations.clear()
        settledDurations.clear()
    }
}

/**
 * 当前会话的执行耗时记录器。
 *
 * 默认 null：没有宿主提供时卡片不显示耗时，而不是用一个隐身的全局实例伪造观察。
 */
val LocalExecutionTiming = staticCompositionLocalOf<ExecutionTimingStore?> { null }

/**
 * 会话级耗时记录器：id 变化时重建，同一会话内的实时卡片与聚合面板共享观察结果。
 */
@Composable
fun rememberExecutionTimingStore(threadId: String?): ExecutionTimingStore {
    val store = remember(threadId) { ExecutionTimingStore() }
    // 会话切换/宿主销毁后旧 store 不再被任何卡片引用；主动清空而不是等 GC。
    DisposableEffect(store) {
        onDispose { store.clear() }
    }
    return store
}

/**
 * 把耗时格式化成标题里的一小段文字。
 *
 * - 不足 1 秒：「不到 1 秒」（真实观察到的极短区间，不四舍五入成 0）；
 * - 1 秒到 1 分钟：保留 0.1 秒精度，整数秒不带小数点；
 * - 超过 1 分钟：「X 分 Y 秒」。
 */
fun formatExecutionDuration(durationMillis: Long): String {
    val safe = durationMillis.coerceAtLeast(0L)
    val tenths = (safe + 50L) / 100L
    return when {
        safe < 1_000L -> "不到 1 秒"
        tenths < 600L -> {
            val seconds = tenths / 10L
            val remainder = tenths % 10L
            if (remainder == 0L) "$seconds 秒" else "$seconds.$remainder 秒"
        }

        else -> {
            val totalSeconds = (safe + 500L) / 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            "$minutes 分 $seconds 秒"
        }
    }
}
