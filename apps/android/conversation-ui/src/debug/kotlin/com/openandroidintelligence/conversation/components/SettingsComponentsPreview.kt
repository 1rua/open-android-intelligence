package com.openandroidintelligence.conversation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme

/** 预览：分组大卡片 + 三种色调的 ListItem 条目。 */
@PreviewLightDark
@Preview(name = "设置条目 · 动态取色", showSystemUi = true)
@Composable
private fun SettingsComponentsPreview() {
    OpenAndroidIntelligenceTheme(dynamicColor = true) {
        Surface {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(Dimensions.ScreenHorizontal),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
            ) {
                SettingsSectionCard {
                    SettingsCardHeader(title = "外观与动效")
                    SettingsSwitchItem(
                        headline = "系统动态取色",
                        supporting = "跟随系统壁纸提取色调（Android 12+）",
                        checked = true,
                        icon = Icons.Default.Palette,
                        tone = SettingsTone.PRIMARY,
                        onCheckedChange = {},
                    )
                }
                SettingsSectionCard {
                    SettingsCardHeader(title = "配对能力授权清单")
                    SettingsSwitchItem(
                        headline = "读取与发送短信",
                        supporting = "限制：每次交互须经手机确认",
                        checked = false,
                        icon = Icons.Default.Extension,
                        onCheckedChange = {},
                    )
                    SettingsNavigationItem(
                        headline = "开发者信任模式",
                        supporting = "未开启：处于 WASM 沙箱隔离保护状态",
                        icon = Icons.Default.Lock,
                        onClick = {},
                    )
                }
                SettingsSectionCard(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer) {
                    SettingsCardHeader(title = "系统级安全熔断")
                    SettingsListItem(
                        headline = "一键紧急停用",
                        supporting = "隔离全部已启用插件并关闭信任模式",
                        icon = Icons.Default.Warning,
                        tone = SettingsTone.DANGER,
                        onClick = {},
                    )
                }
            }
        }
    }
}

/** 预览：展开/折叠容器。 */
@Preview(name = "展开折叠容器")
@Composable
private fun ExpandableRegionPreview() {
    OpenAndroidIntelligenceTheme {
        val expanded = remember { mutableStateOf(true) }
        Column(modifier = Modifier.padding(Dimensions.SpaceMedium)) {
            SettingsListItem(
                headline = if (expanded.value) "收起输出" else "查看输出",
                onClick = { expanded.value = !expanded.value },
            )
            ExpandableRegion(expanded = expanded.value) {
                SettingsListItem(headline = "这里是展开后追加的内容行", enabled = false)
            }
        }
    }
}
