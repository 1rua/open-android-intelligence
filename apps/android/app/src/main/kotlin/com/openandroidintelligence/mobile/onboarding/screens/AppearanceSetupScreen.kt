package com.openandroidintelligence.mobile.onboarding.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.ThemePalette
import com.openandroidintelligence.conversation.theme.supportsDynamicColor
import com.openandroidintelligence.mobile.AppearanceSettings
import com.openandroidintelligence.mobile.ThemePreference
import com.openandroidintelligence.mobile.onboarding.components.OnboardingStepHeader

/**
 * 首次引导：外观与主题选项设置页。
 *
 * 让用户在正式进入应用前选择自己喜欢的界面风格与色彩，默认提供应用图标同款配色（炽橙 EMBER），
 * 默认不开启动态取色（Monet），保证开箱即获得视觉设计完整度极高的品牌调性。
 */
@Composable
fun AppearanceSetupScreen(
    settings: AppearanceSettings,
    onSetTheme: (ThemePreference) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetPalette: (ThemePalette) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding-appearance"),
    ) {
        OnboardingStepHeader(
            categoryTitle = "APPEARANCE",
            stepIndex = 2,
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
                .padding(horizontal = Dimensions.ScreenHorizontal),
        ) {
            Text(
                text = "PERSONALIZATION",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
            Text(
                text = "外观选项",
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.9).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Dimensions.SpaceCompact))
            Text(
                text = "选择适合你的界面主题与色彩风格",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ===== 深浅主题模式 =====
            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
            Text(
                text = "主题模式",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                val themeOptions = listOf(
                    ThemePreference.SYSTEM to "跟随系统",
                    ThemePreference.LIGHT to "浅色模式",
                    ThemePreference.DARK to "深色模式",
                )
                themeOptions.forEach { (pref, title) ->
                    val selected = settings.theme == pref
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onSetTheme(pref) },
                            )
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = MaterialTheme.shapes.small,
                                    )
                                } else {
                                    Modifier.border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = MaterialTheme.shapes.small,
                                    )
                                },
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Dimensions.SpaceCompact),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            // ===== 动态取色（Material You Monet）开关 =====
            if (supportsDynamicColor()) {
                Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Dimensions.SpaceMedium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "系统动态取色",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(Dimensions.SpaceTiny))
                            Text(
                                text = "从当前壁纸提取配色",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                        Switch(
                            checked = settings.dynamicColor,
                            onCheckedChange = onSetDynamicColor,
                        )
                    }
                }
            }

            // ===== 主题色预设 =====
            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "色彩主题",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (settings.dynamicColor) {
                    Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                    Text(
                        text = "（已启用动态取色）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))

            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
            ) {
                ThemePalette.entries.forEach { palette ->
                    val isSelected = !settings.dynamicColor && settings.palette == palette
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = {
                                    if (settings.dynamicColor) onSetDynamicColor(false)
                                    onSetPalette(palette)
                                },
                            )
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = MaterialTheme.shapes.medium,
                                    )
                                } else {
                                    Modifier.border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = MaterialTheme.shapes.medium,
                                    )
                                },
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimensions.SpaceMedium, vertical = Dimensions.SpaceCompact),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 颜色预览小圆球
                            val previewScheme = palette.darkScheme()
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(previewScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "已选择",
                                        tint = previewScheme.onPrimary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(Dimensions.SpaceMedium))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = palette.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (palette == ThemePalette.EMBER) {
                                        Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                                        Box(
                                            modifier = Modifier
                                                .clip(MaterialTheme.shapes.extraSmall)
                                                .background(MaterialTheme.colorScheme.primary)
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = "图标同款",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(Dimensions.SpaceTiny))
                                Text(
                                    text = palette.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
        }

        // ===== 底部操作区 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceMedium,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onNext,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .widthIn(max = Dimensions.FormWidth)
                    .fillMaxWidth()
                    .heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall)
                    .testTag("onboarding-appearance-next"),
            ) {
                Text(
                    text = "下一步",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                Text(
                    text = "→",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
