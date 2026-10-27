package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.SettingsListItem
import com.openandroidintelligence.conversation.components.SettingsTone
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors

/**
 * 侧边抽屉面板：严格遵循主 App 信息架构（设计规格 §5.2）：
 * 1. 顶部 tonal 头部：信号缝线 + 品牌字标（不再是紫色渐变 + 白字）；
 * 2. Gateway 身份与账号信息（首字母头像、主机名、在线状态的文字 + 圆点）；
 * 3. 当前 Gateway 的对话线程（支持搜索、选中高亮与相对时间）；
 * 4. 附件库；
 * 5. 设置与平台管理（置于底部页脚常驻区吸底显示）。
 * 底部安全区由抽屉自己承担 `navigationBarsPadding()`，页脚不会落进手势导航条。
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
    onLogout: () -> Unit,
    organization: ConversationOrganization = ConversationOrganization(),
    managementSupported: Boolean = false,
    deleteSupported: Boolean = managementSupported,
    archiveSupported: Boolean = managementSupported,
    unarchiveSupported: Boolean = archiveSupported,
    renameSupported: Boolean = managementSupported,
    organizationStoredLocally: Boolean = false,
    managing: Boolean = false,
    online: Boolean = true,
    busyThreadIds: Set<String> = emptySet(),
    actions: ConversationBrowserActions = ConversationBrowserActions(),
    selectedFolderId: String? = null,
    accountName: String = "",
    agentName: String? = null,
    avatar: ImageBitmap? = null,
    onChangeAvatar: (() -> Unit)? = null,
    streamHealth: StreamHealth = StreamHealth.IDLE,
) {

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            DrawerHeader(
                username = accountName,
                gatewayLabel = gatewayLabel,
                online = online,
                agentName = agentName,
                avatar = avatar,
                onChangeAvatar = onChangeAvatar,
                streamHealth = streamHealth,
                showClose = showClose,
                onCloseDrawer = onCloseDrawer,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ConversationBrowser(
                threads = threads,
                organization = organization,
                selectedFolderId = selectedFolderId,
                activeThreadId = activeThreadId,
                managementSupported = managementSupported,
                deleteSupported = deleteSupported,
                archiveSupported = archiveSupported,
                unarchiveSupported = unarchiveSupported,
                renameSupported = renameSupported,
                organizationStoredLocally = organizationStoredLocally,
                managing = managing,
                online = online,
                busyThreadIds = busyThreadIds,
                onOpenThread = { id -> onOpenThread(id); onCloseDrawer() },
                onCreateThread = { onCreateThread(); onCloseDrawer() },
                onRefresh = onRefresh,
                actions = actions,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            DrawerNavItem(
                icon = Icons.Default.AttachFile,
                label = "附件库",
                onClick = {
                    onOpenAttachments?.invoke() ?: onOpenSettings()
                    onCloseDrawer()
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ===== 页脚：设置、协议版本与退出登录，常驻吸底 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
            ) {
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
                        .padding(horizontal = Dimensions.SpaceTiny, vertical = Dimensions.SpaceTiny),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Gateway Protocol v2",
                        style = AppTextStyles.MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    TextButton(
                        onClick = onLogout,
                        contentPadding = PaddingValues(horizontal = Dimensions.SpaceSmall, vertical = Dimensions.SpaceTiny),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Dimensions.SmallIcon),
                        )
                        Spacer(Modifier.width(Dimensions.SpaceTiny))
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

/**
 * 抽屉头部：`surfaceContainerLow` 上的 tonal 区块 + 顶部安全区，
 * 品牌只用「信号缝线 + 字标」这一处签名，不再有渐变与白字压浅紫。
 */
@Composable
private fun DrawerHeader(
    username: String,
    gatewayLabel: String,
    online: Boolean,
    showClose: Boolean,
    onCloseDrawer: () -> Unit,
    agentName: String?,
    avatar: ImageBitmap?,
    onChangeAvatar: (() -> Unit)?,
    streamHealth: StreamHealth,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = Dimensions.ScreenHorizontal,
                end = Dimensions.SpaceSmall,
                top = DrawerHeaderTopPadding,
                bottom = Dimensions.SpaceMedium,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalStitch(modifier = Modifier.height(DrawerStitchHeight).width(DrawerStitchWidth))
            Spacer(Modifier.width(Dimensions.SpaceCompact))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Open Android Intelligence",
                    style = AppTextStyles.BrandWordmark,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Agent Gateway",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showClose) {
                IconButton(onClick = onCloseDrawer) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭抽屉",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(Dimensions.SpaceMedium))
        DrawerAccountRow(
            username = username,
            gatewayLabel = gatewayLabel,
            online = online,
            agentName = agentName,
            avatar = avatar,
            onChangeAvatar = onChangeAvatar,
            streamHealth = streamHealth,
        )
    }
}

@Composable
private fun DrawerAccountRow(
    username: String,
    gatewayLabel: String,
    online: Boolean,
    agentName: String?,
    avatar: ImageBitmap?,
    onChangeAvatar: (() -> Unit)?,
    streamHealth: StreamHealth,
) {
    val stateColors = LocalAppStateColors.current
    // 在线状态不是只靠颜色：文字始终在场，颜色只做第二通道。
    val statusColor = if (online && streamHealth == StreamHealth.LIVE) stateColors.success else stateColors.warning
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
    ) {
        Box(
            modifier = Modifier
                .size(DrawerAvatarSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .then(if (onChangeAvatar != null) Modifier.clickable(role = Role.Button, onClick = onChangeAvatar) else Modifier)
                .semantics { contentDescription = if (onChangeAvatar != null) "更换账号头像" else "账号头像" },
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) Image(
                bitmap = avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) else Text(
                text = accountInitial(username),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
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
                modifier = Modifier.padding(top = Dimensions.SpaceTiny),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
                modifier = Modifier.padding(top = Dimensions.SpaceTiny).semantics(mergeDescendants = true) {
                    contentDescription = accountConnectionLabel(agentName, online, streamHealth)
                },
            ) {
                Box(
                    modifier = Modifier
                        .size(DrawerStatusDot)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                agentName?.trim()?.takeIf(String::isNotEmpty)?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    text = accountConnectionLabel(null, online, streamHealth),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun accountInitial(username: String): String = username.trim().let { name ->
    if (name.isEmpty()) "?" else String(Character.toChars(name.codePointAt(0))).uppercase(java.util.Locale.ROOT)
}

internal fun accountConnectionLabel(agentName: String?, online: Boolean, health: StreamHealth): String {
    val status = when {
        !online -> "离线"
        health == StreamHealth.LIVE -> "在线"
        else -> com.openandroidintelligence.conversation.components.connectionLabel(health)
    }
    return listOfNotNull(agentName?.trim()?.takeIf(String::isNotEmpty), status).joinToString(" ")
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

/** 抽屉头部顶部留白（规格 §5.2：tonal 头部顶部 20dp）。 */
private val DrawerHeaderTopPadding = 20.dp

/** 品牌缝线宽度：只占极窄一列，不抢字标。 */
private val DrawerStitchWidth = 8.dp

/** 品牌缝线高度：字标旁边的小尺寸标记，不做整屏品牌墙。 */
private val DrawerStitchHeight = 28.dp

/** 首字母头像直径。 */
private val DrawerAvatarSize = 40.dp

/** 在线状态小圆点直径。 */
private val DrawerStatusDot = 6.dp
