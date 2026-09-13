package com.openandroidintelligence.ui.design

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class MotionPolicy(val reduceMotion: Boolean = false, val durationScale: Float = 1f)

val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy() }

/** 不写系统设置；观察真实动画缩放，Compose 自身负责时间缩放，避免重复计算。 */
@Composable
fun rememberSystemMotionPolicy(userReduced: Boolean): MotionPolicy {
    val resolver = LocalContext.current.contentResolver
    fun readScale() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    var scale by remember(resolver) { mutableFloatStateOf(readScale()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = readScale() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return MotionPolicy(userReduced || scale == 0f, scale)
}

interface MotionPreferenceSource { val policy: StateFlow<MotionPolicy> }
class DefaultMotionPreferenceSource : MotionPreferenceSource {
    private val current = MutableStateFlow(MotionPolicy())
    override val policy: StateFlow<MotionPolicy> = current.asStateFlow()
    fun updateReduceMotion(enabled: Boolean) { current.value = current.value.copy(reduceMotion = enabled) }
}
