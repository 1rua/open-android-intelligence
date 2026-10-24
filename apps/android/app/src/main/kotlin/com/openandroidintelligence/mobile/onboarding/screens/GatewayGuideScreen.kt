package com.openandroidintelligence.mobile.onboarding.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.mobile.onboarding.components.TerminalBlockView
import com.openandroidintelligence.mobile.onboarding.components.halftone
import com.openandroidintelligence.mobile.onboarding.data.GatewayTutorial
import com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials
import com.openandroidintelligence.mobile.onboarding.data.TutorialStep
import com.openandroidintelligence.mobile.onboarding.motion.RevealOrigin
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * Gateway 部署教程页。
 *
 * 三套教程共用同一套模板（Tab → 名称/定位/元数据 → 分步卡片 → 官方仓库），
 * 差异只在 [GatewayTutorial] 数据里。新增一个 Agent 宿主不需要改这个文件。
 *
 * 步骤卡片刻意做成「编号 + 标题 + 说明 + 可复制的终端框 + 验证提示」的固定结构：
 * - 编号与左侧竖线把多步串成一条可执行路径；
 * - 终端框的语境标签（Terminal / Environment / DSH）告诉用户在**哪个环境**执行；
 * - 验证提示回答「我怎么知道我成了」，避免用户执行完不知道对错。
 */
