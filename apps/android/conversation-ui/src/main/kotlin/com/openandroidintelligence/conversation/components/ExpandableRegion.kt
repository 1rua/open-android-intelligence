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
import com.openandroidintelligence.ui.design.isReduced

/**
 * 全 App 唯一的展开/折叠容器。
 *
 * 统一采用 `expandVertically + fadeIn`（折叠为反向），尺寸走临界阻尼弹簧 [MotionSpecs.morph]
 * 让「内容从折叠处生长出来」而不产生布局抖动，透明度走 [MotionSpecs.fade]；系统减少动态时
 * 自动退化为直接落位与短淡化。
 */
@Composable
fun ExpandableRegion(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    reduced: Boolean = LocalMotionPolicy.current.isReduced,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sizeSpec = MotionSpecs.morph<IntSize>(reduced)
    val alphaSpec = MotionSpecs.fade<Float>(reduced)
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(animationSpec = sizeSpec, expandFrom = Alignment.Top) + fadeIn(animationSpec = alphaSpec),
        exit = shrinkVertically(animationSpec = sizeSpec, shrinkTowards = Alignment.Top) + fadeOut(animationSpec = alphaSpec),
    ) {
        Column(content = content)
    }
}
