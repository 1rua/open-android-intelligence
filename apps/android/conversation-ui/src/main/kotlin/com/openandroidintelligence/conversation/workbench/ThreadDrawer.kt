package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions

/**
 * 侧边抽屉面板：严格遵循主 App 信息架构（设计规格 §5.1）：
 * 1. Gateway 身份与账号信息；
 * 2. 新建对话；
 * 3. 当前 Gateway 的对话线程（支持搜索与选中高亮）；
 * 4. 附件库；
 * 5. 设置与平台管理（置于底部页脚常驻区吸底显示）；
 * 坚决不包含假设备卡片（ADB、电量、Wi-Fi）及越界导航项（通知中心、帮助反馈等）。
 */
@Composable
fun ThreadDrawer(
    gatewayLabel: String,
    threads: Loadable<List<ConversationSummary>>,
    activeThreadId: String?,
    onOpenThread: (String) -> Unit,
    onCreateThread: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    showClose: Boolean = true,
    onOpenAttachments: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val threadList = (threads as? Loadable.Ready)?.value.orEmpty()
    val username = if (gatewayLabel.contains("@")) gatewayLabel.substringBefore("@") else "用户"

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 1. Gradient Brand Header =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(AppRadius.Medium),
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                            modifier = Modifier.size(Dimensions.MinimumTouchTarget),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        if (showClose) {
                            IconButton(onClick = onCloseDrawer) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "关闭抽屉",
                                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "Open Android Intelligence",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 18.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Agent Gateway",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                    )
                }
            }

            // ===== 2. User & Gateway Info Section =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = username.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = username,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = gatewayLabel.removePrefix("https://"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(Dimensions.SpaceSmall)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            text = "在线",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            // ===== 3. Navigation List (Scrollable) =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Action: 新建对话
                Button(
                    onClick = {
                        onCreateThread()
                        onCloseDrawer()
                    },
                    shape = RoundedCornerShape(AppRadius.Small),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("新建对话", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }

                Spacer(Modifier.height(4.dp))

                // 会话搜索与列表头部
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索会话…", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(AppRadius.Small),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimensions.MinimumTouchTarget),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "会话列表",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        if (threadList.isNotEmpty()) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "${threadList.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Refresh, "刷新会话", modifier = Modifier.size(16.dp))
                    }
                }

                // 线程项
                val filtered = threadList.filter { it.title.contains(query, ignoreCase = true) }
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (query.isNotBlank()) "暂无匹配对话" else "暂无历史对话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    filtered.forEach { thread ->
                        val isSelected = thread.id.value == activeThreadId
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = thread.title.ifBlank { "未命名对话" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Chat,
                                    contentDescription = null,
                                    modifier = Modifier.size(Dimensions.SmallIcon),
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                                headlineColor = if (isSelected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                leadingIconColor = if (isSelected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.Medium))
                                .clickable {
                                    onOpenThread(thread.id.value)
                                    onCloseDrawer()
                                },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                Spacer(Modifier.height(4.dp))

                // 附件库入口
                DrawerNavItem(
                    icon = Icons.Default.AttachFile,
                    label = "附件库",
                    onClick = {
                        onOpenAttachments?.invoke() ?: onOpenSettings()
                        onCloseDrawer()
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            // ===== 4. Footer (Settings, Protocol Version & Logout) =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 平台设置入口（常驻吸底）
                DrawerNavItem(
                    icon = Icons.Default.Settings,
                    label = "设置与平台管理",
                    onClick = {
                        onOpenSettings()
                        onCloseDrawer()
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Gateway Protocol v2",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )

                    TextButton(
                        onClick = { onLogout?.invoke() ?: onOpenSettings() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "退出登录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/** 抽屉底部的功能入口：与设置页共用同一套官方 ListItem 规范。 */
@Composable
private fun DrawerNavItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
) {
    SettingsListItem(
        headline = label,
        modifier = modifier.clip(RoundedCornerShape(AppRadius.Medium)),
        icon = icon,
        tone = if (isActive) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
        onClick = onClick,
    )
}