@Composable
fun GatewayGuideScreen(
    selectedGatewayId: String,
    onSelectGateway: (String) -> Unit,
    onDone: (RevealOrigin) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tutorial = GatewayTutorials.byId(selectedGatewayId)
    val uriHandler = LocalUriHandler.current
    val reduceMotion = LocalMotionPolicy.current.reduceMotion

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding-guide"),
    ) {
        // ===== 顶部导航 =====
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
                text = "DEPLOYMENT GUIDE",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "04/04",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
        }

        StepRail(activeIndex = 4)

        // ===== Gateway 切换 Tab =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Dimensions.ScreenHorizontal)
                .padding(top = Dimensions.SpaceSmall, bottom = Dimensions.SpaceCompact),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            GatewayTutorials.ALL.forEach { item ->
                GatewayTab(
                    tutorial = item,
                    selected = item.id == tutorial.id,
                    onClick = { onSelectGateway(item.id) },
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .widthIn(max = Dimensions.FormWidth)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = Dimensions.ScreenHorizontal),
        ) {
            AnimatedContent(
                targetState = tutorial,
                transitionSpec = {
                    // 切换教程是「同一页内的内容替换」，只做轻微的淡入上浮：
                    // 用横向滑移会让人误以为换了一整个页面。
                    val fadeSpec = if (reduceMotion) {
                        MotionSpecs.fade<Float>(reduceMotion)
                    } else {
                        tween<Float>(durationMillis = 220, easing = MotionSpecs.EmphasizedDecelerateEasing)
                    }
                    val slideSpec = if (reduceMotion) {
                        MotionSpecs.fade<IntOffset>(reduceMotion)
                    } else {
                        tween<IntOffset>(durationMillis = 220, easing = MotionSpecs.EmphasizedDecelerateEasing)
                    }
                    (fadeIn(fadeSpec) + slideInVertically(slideSpec) { it / 42 }) togetherWith fadeOut(fadeSpec)
                },
                label = "gateway-tutorial",
            ) { active ->
                TutorialBody(
                    tutorial = active,
                    onOpenRepository = { uriHandler.openUri(active.repoUrl) },
                )
            }
        }

        // ===== 主操作：部署完毕进入登录 =====
        var doneOrigin by remember { mutableStateOf<RevealOrigin?>(null) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceMedium,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = {
                    val captured = doneOrigin
                    onDone(captured ?: RevealOrigin(
                        center = androidx.compose.ui.geometry.Offset.Zero,
                        size = IntSize(320, 52),
                    ))
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .widthIn(max = Dimensions.FormWidth)
                    .fillMaxWidth()
                    .heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall)
                    .onGloballyPositioned { coords ->
                        val bounds = coords.boundsInRoot()
                        doneOrigin = RevealOrigin(center = bounds.center, size = bounds.size.toIntSize())
                    }
                    .testTag("onboarding-guide-done"),
            ) {
                Text(text = "部署完毕，进入登录", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                Text(text = "→", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.height(Dimensions.SpaceTiny))
            Text(
                text = "未完成？可稍后在设置中重新查看",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一个 Gateway 的 Tab：选中态实底 + 强调色小圆点。 */
@Composable
private fun GatewayTab(
    tutorial: GatewayTutorial,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.onSurface
    else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.surface
    else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (selected) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .heightIn(min = Dimensions.MinimumTouchTarget)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(container)
            .border(Dimensions.StrokeHairline, borderColor, MaterialTheme.shapes.extraLarge)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = "${tutorial.shortName} 部署教程"
            }
            .padding(horizontal = Dimensions.SpaceMedium)
            .testTag("gateway-tab-${tutorial.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
        )
        Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
        Text(
            text = tutorial.shortName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = content,
        )
    }
}

/** 教程正文：名称 → 定位 → 元数据 chip → 分步卡片 → 官方仓库。 */
@Composable
private fun TutorialBody(
    tutorial: GatewayTutorial,
    onOpenRepository: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .halftone(alpha = 0.10f, dotSpacing = 9.dp)
            .padding(top = Dimensions.SpaceSmall),
    ) {
        Text(
            text = tutorial.displayName,
            fontSize = 26.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.7).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
        Text(
            text = tutorial.tagline,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Dimensions.SpaceCompact))

        // 元数据 chip：语言/协议/插件版本，用等宽字排技术参数。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
        ) {
            tutorial.chips.forEach { chip ->
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(
                            Dimensions.StrokeHairline,
                            MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.extraSmall,
                        )
                        .padding(horizontal = Dimensions.SpaceSmall, vertical = 3.dp),
                ) {
                    Text(
                        text = chip.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 0.9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
        tutorial.steps.forEachIndexed { index, step ->
            TutorialStepRow(
                index = index + 1,
                step = step,
                isLast = index == tutorial.steps.lastIndex,
            )
        }

        // ===== 官方仓库入口 =====
        Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .border(
                    Dimensions.StrokeHairline,
                    MaterialTheme.colorScheme.outlineVariant,
                    MaterialTheme.shapes.small,
                )
                .clickable(role = Role.Button, onClick = onOpenRepository)
                .semantics { contentDescription = "打开官方仓库 ${tutorial.repoUrl}" }
                .padding(Dimensions.SpaceCompact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
            Text(
                text = "官方仓库",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
            Text(
                text = tutorial.repoUrl.removePrefix("https://"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
    }
}

/**
 * 一步教程。
 *
 * 左侧序号圆点 + 连接竖线构成路径；最后一步不画竖线，避免线悬在页面底部。
 */
@Composable
private fun TutorialStepRow(
    index: Int,
    step: TutorialStep,
    isLast: Boolean,
) {
    val stateColors = LocalAppStateColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        // ---- 左侧路径列 ----
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(Dimensions.SpaceLarge + Dimensions.SpaceSmall),
        ) {
            Box(
                modifier = Modifier
                    .size(23.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        Dimensions.StrokeHairline,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(Dimensions.StrokeHairline)
                        .height(Dimensions.SpaceLarge + Dimensions.SpaceXLarge)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }

        Spacer(modifier = Modifier.width(Dimensions.SpaceCompact))

        // ---- 右侧内容 ----
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Dimensions.SpaceTiny))
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            step.terminal?.let { block ->
                Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
                TerminalBlockView(block = block)
            }
            step.verifyHint?.let { hint ->
                Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
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
                            .heightIn(min = 34.dp)
                            .background(stateColors.success),
                    )
                    Spacer(modifier = Modifier.width(Dimensions.SpaceCompact))
                    Column {
                        Text(
                            text = "验证通过的样子",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.2.sp,
                            color = stateColors.success,
                        )
                        Spacer(modifier = Modifier.height(Dimensions.SpaceTiny))
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
        }
    }
}

/** [androidx.compose.ui.geometry.Size] → [IntSize]：RevealOrigin 需要整数尺寸。 */
private fun androidx.compose.ui.geometry.Size.toIntSize(): IntSize =
    IntSize(width = width.toInt(), height = height.toInt())
