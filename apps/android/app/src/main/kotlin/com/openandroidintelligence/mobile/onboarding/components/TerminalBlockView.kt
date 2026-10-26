package com.openandroidintelligence.mobile.onboarding.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppStateColors
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.mobile.onboarding.data.TerminalBlock
import kotlinx.coroutines.delay

/** 复制按钮的视觉高度：小控件档位，但仍用最小触控尺寸兜底点击区域。 */
private val CopyButtonHeight = 24.dp

/** 命令区字号：原型 10.5px，在手机上取 11sp 保证可读（中文注释也要能看清）。 */
private val CommandFontSize = 11.sp

/** 命令区行高倍数：原型 1.75，为多行续行命令留出足够的断行辨识度。 */
private const val CommandLineHeightRatio = 1.75f

/** 「已复制」状态保持时长：1.5 秒足够看清，又不会让按钮长期停在完成态。 */
private const val CopiedFeedbackMillis = 1_500L

/**
 * 终端代码块（Terminal Block View）：Gateway 部署教程里的可复制命令框。
 *
 * 结构照原型分两层：
 * 1. 顶部栏（surfaceContainerHigh）：左侧是**语境标签**（"Terminal · 安装插件"），
 *    它告诉用户这条命令要在哪个环境里执行；右侧是复制按钮。
 * 2. 命令区（surfaceContainer）：等宽字体、1.75 倍行高，**必须换行、不横向裁切**——
 *    手机竖屏下一条带续行反斜杠的安装命令很容易超宽，截断会让用户漏拷参数。
 *    这里靠 softWrap（Compose Text 默认）实现 `white-space: pre-wrap` 的等价效果，
 *    且不做任何 maxLines 限制。
 *
 * 复制反馈：
 * - 点击后写入系统剪贴板，按钮文字变「已复制」并换用 [AppStateColors.success]；
 * - 1.5 秒后由 [LaunchedEffect] 自动复原，不依赖外部状态，也不留「复制成功」的哑状态；
 * - 颜色不是唯一信号：按钮同时改了文字，符合状态色使用约定。
 *
 * @param block 要展示的命令块。命令为纯文本、不含富文本标记，保证粘贴回去可直接执行。
 * @param onCopy 可选的复制回调（用于上报分析或提示 Toast）。默认 null，
 *   此时复制仍然发生——剪贴板写入是本组件的自带能力，不因回调缺失而失效。
 */
@Composable
fun TerminalBlockView(
    block: TerminalBlock,
    modifier: Modifier = Modifier,
    onCopy: ((String) -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    val stateColors: AppStateColors = LocalAppStateColors.current
    var copied by remember(block) { mutableStateOf(false) }

    // 复制成功后自行倒计时复原：交给副作用而不是点击处理里的协程作用域，
    // 这样组件被移出组合时计时也会随之取消，不会向已销毁的界面写状态。
    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedFeedbackMillis)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(AppRadius.Medium),
        color = MaterialTheme.colorScheme.surfaceContainer,
        // 1dp 边框让代码框即使贴在同样深浅的背景上也边界清楚。
        border = BorderStroke(Dimensions.StrokeHairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // ── 顶部栏：语境标签 + 复制 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    // 垂直方向不留内边距：顶部栏高度由复制按钮的最小触控尺寸撑开，
                    // 叠加留白会让这一行比原型明显更厚。
                    .padding(horizontal = Dimensions.SpaceCompact),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                Text(
                    text = block.label,
                    style = AppTextStyles.MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // 标签是定位信息，允许截断：完整命令区才是必读内容，标签截断不损失信息。
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CopyButton(
                    copied = copied,
                    successColor = stateColors.success,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(block.command))
                        copied = true
                        onCopy?.invoke(block.command)
                    },
                )
            }

            // ── 命令区：等宽、可换行、不截断 ──
            Text(
                text = block.command,
                fontFamily = FontFamily.Monospace,
                fontSize = CommandFontSize,
                lineHeight = CommandFontSize * CommandLineHeightRatio,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Dimensions.SpaceCompact,
                        vertical = Dimensions.SpaceCompact,
                    )
                    // 整块命令作为一个语义节点。这里显式给出完整命令文本：默认读屏会按
                    // 换行拆读，长命令容易被切碎；合并后一次读完，粘贴内容也不会丢行。
                    .semantics(mergeDescendants = true) {
                        contentDescription = block.command
                    },
            )
        }
    }
}

/**
 * 复制按钮：**视觉 24dp / 命中区 48dp**。
 *
 * 为什么两层结构：`Modifier.height(24.dp)` 一旦生效，布局尺寸就被钉死在 24dp，
 * 再叠加最小触控尺寸也撑不开手指的命中范围。所以把「命中区」和「可见按钮」
 * 拆成两层——外层 Box 负责 [Dimensions.MinimumTouchTarget] 的命中范围与无障碍角色，
 * 内层 Surface 只负责 24dp 的视觉高度与描边。
 *
 * 不用 Material 的 TextButton / OutlinedButton：它们的默认内边距与最小高度
 * 会把顶部栏顶到 40dp 以上，破坏这一行的紧凑感；裸 Box + clickable 可以精确控制，
 * 但仍然补齐 role、contentDescription 与最小触控区，可点击性不打折。
 *
 * @param copied 复制完成状态；为 true 时文字变「已复制」并换 success 语义色。
 */
@Composable
private fun CopyButton(
    copied: Boolean,
    successColor: Color,
    onCopy: () -> Unit,
) {
    val textColor = if (copied) successColor else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (copied) successColor else MaterialTheme.colorScheme.outlineVariant
    val label = if (copied) "已复制" else "复制"

    // 外层：命中区 + 语义。居中内层按钮，让 48dp 的命中区不改变按钮的视觉重心。
    Box(
        modifier = Modifier
            .defaultMinSize(
                minWidth = Dimensions.MinimumTouchTarget,
                minHeight = Dimensions.MinimumTouchTarget,
            )
            .clip(RoundedCornerShape(AppRadius.Small))
            .semantics {
                role = Role.Button
                contentDescription = if (copied) "已复制命令" else "复制命令"
            }
            .clickable(onClick = onCopy),
        contentAlignment = Alignment.Center,
    ) {
        // 内层：可见按钮，固定 24dp 高，与原型一致。
        Surface(
            shape = RoundedCornerShape(AppRadius.ExtraSmall),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(Dimensions.StrokeHairline, borderColor),
            modifier = Modifier.height(CopyButtonHeight),
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = Dimensions.SpaceSmall)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    style = AppTextStyles.MonoSmall,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                )
            }
        }
    }
}
