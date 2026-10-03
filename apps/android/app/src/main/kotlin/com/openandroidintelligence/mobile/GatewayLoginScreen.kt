package com.openandroidintelligence.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.components.CONNECTION_FAILURE_FALLBACK
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.readableFailure
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.gateway.http.GatewayEndpoint
import com.openandroidintelligence.gateway.http.TransportSecurity
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * 网关登录页面：严格对接真实 Gateway Protocol v2（网关地址 + 账号 + 密码）。
 * 遵循 Material Design 3 规范与固定品牌色令牌。
 * 呈现真实的协商、认证阶段与失败重试，坚决不包含已废弃的 Bridge 概念与虚假认证徽章。
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
    androidx.compose.runtime.LaunchedEffect(invitationPayload) {
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
    val invitationFingerprint=invitationUri?.getQueryParameter("identityFingerprint")
    val invitationCurrent=invitePayload.isBlank() || runCatching {
        invitationUri?.scheme=="oai" && invitationUri.host=="pair" && invitationUri.getQueryParameter("invitationId")?.isNotBlank()==true &&
            invitationFingerprint?.matches(Regex("sha256:[0-9a-f]{64}"))==true && java.time.Instant.parse(invitationUri.getQueryParameter("expiresAt")).isAfter(java.time.Instant.now())
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
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = Dimensions.FormWidth)
                .padding(horizontal = Dimensions.ScreenHorizontal, vertical = Dimensions.ScreenVertical),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ===== 1. Top Bar: Right-aligned Status and Round Theme Toggle =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    Text(
                        text = if (phase is ConnectionPhase.Connected) "已连接" else "未连接",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                        modifier =                         Modifier
                            .size(Dimensions.MinimumTouchTarget - 8.dp)
                            .clip(CircleShape)
                            .clickable { onToggleTheme?.invoke() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "切换明暗主题",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ===== 2. Brand Section: Title -> Subtitle -> Primary Icon Container =====
            Text(
                text = "Open Android Intelligence",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
                letterSpacing = (-0.3).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "连接你的 Agent Gateway",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                shape = RoundedCornerShape(AppRadius.Medium),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(Dimensions.BrandMark),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.DeveloperBoard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(Dimensions.Icon),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))

            // ===== 3. Gateway Login Form Card =====
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
                                if (urlIsValid && username.isNotBlank() && password.isNotEmpty() && !isBusy) {
                                    val secret = password.toCharArray()
                                    password = ""
                                    if (invitation && onInvite != null) { if (invitationCurrent) onInvite(normalizedUrl,username.trim(),secret,invitationFingerprint) else secret.fill('\u0000') } else onLogin(normalizedUrl, username.trim(), secret)
                                }
                            },
                        ),
                    )

                    AnimatedContent(
                        targetState = phase,
                        transitionSpec = {
                            if (reduceMotion) {
                                fadeIn(MotionSpecs.fade(true)) togetherWith
                                    fadeOut(MotionSpecs.fade(true))
                            } else {
                                (fadeIn(MotionSpecs.fade(false)) + scaleIn(
                                    animationSpec = tween(MotionSpecs.Enter, easing = MotionSpecs.EmphasizedDecelerateEasing),
                                    initialScale = 0.92f,
                                )) togetherWith (fadeOut(MotionSpecs.fade(false)) + scaleOut(
                                    animationSpec = tween(MotionSpecs.Exit, easing = MotionSpecs.EmphasizedAccelerateEasing),
                                    targetScale = 0.95f,
                                ))
                            }
                        },
                        label = "gateway-login-phase",
                    ) { currentPhase ->
                        PhaseBanner(currentPhase, onRetry)
                    }

                    Button(
                        onClick = {
                            val secret = password.toCharArray()
                            password = ""
                            if (invitation && onInvite != null) { if (invitationCurrent) onInvite(normalizedUrl,username.trim(),secret,invitationFingerprint) else secret.fill('\u0000') } else onLogin(normalizedUrl, username.trim(), secret)
                        },
                        enabled = urlIsValid && username.isNotBlank() && password.isNotEmpty() && !isBusy,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimensions.MinimumTouchTarget + 8.dp),
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在连接…", fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("连接至网关", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Connection status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
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
                    text = if (phase is ConnectionPhase.Connected) "已连接 Gateway" else "尚未连接 Gateway",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "凭据只用于本次认证，刷新凭据由 Android Keystore 保护",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (savedProfiles.isNotEmpty()) {
                Text("已保存账号", style = MaterialTheme.typography.titleSmall)
                savedProfiles.forEach { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !isBusy, onClick = {
                            url = profile.gatewayBaseUrl
                            username = profile.username
                            password = ""
                            onSelectProfile(profile.localProfileId)
                        }, modifier = Modifier.weight(1f)) {
                            Text("${profile.username} · ${profile.gatewayBaseUrl}")
                        }
                        TextButton(enabled = !isBusy, onClick = { removingProfile = profile }) { Text("移除") }
                    }
                }
            }
            operationNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (invitation && !invitationCurrent) Text("邀请内容无效或已过期，请重新获取。",color=MaterialTheme.colorScheme.error)
            if (onDeviceKey != null && savedProfiles.any { it.gatewayBaseUrl==normalizedUrl && it.username==username.trim() }) {
                TextButton(enabled=!isBusy,onClick={ password="";onDeviceKey(normalizedUrl,username.trim()) }) { Text("使用已配对设备密钥登录") }
            }
            if (endpoint?.isTls == true && username.isNotBlank() && phase is ConnectionPhase.Failed) {
                TextButton(enabled = !isBusy, onClick = { showIdentityConfirmation = true }) { Text("重新确认网关身份") }
            }

            // ===== 4. Footer Action =====
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
 * 标准 M3 表单输入框：Outlined 变体不需要自造底色，描边与焦点态全部由 M3 负责，
 * 因此这里不存在任何容器色，深浅主题与动态取色都能正确跟随。
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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PhaseBanner(phase: ConnectionPhase, onRetry: () -> Unit) {
    when (phase) {
        ConnectionPhase.Disconnected -> {
            Text(
                text = "尚未连接 Gateway",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }

        ConnectionPhase.Negotiating -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("正在协商协议版本…", style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
        }

        ConnectionPhase.Authenticating -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("正在验证凭据…", style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
        }

        is ConnectionPhase.OfflineMirror -> Text("离线镜像",color=MaterialTheme.colorScheme.secondary)
        is ConnectionPhase.Connected -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                color = if (phase.transportSecurity.isEncrypted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "已连接 ${phase.username}",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.width(6.dp))
            TransportSecurityChip(phase.transportSecurity)
        }

        is ConnectionPhase.Failed -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(AppRadius.Medium),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Dimensions.SpaceCompact)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = readableFailure(phase.code, CONNECTION_FAILURE_FALLBACK),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                    )
                }
                TextButton(onClick = onRetry) {
                    Text("重试", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(Dimensions.SpaceSmall)
            .clip(CircleShape)
            .background(color),
    )
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
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "未加密连接（HTTP）：账号口令、消息与附件内容在传输途中可被读取或篡改，" +
                    "且无法核验 Gateway 身份。请仅在你信任的本机或局域网内使用，正式部署请改用 https://。",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
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
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
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
