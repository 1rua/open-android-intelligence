package com.openandroidintelligence.assistant

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 录音波形的固定参数。
 *
 * 条数就是滚动窗口长度：24 帧约等于 1.2 秒的采样（电平表 50ms 一次），
 * 既能看出说话节奏，又不会让一根条代表太久以前的声音。
 */
internal object AssistantVoiceWaveformDefaults {
    /** 滚动窗口帧数：只保留最近 24 个真实音量样本。 */
    const val BarCount: Int = 24

    /** 没有音量时仍保留的最小柱高比例，避免整行完全消失、看不出「正在录音」。 */
    const val MinFraction: Float = 0.12f

    /** 波形高度：与输入栏的图标行同高的紧凑条带。 */
    val Height: Dp = 24.dp
}

/**
 * 录音波形样本窗口（纯逻辑，可单测）。
 *
 * 只保存真实推进进来的音量样本，右侧为最新；不足 [barCount] 帧时左侧按 0 补齐，
 * 因此柱数永远是 [barCount]，波形不会随着样本增多而改变宽度。
 * 没有样本时不生成随机数或正弦波——那会让界面看起来"在动"，但实际上什么都没录到。
 */
internal class AssistantVoiceWaveformWindow(
    val barCount: Int = AssistantVoiceWaveformDefaults.BarCount,
) {
    init {
        require(barCount > 0) { "barCount must be positive" }
    }

    private val samples = ArrayDeque<Float>(barCount)

    /** 当前窗口里的真实样本数（不足 [barCount] 时小于它）。 */
    val size: Int get() = samples.size

    /** 推入一帧音量（0..1）；超出窗口的最旧样本被丢弃。 */
    fun push(level: Float) {
        samples.addLast(level.coerceIn(0f, 1f))
        while (samples.size > barCount) samples.removeFirst()
    }

    /**
     * 按「旧 → 新」返回固定长度的柱高列表，右侧为最新样本。
     *
     * 还没有填满窗口时左侧补 0：柱子的位置从现在开始向右生长，而不是一开始就散落在整行。
     */
    fun bars(): List<Float> {
        val offset = barCount - samples.size
        return List(barCount) { index ->
            if (index < offset) 0f else samples[index - offset]
        }
    }

    fun clear() = samples.clear()
}

/**
 * 助理录音波形（MD3 Expressive 风格）。
 *
 * 画法：
 * - 每根柱子居中、圆角端头；高度 = 最小比例 + 真实音量，颜色用 `primary`；
 * - 没有样本的位置画 `outlineVariant` 的静态基线，柱数恒定，波形宽度稳定；
 * - 新样本从右侧进入，窗口只保留最近 [AssistantVoiceWaveformDefaults.BarCount] 帧。
 *
 * 动效：历史柱子的高度就是录下时的真实音量；最右一列代表「此刻」，用 [MotionSpecs] 的
 * 轻快吸附平滑落位，避免电平抖动让最新一列闪跳。开启「减少动态」时平滑退化为 `snap`，
 * 每一列都直接落在真实值上，整行就是静态柱。
 *
 * 何时推进窗口：默认由音量变化驱动；宿主传入 [sampleKey]（例如录音已录时长）时改由它驱动，
 * 这样即使声音大小保持不变，窗口也按真实采样节奏前进，而不是「安静时不滚动」。
 *
 * 无障碍：波形本身不承载可读信息，只提供一句「录音波形」的描述；
 * 已录时长由旁边的计时文本朗读，避免重复播报。
 *
 * @param level 当前音量（0..1）；超出范围会被裁剪。
 * @param sampleKey 采样序号/时间戳；变化即代表采集到一个新样本。为 null 时按 [level] 变化推进。
 */
@Composable
internal fun AssistantVoiceWaveform(
    level: Float,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = LocalMotionPolicy.current.reduceMotion,
    barCount: Int = AssistantVoiceWaveformDefaults.BarCount,
    sampleKey: Any? = null,
) {
    val clamped = level.coerceIn(0f, 1f)
    val window = remember(barCount) { AssistantVoiceWaveformWindow(barCount) }
    var bars by remember(barCount) { mutableStateOf(window.bars()) }

    // 窗口按电平表的采样节奏推进（默认 50ms 一个样本），因此 24 帧约等于最近 1.2 秒的
    // 真实音量历史；不能按动画帧推进，否则同一段时间会被拆成 60fps 的碎片、窗口迅速缩短。
    LaunchedEffect(sampleKey ?: clamped) {
        window.push(clamped)
        bars = window.bars()
    }
    // 只平滑「最新一根柱子」的落位，历史柱子保持已经录下的真实高度。
    // 减少动态时这里是 snap：柱子直接落在真实值上，整行就是静态柱。
    val newest by animateFloatAsState(
        targetValue = clamped,
        animationSpec = MotionSpecs.snappy(reduceMotion),
        label = "assistant-voice-waveform-level",
    )

    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .height(AssistantVoiceWaveformDefaults.Height)
            .semantics { contentDescription = "录音波形" },
    ) {
        val slot = size.width / barCount
        val barWidth = slot * 0.5f
        val centerY = size.height / 2f
        val minimum = AssistantVoiceWaveformDefaults.MinFraction
        bars.forEachIndexed { index, sample ->
            // 最右一列代表"此刻"，用平滑后的音量；其余是已经录下的历史样本。
            val value = if (index == bars.lastIndex) newest else sample
            val fraction = minimum + value * (1f - minimum)
            val half = size.height / 2f * fraction
            drawLine(
                color = if (value > 0f) activeColor else idleColor,
                start = Offset(slot * index + slot / 2f, centerY - half),
                end = Offset(slot * index + slot / 2f, centerY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
