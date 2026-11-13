package com.openandroidintelligence.conversation.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 振幅来源：返回 `MediaRecorder.maxAmplitude` 的原始值（0..32767）。 */
fun interface VoiceAmplitudeSource {
    fun maxAmplitude(): Int
}

/**
 * 录音音量表。
 *
 * 每 [pollIntervalMs]（默认约 50ms）读取一次 [VoiceAmplitudeSource.maxAmplitude]，
 * 把 0..32767 的原始振幅归一化为 0..1 的 [StateFlow]，供界面绘制音量条。
 *
 * 只读取已有录音器的振幅，不申请新权限，也不引入语音识别。
 */
class VoiceLevelMeter(
    private val source: VoiceAmplitudeSource,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    init {
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive" }
    }

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var job: Job? = null

    /** 在给定作用域内开始采样；重复调用不会重复启动。 */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        _level.value = readLevel()
        job = scope.launch(dispatcher) {
            while (isActive) {
                delay(pollIntervalMs)
                _level.value = readLevel()
            }
        }
    }

    /** 停止采样并把音量归零；录音结束后调用。 */
    fun stop() {
        job?.cancel()
        job = null
        _level.value = 0f
    }

    override fun close() = stop()

    private fun readLevel(): Float =
        runCatching { normalizeAmplitude(source.maxAmplitude()) }.getOrDefault(0f)

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 50L
        const val MAX_AMPLITUDE: Int = 32_767

        /** 把 `MediaRecorder.maxAmplitude` 的原始值裁剪并归一化到 0..1。 */
        fun normalizeAmplitude(amplitude: Int): Float =
            amplitude.coerceIn(0, MAX_AMPLITUDE).toFloat() / MAX_AMPLITUDE
    }
}
