package com.openandroidintelligence.conversation.voice

import java.util.Locale

/**
 * 语音时长文案：`分:秒`（例如 `0:07`、`1:05`）。
 *
 * 只用于展示真实观察到的时长：录音中的计时与时间线里语音附件的时长芯片共用这一个格式，
 * 两处不会出现「0:07」与「0:7」这样的差异。
 */
fun formatVoiceDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
