package com.openandroidintelligence.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SettingsSwitchItem
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.ThemePalette
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState
import com.openandroidintelligence.mobile.ThemePreference
import com.openandroidintelligence.mobile.themeLabel

/**
 * 外观与交互设置页面。
 *
 * 功能模块:
 * - 主题模式选择(跟随系统/浅色/深色)
 * - 系统动态取色(Material You)
 * - 图标动态取色
 * - 预设色板选择(6种)
 * - 减少动画开关
 */
@Composable
fun AppearanceSettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    presentation: SettingsPresentation,
    onSetTheme: (ThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetDynamicIconColor: (Boolean) -> Unit,
    onSetPalette: (ThemePalette) -> Unit,
    onSetReduceMotion: (Boolean) -> Unit,
) {
    SettingsPageScaffold(
        title = "外观与交互",
        presentation = presentation,
        onBack = onBack,
    ) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            SectionLabel("主题模式")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    ThemePreference.entries.forEachIndexed { index, option ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        SettingsListItem(
                            headline = themeLabel(option),
                            supporting = when (option) {
                                ThemePreference.SYSTEM -> "跟随系统设置自动切换"
                                ThemePreference.LIGHT -> "始终使用浅色主题"
                                ThemePreference.DARK -> "始终使用深色主题,省电低眩光"
                            },
                            icon = Icons.Default.Palette,
                            selected = uiState.appearance.theme == option,
                            modifier = Modifier.selectable(
                                selected = uiState.appearance.theme == option,
                                role = Role.RadioButton,
                                onClick = { onSetTheme(option) },
                            ),
                            trailing = {
                                RadioButton(
                                    selected = uiState.appearance.theme == option,
                                    onClick = null,
                                )
                            },
                        )
                    }
                }
            }

            SectionLabel("配色方案")
            SettingsSectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                    SettingsSwitchItem(
                        headline = "跟随壁纸取色",
                        supporting = "使用壁纸主色生成应用配色方案 (Android 12+)",
                        checked = uiState.appearance.dynamicColor,
                        icon = Icons.Default.ColorLens,
                        tone = SettingsTone.PRIMARY,
                        onCheckedChange = onSetDynamicColor,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchItem(
                        headline = "图标动态取色",
                        supporting = "桌面图标跟随主题色变化 (需系统支持且开启主题图标)",
                        checked = uiState.appearance.dynamicIconColor,
                        icon = Icons.Default.Palette,
                        tone = SettingsTone.PRIMARY,
                        onCheckedChange = onSetDynamicIconColor,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ThemePalette.entries.forEachIndexed { index, palette ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        PaletteOptionRow(
                            palette = palette,
                            selected = !uiState.appearance.dynamicColor &&
                                uiState.appearance.palette == palette,
                            enabled = !uiState.appearance.dynamicColor,
                            onSelect = { onSetPalette(palette) },
                        )
                    }
                    Text(
                        text = if (uiState.appearance.dynamicColor) {
                            "动态取色已开启,关闭后可选择预设色板"
                        } else {
                            "当前使用预设色板,开启动态取色将改用壁纸配色"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Dimensions.SpaceMedium,
                            vertical = Dimensions.SpaceSmall,
                        ),
                    )
                }
            }

            SectionLabel("动画效果")
            SettingsSectionCard {
                SettingsSwitchItem(
                    headline = "减少动画",
                    supporting = "降低界面动画的移动和缩放幅度",
                    checked = uiState.appearance.reduceMotion,
                    icon = Icons.Default.Animation,
                    onCheckedChange = onSetReduceMotion,
                )
            }
        }
    }
}

/**
 * 预设色板选项行。
 *
 * 用三个色点预览当前方案的 primary / primaryContainer / tertiary,
 * 用户看到的就是切换后界面真正会用的颜色。
 */
@Composable
private fun PaletteOptionRow(
    palette: ThemePalette,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val light = remember(palette) { palette.lightScheme() }
    val dark = remember(palette) { palette.darkScheme() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimensions.MinimumTouchTarget)
            .clip(RoundedCornerShape(AppRadius.Medium))
            .then(
                if (enabled) {
                    Modifier.selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = onSelect,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceCompact)
            .alpha(if (enabled) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
    ) {
        Box(
            modifier = Modifier
                .size(Dimensions.LeadingIconContainer + 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PaletteDot(color = light.primary, dark = dark.primary)
                PaletteDot(color = light.primaryContainer, dark = dark.primaryContainer)
                PaletteDot(color = light.tertiary, dark = dark.tertiary)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = palette.label,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = palette.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
        }
    }
}

@Composable
private fun PaletteDot(
    color: Color,
    dark: Color,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (isDark) dark else color),
    )
}
