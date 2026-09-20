package com.openandroidintelligence.conversation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions

/** 设置条目的语义色调，决定前置图标容器与图标取哪个 M3 角色。 */
enum class SettingsTone {
    /** 普通条目：中性容器 + onSurfaceVariant 图标。 */
    NEUTRAL,

    /** 强调条目：primaryContainer 容器 + onPrimaryContainer 图标。 */
    PRIMARY,

    /** 危险条目：errorContainer 容器 + onErrorContainer 图标。 */
    DANGER,
}

/**
 * 设置类「分组大卡片」。
 *
 * 一个业务分组 = 一张卡：背景走 `surfaceContainer`（强调组可用 `surfaceContainerHigh`），
 * 圆角统一 24dp，内部按 8dp 栅格排布。这是 Android 官方设置页的容器层级表达，
 * 取代之前散落的、各自 14dp 圆角的临时 Surface。
 */
@Composable
fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = Dimensions.SpaceMedium,
        vertical = Dimensions.SpaceSmall,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppRadius.Large),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** 卡片标题，用于多张分组卡之间的层级区分。 */
@Composable
fun SettingsCardHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ProvideTextStyle(MaterialTheme.typography.titleSmall) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                Text(title, modifier = Modifier.weight(1f, fill = false))
            }
        }
        trailing?.invoke()
    }
}

/**
 * 标准设置条目，基于官方 M3 [ListItem]：
 * 前置图标容器 → 标题 + 辅助说明两行排版 → 后置控件槽位。
 *
 * 点击反馈一律保留 M3 Ripple（不要再额外画 `.clickable` 之外的高亮）；
 * [onClick] 传 null 表示这一行只展示、不可交互。
 */
@Composable
fun SettingsListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    tone: SettingsTone = SettingsTone.NEUTRAL,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactive = onClick != null && enabled
    ListItem(
        headlineContent = { Text(headline, style = MaterialTheme.typography.bodyLarge) },
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .then(if (interactive) Modifier.clickable(onClick = onClick!!) else Modifier),
        supportingContent = supporting?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium) }
        },
        leadingContent = icon?.let { { SettingsIconBadge(icon = it, tone = tone, enabled = enabled) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/**
 * 开关型条目：整行可点，Switch 本身只读展示（[Switch] 的 `onCheckedChange` 置空），
 * 避免出现「点图标一个波纹、点文字又一个波纹」的双重反馈。
 */
@Composable
fun SettingsSwitchItem(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    tone: SettingsTone = SettingsTone.NEUTRAL,
    enabled: Boolean = true,
) {
    SettingsListItem(
        headline = headline,
        modifier = modifier,
        supporting = supporting,
        icon = icon,
        tone = tone,
        enabled = enabled,
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        trailing = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
    )
}

/** 跳转型条目：行尾固定 chevron 箭头。 */
@Composable
fun SettingsNavigationItem(
    headline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    tone: SettingsTone = SettingsTone.NEUTRAL,
    enabled: Boolean = true,
) {
    SettingsListItem(
        headline = headline,
        modifier = modifier,
        supporting = supporting,
        icon = icon,
        tone = tone,
        enabled = enabled,
        onClick = if (enabled) onClick else null,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
        },
    )
}

/**
 * 前置图标容器：32dp 圆形底衬 + 18dp 图标，颜色取自 M3 容器角色。
 * 遵循现代开源项目（如 rikka-hub）与 Material 3 精致紧凑规范。
 * 图标置于底衬中心，形成层级分明的视觉符号。
 */
@Composable
fun SettingsIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: SettingsTone = SettingsTone.NEUTRAL,
    size: Dp = Dimensions.LeadingIconContainer,
    enabled: Boolean = true,
) {
    val (container, content) = when (tone) {
        SettingsTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest to
            MaterialTheme.colorScheme.onSurfaceVariant
        SettingsTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        SettingsTone.DANGER -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.38f),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
        }
    }
}
