package com.openandroidintelligence.gateway.remote.runtime

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 远程连接重试的退避策略。
 *
 * 策略本身不持有重试次数；调用方在连接成功后从 0 重新开始计算，确保退避状态只由重连循环掌控。
 */
data class RemoteReconnectPolicy(
    val initialDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 30_000L,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.2,
) {
    init {
        require(initialDelayMs > 0L) { "初始重连延迟必须大于 0" }
        require(maxDelayMs >= initialDelayMs) { "最大重连延迟不能小于初始延迟" }
        require(multiplier.isFinite() && multiplier >= 1.0) { "退避倍率必须是大于等于 1 的有限值" }
        require(jitterRatio in 0.0..1.0) { "抖动比例必须位于 0.0 到 1.0 之间" }
    }

    /**
     * 返回第 [attempt] 次重试前应等待的毫秒数，[attempt] 从 0 开始。
     *
     * [randomFraction] 必须返回 0.0 到 1.0 之间的对称随机样本；0.0 对应负向抖动边界，
     * 0.5 对应无抖动，1.0 对应正向抖动边界。默认使用 [Random.Default]。
     */
    fun delayMs(
        attempt: Int,
        randomFraction: () -> Double = { Random.nextDouble() },
    ): Long {
        require(attempt >= 0) { "重连次数不能为负数" }

        val fraction = randomFraction()
        require(fraction in 0.0..1.0) { "重连随机样本必须位于 0.0 到 1.0 之间" }

        val exponentialDelay = initialDelayMs.toDouble() * multiplier.pow(attempt)
        val cappedDelay = min(maxDelayMs.toDouble(), exponentialDelay)
        val jitterMultiplier = 1.0 + jitterRatio * (2.0 * fraction - 1.0)
        return (cappedDelay * jitterMultiplier)
            .roundToLong()
            .coerceIn(initialDelayMs, maxDelayMs)
    }
}

/**
 * 单个重连循环使用的即时唤醒原语。
 *
 * `wake` 通过 conflated Channel 合并重复通知：等待中的 [await] 只会被唤醒一次，等待结束后
 * 会清空残留通知，避免旧唤醒跳过下一轮退避。该实例应由一个消费循环独占使用。
 */
class RemoteReconnectSignal {
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)

    /** 发出一次唤醒；尚未被消费的重复唤醒会合并，关闭后调用无效果。 */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /**
     * 最多等待 [delayMs] 毫秒；收到唤醒或通道关闭时立即返回。
     *
     * 等待结束（包括超时、取消和关闭）时会排空残留唤醒，确保旧通知不会泄漏到下一次等待。
     */
    suspend fun await(delayMs: Long) {
        require(delayMs >= 0L) { "等待时长不能为负数" }
        if (delayMs == 0L) {
            clear()
            return
        }

        try {
            withTimeoutOrNull(delayMs) {
                wakeups.receiveCatching()
            }
        } finally {
            clear()
        }
    }

    /** 丢弃当前尚未消费的唤醒，但保持原语可继续使用。 */
    fun clear() {
        while (wakeups.tryReceive().isSuccess) {
            // conflated 通道正常最多只有一个值；循环用于覆盖与并发发送交错的情况。
        }
    }

    /** 永久关闭唤醒原语：唤醒等待者、丢弃残留信号，之后的 [wake] 调用会被忽略。 */
    fun close() {
        wakeups.close()
        clear()
    }
}
