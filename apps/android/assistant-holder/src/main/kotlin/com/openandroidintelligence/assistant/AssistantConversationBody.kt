package com.openandroidintelligence.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.ui.resources.R as UiR
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors

/**
 * 助理界面的提示条。
 *
 * 本进程的一次性提示（截图缺失、编码失败等）与主进程 `notice` 共用同一种呈现：
 * 颜色用 M3 之外的语义 warning 角色（`LocalAppStateColors`），不手写色值，
 * 并且始终给出文字结论，不靠颜色传达。
 *
 * 只有本进程自己的提示可以被关闭（传 `onDismiss`）；主进程发布的 notice 是它声明的
 * 真实状态，界面不在本地抹掉它，因此 `onDismiss` 为 null 时只显示内容。
 */
@Composable
internal fun AssistantNoticeBar(
    text: String,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppStateColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.warningContainer,
        contentColor = colors.onWarningContainer,
        shape = RoundedCornerShape(AppRadius.Medium),
        border = BorderStroke(Dimensions.StrokeHairline, colors.warning),
    ) {
        Row(
            modifier = Modifier.padding(
                start = Dimensions.SpaceMedium,
                end = if (onDismiss == null) Dimensions.SpaceMedium else Dimensions.SpaceTiny,
                top = Dimensions.SpaceCompact,
                bottom = Dimensions.SpaceCompact,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Outlined.Close, contentDescription = stringResource(UiR.string.assistant_notice_dismiss_desc))
                }
            }
        }
    }
}
