package com.openandroidintelligence.mobile.onboarding.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.mobile.onboarding.components.OnboardingStepHeader
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import androidx.compose.foundation.clickable
import com.openandroidintelligence.mobile.onboarding.motion.RevealOrigin

/**
 * Agent Gateway 部署状态选择页。
 *
 * 只有两条路，且**不要求已部署的用户再读一遍教程**：
 * - A「我已部署」→ 直接触发按钮展开转场，随后进入登录；
 * - B「我还未部署」→ 进入三套 Gateway 的部署教程。
 *
 * 卡片在按下时底部有一条 2dp 强调线从 0 展开到 100%：这是「可点」的
 * 空间反馈，也把视线引向即将发生的转场起点。
 *
 * @param onAlreadyDeployed 回传被点按钮的**几何信息**，供转场以它为起点展开。
 *   几何在这里采集而不是在转场里猜：只有按钮自己知道它在窗口里的真实位置。
 */
@Composable
fun DeploymentChoiceScreen(
    onAlreadyDeployed: (RevealOrigin) -> Unit,
    onNotDeployed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding-deployment"),
    ) {
        OnboardingStepHeader(
            categoryTitle = "CORE SETUP",
            stepIndex = 4,
            totalSteps = 5,
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .widthIn(max = Dimensions.FormWidth)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = Dimensions.ScreenHorizontal)
                .padding(top = Dimensions.SpaceSmall),
        ) {
            Text(
                text = "DEPLOYMENT",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
            Text(
                text = "OAI Core\n部署好了吗？",
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.9).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Dimensions.SpaceCompact))
            Text(
                text = "手机只连接一个 OAI Core，由 Core 接入不同 Agent。若已完成部署，直接进入登录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
            ChoiceCard(
                index = "A",
                title = "我已部署",
                description = "已启动 OAI Core 并创建 OAI 账号。使用同一个服务端地址登录。",
                trailing = "进入登录",
                accent = true,
                onReveal = onAlreadyDeployed,
                testTag = "onboarding-deploy-done",
            )
            Spacer(modifier = Modifier.height(Dimensions.SpaceCompact))
            ChoiceCard(
                index = "B",
                title = "我还未部署",
                description = "查看 OAI Core 安装、账号创建与 oai serve 启动步骤，也保留旧 OpenClaw 兼容指南。",
                trailing = "查看教程",
                accent = false,
                onClick = onNotDeployed,
                testTag = "onboarding-deploy-pending",
            )

            // ===== 边界说明：引导完成 ≠ 已连接 =====
            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(Dimensions.SpaceCompact),
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .heightIn(min = 44.dp)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Spacer(modifier = Modifier.width(Dimensions.SpaceCompact))
                Column {
                    Text(
                        text = "说明",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(modifier = Modifier.height(Dimensions.SpaceTiny))
                    Text(
                        text = "暂不提供统一云端服务，需连接自托管服务器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
        }

        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

/**
 * 一张选择卡片。
 *
 * @param onReveal 需要「以按钮为起点展开」时使用：卡片自己不触发动作，
 *   而是把采集到的几何交给调用方，由流程层启动转场。
 */
@Composable
private fun ChoiceCard(
    index: String,
    title: String,
    description: String,
    trailing: String,
    accent: Boolean,
    testTag: String,
    onReveal: ((RevealOrigin) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = LocalMotionPolicy.current.reduceMotion

    // 采集卡片在**根窗口中的真实几何**：转场以它为圆心与起始形状。
    // 采集时机是布局完成与滚动之后，因此长页面滚动过的位置也是准的。
    var origin by remember { mutableStateOf<RevealOrigin?>(null) }

    val containerColor by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = MotionSpecs.emphasized(reduceMotion),
        label = "choice-card-bg",
    )
    val ruleFraction by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.tertiary else Color.Transparent,
        animationSpec = MotionSpecs.emphasized(reduceMotion),
        label = "choice-card-rule",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(containerColor)
            .border(
                width = Dimensions.StrokeHairline,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.extraLarge,
            )
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInRoot()
                origin = RevealOrigin(center = bounds.center, size = bounds.size.toIntSize())
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
            ) {
                val captured = origin
                if (onReveal != null) {
                    // 几何缺失（极端布局时序）时用卡片默认值兜底，不让点击静默失败。
                    onReveal(captured ?: RevealOrigin(center = Offset.Zero, size = coordsSize()))
                } else {
                    onClick?.invoke()
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$title。$description"
            }
            .testTag(testTag)
            .padding(Dimensions.SpaceMedium),
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                // 右上角大号索引字母：纯排版元素，不进无障碍树。
                Text(
                    text = index,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = (-4).dp),
                )
                Column(modifier = Modifier.padding(end = Dimensions.SpaceXLarge)) {
                    Text(
                        text = title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.4).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimensions.SpaceMedium))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.4.sp,
                    color = if (accent) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                Text(
                    text = "→",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (accent) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 按下的强调线：从底部铺开，纯装饰但传达了「即将发生」的方向。
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(ruleFraction),
        )
    }
}

/** 卡片几何缺失时的兜底尺寸（不影响正确性，只避免转场起点落在 0 尺寸）。 */
private fun coordsSize() = IntSize(width = 320, height = 120)

/**
 * 四段式步骤刻度：引导流程里各页共用同一套进度语言。
 *
 * @param activeIndex 从 1 开始的当前页；之前的段落为「已完成」。
 */
@Composable
internal fun StepRail(activeIndex: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.ScreenHorizontal)
            .padding(bottom = Dimensions.SpaceCompact),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(4) { offset ->
            val index = offset + 1
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(2.dp)
                    .background(
                        when {
                            index == activeIndex -> MaterialTheme.colorScheme.tertiary
                            index < activeIndex -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

/** [androidx.compose.ui.geometry.Size] → [IntSize]：RevealOrigin 需要整数尺寸。 */
private fun androidx.compose.ui.geometry.Size.toIntSize(): IntSize =
    IntSize(width = width.toInt(), height = height.toInt())
