package com.openandroidintelligence.mobile.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.ThemePalette
import com.openandroidintelligence.conversation.theme.supportsDynamicColor
import com.openandroidintelligence.mobile.AppearanceSettings
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.SettingsUiState
import com.openandroidintelligence.mobile.ThemePreference
import com.openandroidintelligence.mobile.settings.SectionLabel
import com.openandroidintelligence.mobile.settings.SettingsPageColumn
import com.openandroidintelligence.mobile.settings.SettingsPageScaffold
import kotlinx.coroutines.CancellationException

/** 设置与引导共用外观编辑器；实际状态及写入均仍由 AppearancePreferences 拥有。 */
@Composable
internal fun AppearanceSettingsScreen(
    uiState: SettingsUiState,
    presentation: SettingsPresentation,
    onBack: () -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetDynamicIconColor: (Boolean) -> Unit,
    onSetPalette: (ThemePalette) -> Unit,
    onSetReduceMotion: (Boolean) -> Unit,
) {
    SettingsPageScaffold(title = "外观与交互", presentation = presentation, onBack = onBack) { padding ->
        SettingsPageColumn(contentPadding = padding) {
            AppearanceEditorContent(
                settings = uiState.appearance,
                onSetTheme = onSetTheme,
                onSetDynamicColor = onSetDynamicColor,
                onSetDynamicIconColor = onSetDynamicIconColor,
                onSetPalette = onSetPalette,
                onSetReduceMotion = onSetReduceMotion,
            )
        }
    }
}

/** showAdvanced=false 隐藏引导原来没有的图标和动效设置。
 * allowPaletteWhileDynamic 保留引导可选色板、并由 selectPalette 关闭动态取色的行为。
 */
@Composable
internal fun AppearanceEditorContent(
    settings: AppearanceSettings,
    onSetTheme: (ThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetPalette: (ThemePalette) -> Unit,
    onSetDynamicIconColor: ((Boolean) -> Unit)? = null,
    onSetReduceMotion: ((Boolean) -> Unit)? = null,
    showAdvanced: Boolean = true,
    allowPaletteWhileDynamic: Boolean = false,
) {
    require(!showAdvanced || (onSetDynamicIconColor != null && onSetReduceMotion != null))
    var failures by remember { mutableStateOf(emptySet<String>()) }
    fun save(control: String, action: () -> Unit) {
        try {
            action()
            failures = failures - control
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failures = failures + control
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium)) {
        SectionLabel("主题模式")
        SettingsSectionCard {
            ThemeSelector(settings.theme, onboarding = !showAdvanced) { theme -> save("theme") { onSetTheme(theme) } }
            if ("theme" in failures) AppearanceSaveFailure()
        }
        SectionLabel("主题色")
        SettingsSectionCard {
            // 引导以前只在支持动态色的设备上显示开关；设置页保留原有入口。
            if (showAdvanced || supportsDynamicColor()) {
                AppearanceSwitch(
                    headline = "系统动态取色",
                    supporting = if (showAdvanced) "主题色由系统壁纸推导；关闭后使用预设色板。Android 12 及以上支持动态取色。" else "从当前壁纸提取配色",
                    checked = settings.dynamicColor,
                    onCheckedChange = { value -> save("color") { onSetDynamicColor(value) } },
                )
                if ("color" in failures) AppearanceSaveFailure()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (!showAdvanced) {
                Text("色彩主题", style = MaterialTheme.typography.titleSmall)
                if (settings.dynamicColor) {
                    Text("（已启用动态取色）", style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(Modifier.selectableGroup()) {
                ThemePalette.entries.forEach { palette ->
                    PaletteOption(
                        palette = palette,
                        showIconMatch = !showAdvanced,
                        selected = !settings.dynamicColor && settings.palette == palette,
                        enabled = !settings.dynamicColor || allowPaletteWhileDynamic,
                        onSelect = { save("palette") { onSetPalette(palette) } },
                    )
                }
            }
            if ("palette" in failures) AppearanceSaveFailure()
            Text(
                text = if (settings.dynamicColor && allowPaletteWhileDynamic) {
                    "动态取色已开启；选择预设色板会关闭动态取色。"
                } else if (settings.dynamicColor) {
                    "动态取色已开启；关闭上面的开关即可选择预设色板。"
                } else {
                    "当前使用预设色板；打开动态取色会改用系统壁纸配色。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Dimensions.SpaceSmall),
            )
        }
        if (showAdvanced) {
            SectionLabel("桌面图标")
            SettingsSectionCard {
                AppearanceSwitch(
                    headline = "图标动态取色",
                    supporting = "跟随桌面主题配色，需要 Android 13 或以上并在桌面开启主题图标。",
                    checked = settings.dynamicIconColor,
                    onCheckedChange = { value -> save("icon") { checkNotNull(onSetDynamicIconColor)(value) } },
                )
                if ("icon" in failures) AppearanceSaveFailure()
            }
            SectionLabel("动效与无障碍")
            SettingsSectionCard {
                AppearanceSwitch(
                    headline = "减少动画",
                    supporting = "减少滑动和缩放，保留轻微淡入淡出；同时遵循系统动画设置。",
                    checked = settings.reduceMotion,
                    onCheckedChange = { value -> save("motion") { checkNotNull(onSetReduceMotion)(value) } },
                )
                if ("motion" in failures) AppearanceSaveFailure()
            }
        }
    }
}

@Composable
private fun AppearanceSaveFailure() {
    Text(
        "外观设置未能保存，请重试。",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(selected: ThemePreference, onboarding: Boolean, onSelect: (ThemePreference) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < Dimensions.DrawerWidth * LocalDensity.current.fontScale
        if (compact) {
            Column(Modifier.selectableGroup()) {
                ThemePreference.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = Dimensions.MinimumTouchTarget)
                            .selectable(selected = selected == option, role = Role.RadioButton, onClick = { onSelect(option) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == option, onClick = null)
                        Text(themeLabel(option, onboarding), modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemePreference.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemePreference.entries.size),
                    ) { Text(themeLabel(option, onboarding)) }
                }
            }
        }
    }
}

private fun themeLabel(option: ThemePreference, onboarding: Boolean): String = when (option) {
    ThemePreference.SYSTEM -> "跟随系统"
    ThemePreference.LIGHT -> if (onboarding) "浅色模式" else "浅色"
    ThemePreference.DARK -> if (onboarding) "深色模式" else "深色"
}

@Composable
private fun AppearanceSwitch(headline: String, supporting: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Dimensions.MinimumTouchTarget)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = Dimensions.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PaletteOption(
    palette: ThemePalette,
    showIconMatch: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val light = remember(palette) { palette.lightScheme() }
    val dark = remember(palette) { palette.darkScheme() }
    val colors = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) dark else light
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = Dimensions.MinimumTouchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .alpha(if (enabled) 1f else 0.6f)
            .padding(vertical = Dimensions.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(colors.primary, colors.primaryContainer, colors.tertiary).forEach { color ->
                Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(palette.label, style = MaterialTheme.typography.titleSmall)
            if (showIconMatch && palette == ThemePalette.EMBER) {
                Text("图标同款", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(palette.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}
