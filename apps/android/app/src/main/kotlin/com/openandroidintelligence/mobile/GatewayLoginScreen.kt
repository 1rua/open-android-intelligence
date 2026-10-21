package com.openandroidintelligence.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.CONNECTION_FAILURE_FALLBACK
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.bouncyClickable
import com.openandroidintelligence.conversation.components.readableFailure
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.gateway.http.GatewayEndpoint
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 网关登录页面：严格对接真实 Gateway Protocol v2（网关地址 + 账号 + 密码）。
 * 遵循 Material Design 3 规范与品牌设计令牌。
 * 呈现真实的协商、认证阶段与失败重试，坚决不包含已废弃的 Bridge 概念与虚假认证徽章。
 *
 * 布局与安全区：
 * - 根容器补齐 `statusBarsPadding` / `imePadding` / `navigationBarsPadding`：键盘弹出时
 *   主按钮与页脚不会被遮挡，横屏时内容不会落到手势条里；
 * - 内容在宽屏上限宽 [Dimensions.FormWidth] 并水平居中，不再通栏拉伸；
 * - 全部字号与间距取自 `MaterialTheme.typography` 与 [Dimensions]，不写死 sp / dp。
 *
 * @param isDarkTheme / [onToggleTheme] 为兼容既有调用方保留：主题切换入口已收敛到
 *   「设置 → 外观与动效」，登录页不再重复提供一个无调用方的按钮。
 */
