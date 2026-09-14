package com.openandroidintelligence.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.components.readableFailure
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.gateway.http.GatewayEndpoint
import com.openandroidintelligence.gateway.http.TransportSecurity
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
) {
    var url by rememberSaveable { mutableStateOf("https://") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val normalizedUrl = url.trim()
    // The address decides whether this pairing can be verified at all, so the
    // form classifies it exactly the way the runtime will.
    val endpoint = remember(normalizedUrl) { GatewayEndpoint.parse(normalizedUrl) }
    val urlIsValid = endpoint != null
    val showUrlError = normalizedUrl != "https://" && normalizedUrl.isNotEmpty() && !urlIsValid
    val plaintextAddress = endpoint?.isTls == false
    val isBusy = phase is ConnectionPhase.Negotiating || phase is ConnectionPhase.Authenticating
    val reduceMotion = LocalMotionPolicy.current.reduceMotion

    val cardBg = if (isDarkTheme) Color(0xFF19231F) else Color.White
    val cardBorder = if (isDarkTheme) Color(0xFF26332E) else Color(0xFFD5E2DC)
    val inputBg = if (isDarkTheme) Color(0xFF101613) else Color(0xFFE3ECE7)

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
                .widthIn(max = 390.dp)
                .padding(horizontal = 24.dp, vertical = 16.dp),
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
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (phase is ConnectionPhase.Connected) Color(0xFF4ADE80)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
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
                        modifier = Modifier
                            .size(32.dp)
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
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 2.dp,
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.DeveloperBoard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ===== 3. Gateway Login Form Card =====
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cardBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    FormInputField(
                        label = "网关地址",
                        value = url,
                        onValueChange = { url = it },
                        placeholder = "https://gateway.example.local:8443",
                        inputBg = inputBg,
                        isError = showUrlError,
                        supportingText = if (showUrlError) "请输入包含主机名的网关地址（http:// 或 https://）" else null,
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
                        placeholder = "输入账号",
                        inputBg = inputBg,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )

                    FormInputField(
                        label = "访问凭据 / 密码",
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "输入访问凭据或密码",
                        inputBg = inputBg,
                        isPassword = true,
                        passwordVisible = passwordVisible,
                        onTogglePassword = { passwordVisible = !passwordVisible },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (urlIsValid && username.isNotBlank() && password.isNotEmpty() && !isBusy) {
                                    val secret = password.toCharArray()
                                    password = ""
                                    onLogin(normalizedUrl, username.trim(), secret)
                                }
                            },
                        ),
                    )

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
                            onLogin(normalizedUrl, username.trim(), secret)
                        },
                        enabled = urlIsValid && username.isNotBlank() && password.isNotEmpty() && !isBusy,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
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
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (phase is ConnectionPhase.Connected) Color(0xFF4ADE80)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (phase is ConnectionPhase.Connected) "已连接 Gateway" else "尚未连接 Gateway",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
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
}

@Composable
private fun FormInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    inputBg: Color,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
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
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
        )

        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                )
            },
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            isError = isError,
            supportingText = supportingText?.let { { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) } },
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else null,
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = inputBg,
                unfocusedContainerColor = inputBg,
                disabledContainerColor = inputBg,
                errorContainerColor = inputBg,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
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
            phase.limits?.maxSingleAttachmentBytes?.let { max ->
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "附件上限 ${max / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }

        is ConnectionPhase.Failed -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Dimensions.SpaceCompact)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = readableFailure(phase.code), style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
                }
                TextButton(onClick = onRetry) {
                    Text("重试", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
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
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
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
        shape = RoundedCornerShape(6.dp),
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
