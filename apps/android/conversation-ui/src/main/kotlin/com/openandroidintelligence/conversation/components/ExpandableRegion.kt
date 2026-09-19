package com.openandroidintelligence.conversation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 全 App 唯一的展开/折叠容器。
 *
 * 统一采用 `expandVertically + fadeIn`（折叠为反向），并走 M3 Emphasized 曲线，
 * 让「内容从折叠处生长出来」而不是瞬间出现；系统动画关闭时 [MotionSpecs.emphasized]
 * 自动退化为短淡化。
 */
@Composable
fun ExpandableRegion(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    reduced: Boolean = LocalMotionPolicy.current.reduceMotion,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sizeSpec = MotionSpecs.emphasized<IntSize>(reduced)
    val alphaSpec = MotionSpecs.emphasized<Float>(reduced)
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(animationSpec = sizeSpec, expandFrom = Alignment.Top) + fadeIn(animationSpec = alphaSpec),
        exit = shrinkVertically(animationSpec = sizeSpec, shrinkTowards = Alignment.Top) + fadeOut(animationSpec = alphaSpec),
    ) {
        Column(content = content)
    }
}
