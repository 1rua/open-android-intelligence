package com.openandroidintelligence.screen

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class ScreenSettled(val settled: Boolean, val reason: String)

/** 从系统确认动作完成后计时；真实布局事件重置静默窗，硬期限始终不延长。 */
class ScreenQuietWait(private val events: StateFlow<Long>, private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }) {
    suspend fun await(): ScreenSettled {
        val deadline = elapsed() + 3000
        while (true) {
            val remaining = deadline - elapsed()
            if (remaining <= 0) return ScreenSettled(false, "timeout")
            val revision = events.value
            val changed = withTimeoutOrNull(minOf(400, remaining)) { events.first { it != revision } }
            if (changed == null) return if (remaining >= 400) ScreenSettled(true, "quiet") else ScreenSettled(false, "timeout")
        }
    }
}
