package com.openandroidintelligence.conversation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/** 设置条目的背景和波纹共用同一动画边界，点击修饰符必须接在返回值之后。 */
@Composable
internal fun settingsItemMorphModifier(
    interactionSource: MutableInteractionSource,
    selected: Boolean,
    enabled: Boolean,
    scaleOnPress: Boolean,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalMotionPolicy.current.reduceMotion
    val progress by animateFloatAsState(
        targetValue = if (selected || (pressed && enabled)) 1f else 0f,
        animationSpec = MotionSpecs.morph(reduced),
        label = "settings-item-shape",
    )
    val scale by animateFloatAsState(
        targetValue = if (scaleOnPress && pressed && enabled && !reduced) 0.97f else 1f,
        animationSpec = MotionSpecs.press(reduced),
        label = "settings-item-press",
    )
    val fraction = progress.coerceIn(0f, 1f)
    val corners = MaterialTheme.shapes.medium
    val shape = RoundedCornerShape(
        topStart = SettingsMorphCorner(corners.topStart, fraction),
        topEnd = SettingsMorphCorner(corners.topEnd, fraction),
        bottomEnd = SettingsMorphCorner(corners.bottomEnd, fraction),
        bottomStart = SettingsMorphCorner(corners.bottomStart, fraction),
    )
    return Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .background(lerp(Color.Transparent, MaterialTheme.colorScheme.secondaryContainer, fraction))
}

/** 在绘制尺寸确定后解析主题圆角，胶囊端点始终是短边的一半。 */
private data class SettingsMorphCorner(val resting: CornerSize, val fraction: Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float {
        val start = resting.toPx(shapeSize, density)
        return start + (shapeSize.minDimension / 2f - start) * fraction
    }
}
