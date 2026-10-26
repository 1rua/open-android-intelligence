package com.openandroidintelligence.mobile.onboarding.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * 引导流程顶栏与随步骤平滑指示的进度刻度条。
 *
 * @param categoryTitle 顶栏分类标题（如 "APPEARANCE", "SYSTEM ACCESS", "CORE SETUP"）
 * @param stepIndex 当前步骤序号（1-based，例如 2）
 * @param totalSteps 步骤总数（例如 5）
 * @param onBack 返回点击回调
 */
@Composable
fun OnboardingStepHeader(
    categoryTitle: String,
    stepIndex: Int,
    totalSteps: Int = 5,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Dimensions.SpaceSmall, vertical = Dimensions.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "返回" },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = categoryTitle,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val currentFormatted = stepIndex.toString().padStart(2, '0')
            val totalFormatted = totalSteps.toString().padStart(2, '0')
            Text(
                text = "$currentFormatted/$totalFormatted",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
        }

        // 步骤指示条：段落颜色随当前步骤状态平滑动效过渡
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.ScreenHorizontal)
                .padding(bottom = Dimensions.SpaceCompact),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(totalSteps) { offset ->
                val index = offset + 1
                val targetColor = when {
                    index == stepIndex -> MaterialTheme.colorScheme.tertiary
                    index < stepIndex -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                val animatedColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = tween(durationMillis = 300),
                    label = "step_rail_color_$index",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(animatedColor),
                )
            }
        }
    }
}