@Composable
fun GatewayLoginScreen(
    phase: ConnectionPhase,
    onLogin: (url: String, username: String, password: CharArray) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = true,
    onToggleTheme: (() -> Unit)? = null,
    savedProfiles: List<AccountProfile> = emptyList(),
    onSelectProfile: (String) -> Unit = {},
    onRemoveProfile: (String) -> Unit = {},
    onReconfirmIdentity: (String, String) -> Unit = { _, _ -> },
    isManagingProfiles: Boolean = false,
    operationNotice: String? = null,
    onDismissNotice: () -> Unit = {},
    onInvite: ((String,String,CharArray,String?)->Unit)? = null,
    onDeviceKey: ((String,String)->Unit)? = null,
    invitationPayload: String? = null,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var invitation by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var invitePayload by remember { mutableStateOf("") }
    var removingProfile by remember { mutableStateOf<AccountProfile?>(null) }
    LaunchedEffect(invitationPayload) {
        invitationPayload?.let { payload ->
            val uri=android.net.Uri.parse(payload)
            if (uri.scheme=="oai" && uri.host=="pair") {
                invitation=true; invitePayload=payload; url=uri.getQueryParameter("gateway").orEmpty(); username=uri.getQueryParameter("account").orEmpty(); password=uri.getQueryParameter("code").orEmpty()
            }
        }
    }
    var showIdentityConfirmation by remember { mutableStateOf(false) }

    val normalizedUrl = remember(url) { sanitizeGatewayUrl(url) }
    val invitationUri=remember(invitePayload) { runCatching { android.net.Uri.parse(invitePayload) }.getOrNull() }
    val invitationFingerprint = invitationUri?.getQueryParameter("identityFingerprint")?.takeIf { it.isNotBlank() }
    val invitationCurrent = invitePayload.isBlank() || runCatching {
        invitationUri?.scheme == "oai" && invitationUri.host == "pair" && invitationUri.getQueryParameter("invitationId")?.isNotBlank() == true &&
            (invitationFingerprint == null || invitationFingerprint.matches(Regex("sha256:[0-9a-f]{64}"))) && java.time.Instant.parse(invitationUri.getQueryParameter("expiresAt")).isAfter(java.time.Instant.now())
    }.getOrDefault(false)
    // The address decides whether this pairing can be verified at all, so the
    // form classifies it exactly the way the runtime will.
    val endpoint = remember(normalizedUrl) { GatewayEndpoint.parse(normalizedUrl) }
    val urlIsValid = endpoint != null
    val showUrlError = url.isNotBlank() && !urlIsValid
    val plaintextAddress = endpoint?.isTls == false
    val isBusy = phase is ConnectionPhase.Negotiating || phase is ConnectionPhase.Authenticating || isManagingProfiles
    val reduceMotion = LocalMotionPolicy.current.reduceMotion

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                // 先用 widthIn 再 fillMaxWidth：上限才真正生效（反过来会被通栏拉伸覆盖）。
                .widthIn(max = Dimensions.FormWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceLarge,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ===== 1. 顶部状态行（右侧对齐的状态指示；主题切换统一在设置页） =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(Dimensions.SpaceSmall)
                        .clip(CircleShape)
                        .background(
                            if (phase is ConnectionPhase.Connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
                Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                Text(
                    text = if (phase is ConnectionPhase.Connected) "已连接" else "未连接",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(Dimensions.SpaceXLarge))

            // ===== 2. 品牌：信号缝线 + 字标 + 一句说明 =====
            SignalStitch(modifier = Modifier.height(Dimensions.BrandMark))
            Spacer(modifier = Modifier.height(Dimensions.SpaceCompact))
            Text(
                text = "Open Android Intelligence",
                style = AppTextStyles.BrandWordmark,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Dimensions.SpaceTiny))
            Text(
                text = "连接你的 Agent Gateway",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))

            // ===== 3. 网关登录表单 =====
            SettingsSectionCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentPadding = PaddingValues(
                    horizontal = Dimensions.SpaceMedium,
                    vertical = Dimensions.SpaceMedium,
                ),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
                ) {
                    if (onInvite != null) {
                        TextButton(onClick={ invitation=!invitation; password="";invitePayload="" }) { Text(if (invitation) "改用密码登录" else "使用邀请短码") }
                        if (invitation) FormInputField(label="二维码内容",value=invitePayload,onValueChange={ value ->
                            invitePayload=value
                            val uri=android.net.Uri.parse(value)
                            if (uri.scheme=="oai" && uri.host=="pair") {
                                uri.getQueryParameter("gateway")?.let { url=it }; uri.getQueryParameter("account")?.let { username=it }; uri.getQueryParameter("code")?.let { password=it }
                            }
                        },placeholder="粘贴 oai://pair?… 邀请内容")
                    }
                    FormInputField(
                        label = "网关地址",
                        value = url,
                        onValueChange = { input ->
                            var cleaned = input
                            while (cleaned.startsWith("https://http://", ignoreCase = true) ||
                                cleaned.startsWith("http://http://", ignoreCase = true) ||
                                cleaned.startsWith("https://https://", ignoreCase = true) ||
                                cleaned.startsWith("http://https://", ignoreCase = true)
                            ) {
                                cleaned = cleaned.substring(cleaned.indexOf("://") + 3)
                            }
                            url = cleaned
                        },
                        onClear = { url = "" },
                        placeholder = "https://gateway.example.local:8443 或 http://10.0.2.2:8045",
                        isError = showUrlError,
                        supportingText = if (showUrlError) "请输入包含主机名的有效网关地址（例如 http://10.0.2.2:8045 或 https://gateway.example.com）" else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )

                    // 明文地址允许使用，但绝不静默：只要地址栏是该地址，警告就一直显示。
                    if (plaintextAddress) {
                        PlaintextConnectionNotice()
                    }

                    FormInputField(
                        label = "用户名 / 账号",
                        value = username,
                        onValueChange = { username = it },
                        onClear = { username = "" },
                        placeholder = "输入账号",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )

                    FormInputField(
                        label = if (invitation) "一次性邀请短码" else "访问凭据 / 密码",
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "输入访问凭据或密码",
                        isPassword = true,
                        passwordVisible = passwordVisible,
                        onTogglePassword = { passwordVisible = !passwordVisible },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (urlIsValid && username.isNotBlank() && password.isNotEmpty() && (!invitation || invitationCurrent) && !isBusy) {
                                    val secret = password.toCharArray()
                                    password = ""
                                    if (invitation && onInvite != null) onInvite(normalizedUrl, username.trim(), secret, invitationFingerprint) else onLogin(normalizedUrl, username.trim(), secret)
                                }
                            },
                        ),
                    )

                    // 阶段横幅只做淡化切换：缩短的 200ms fade 已足够表达状态变化，
                    // 不再用 400ms 的缩放抢注意力（减少动态时进一步退化为 150ms）。
                    AnimatedContent(
                        targetState = phase,
                        transitionSpec = {
                            fadeIn(MotionSpecs.fade(reduceMotion)) togetherWith
                                fadeOut(MotionSpecs.fade(reduceMotion))
                        },
                        label = "gateway-login-phase",
                    ) { currentPhase ->
                        PhaseBanner(currentPhase, onRetry)
                    }

                    Button(
                        onClick = {
                            val secret = password.toCharArray()
                            password = ""
                            if (invitation && onInvite != null) onInvite(normalizedUrl, username.trim(), secret, invitationFingerprint) else onLogin(normalizedUrl, username.trim(), secret)
                        },
                        enabled = urlIsValid && username.isNotBlank() && password.isNotEmpty() && (!invitation || invitationCurrent) && !isBusy,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall),
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                            Text("正在连接…", style = MaterialTheme.typography.labelLarge)
                        } else {
                            Text("连接至网关", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimensions.SpaceMedium))

            // Keystore notice
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimensions.SmallIcon),
                )
                Spacer(modifier = Modifier.width(Dimensions.SpaceTiny))
                Text(
                    text = "凭据只用于本次认证，刷新凭据由 Android Keystore 保护",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (savedProfiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
                Text(
                    text = "已保存账号",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
                SettingsSectionCard {
                    savedProfiles.forEachIndexed { index, profile ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        ListItem(
                            headlineContent = {
                                Text(profile.username, style = MaterialTheme.typography.bodyLarge)
                            },
                            supportingContent = {
                                Text(
                                    text = profile.gatewayBaseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                TextButton(
                                    enabled = !isBusy,
                                    onClick = { removingProfile = profile },
                                ) { Text("移除") }
                            },
                            // 卡片底色由分组卡片负责，行本身保持透明，避免叠出第二块面。
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            // 整行可点即「用这个账号登录」，TalkBack 会朗读为按钮。
                            modifier = Modifier.bouncyClickable(
                                enabled = !isBusy,
                                role = Role.Button,
                            ) {
                                url = profile.gatewayBaseUrl
                                username = profile.username
                                password = ""
                                onSelectProfile(profile.localProfileId)
                            },
                        )
                    }
                }
            }

            operationNotice?.let { notice ->
                Spacer(modifier = Modifier.height(Dimensions.SpaceMedium))
                // 操作结果用标准提示条呈现（图标 + 文案 + 关闭），不再是一行裸文本。
                OperationNoticeBanner(text = notice, onDismiss = onDismissNotice)
            }
            if (invitation && !invitationCurrent) {
                Spacer(modifier = Modifier.height(Dimensions.SpaceSmall))
                Text(
                    text = "邀请内容无效或已过期，请重新获取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (onDeviceKey != null && savedProfiles.any { it.gatewayBaseUrl==normalizedUrl && it.username==username.trim() }) {
                TextButton(enabled=!isBusy,onClick={ password="";onDeviceKey(normalizedUrl,username.trim()) }) { Text("使用已配对设备密钥登录") }
            }
            if (endpoint?.isTls == true && username.isNotBlank() && phase is ConnectionPhase.Failed) {
                TextButton(enabled = !isBusy, onClick = { showIdentityConfirmation = true }) { Text("重新确认网关身份") }
            }

            // ===== 4. 页脚入口 =====
            TextButton(onClick = onOpenSettings) {
                Text(
                    "设置与平台管理",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    removingProfile?.let { profile ->
        AlertDialog(onDismissRequest = { removingProfile = null }, title = { Text("移除本机账号") },
            text = { Text("将退出 ${profile.username} 并删除本机凭据、配对密钥、账号资料和临时文件。Gateway 账号仍会保留；移除失败时可重试。") },
            confirmButton = { TextButton(onClick = { onRemoveProfile(profile.localProfileId); removingProfile = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { removingProfile = null }) { Text("取消") } })
    }
    if (showIdentityConfirmation) {
        AlertDialog(onDismissRequest = { showIdentityConfirmation = false }, title = { Text("重新确认网关身份") },
            text = { Text("请先向管理员核对部署或证书变更。继续会清除该账号的旧网关身份和自动登录凭据；下一次密码登录会重新建立 TLS 信任。") },
            confirmButton = { TextButton(onClick = { password = ""; onReconfirmIdentity(normalizedUrl, username.trim()); showIdentityConfirmation = false }) { Text("已核对，继续") } },
            dismissButton = { TextButton(onClick = { showIdentityConfirmation = false }) { Text("取消") } })
    }
}

/**
 * 标准 M3 表单输入框：Outlined 变体不需要自造底色，描边与焦点态全部由 M3 负责。
 *
 * 标签走 `OutlinedTextField(label = ...)` 而不是在输入框外另放一个 Text：
 * 外置标题不会被 TalkBack 关联到输入焦点，读屏时只能听到占位符。
 */
@Composable
private fun FormInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium)
        },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        supportingText = supportingText?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        visualTransformation = if (isPassword && !passwordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { onTogglePassword?.invoke() }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    )
                }
            }
        } else if (onClear != null && value.isNotEmpty()) {
            {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清除内容",
                    )
                }
            }
        } else null,
        shape = RoundedCornerShape(AppRadius.Medium),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 连接阶段横幅：每种状态都有图标 + 文字，颜色永远不是唯一信号。
 * 高度变化由外层 [AnimatedContent] 的淡化承担，这里只负责如实表达状态。
 */
@Composable
private fun PhaseBanner(phase: ConnectionPhase, onRetry: () -> Unit) {
    val stateColors = LocalAppStateColors.current
    when (phase) {
        ConnectionPhase.Disconnected -> Text(
            text = "尚未连接 Gateway",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConnectionPhase.Negotiating -> PhaseProgressRow(text = "正在协商协议版本…")

        ConnectionPhase.Authenticating -> PhaseProgressRow(text = "正在验证凭据…")

        is ConnectionPhase.OfflineMirror -> PhaseSurface(
            containerColor = stateColors.warningContainer,
            contentColor = stateColors.onWarningContainer,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
            Text("离线镜像：显示本机缓存，收发需重新连接", style = MaterialTheme.typography.bodySmall)
        }

        is ConnectionPhase.Connected -> PhaseSurface(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = stateColors.success,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
            Text(
                text = "已连接 ${phase.username}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TransportSecurityChip(phase.transportSecurity)
        }

        is ConnectionPhase.Failed -> PhaseSurface(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.SmallIcon),
                    )
                    Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
                    Text(
                        text = readableFailure(phase.code, CONNECTION_FAILURE_FALLBACK),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onRetry) {
                    Text("重试", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun PhaseProgressRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(Dimensions.SmallIcon), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PhaseSurface(
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(AppRadius.Medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Dimensions.SpaceCompact),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** 明文配对前的持续警告：连接本身可用，但传输内容对网络中的旁观者可读。 */
@Composable
private fun PlaintextConnectionNotice() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(AppRadius.Medium),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Dimensions.SpaceCompact),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Spacer(modifier = Modifier.width(Dimensions.SpaceSmall))
            Text(
                text = "未加密连接（HTTP）：账号口令、消息与附件内容在传输途中可被读取或篡改，" +
                    "且无法核验 Gateway 身份。请仅在你信任的本机或局域网内使用，正式部署请改用 https://。",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** 如实标注当前连接的安全等级，绝不明文伪装成已固定的 TLS 身份。 */
@Composable
private fun TransportSecurityChip(security: TransportSecurity) {
    val plaintext = !security.isEncrypted
    Surface(
        color = if (plaintext) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (plaintext) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(AppRadius.ExtraSmall),
    ) {
        Text(
            text = when (security) {
                TransportSecurity.PLAINTEXT -> "未加密（HTTP）"
                TransportSecurity.TLS_PINNED -> "TLS 已固定"
                TransportSecurity.TLS_SYSTEM_TRUST -> "系统 CA 信任"
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Dimensions.SpaceSmall, vertical = Dimensions.SpaceTiny),
        )
    }
}

/** Default to HTTPS; preserve explicit HTTP and let the endpoint reject ambiguous schemes. */
fun sanitizeGatewayUrl(raw: String): String {
    var trimmed = raw.trim()
    if (trimmed.isNotEmpty() && !trimmed.contains("://")) {
        trimmed = "https://$trimmed"
    }
    return trimmed.removeSuffix("/")
}
