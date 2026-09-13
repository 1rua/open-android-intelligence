package com.openandroidintelligence.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.readableFailure
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy

/**
 * The gateway login screen: negotiate + password login, nothing else.
 *
 * The phase banner reflects only what the runtime proved: negotiating,
 * authenticating, failed with the Gateway's own error code, or connected with
 * the limits this connection negotiated.
 */
@Composable
fun GatewayLoginScreen(
    phase: ConnectionPhase,
    onLogin: (url: String, username: String, password: CharArray) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable { mutableStateOf("https://") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val normalizedUrl = url.trim()
    val urlIsValid = normalizedUrl.isNotEmpty() && isValidHttpsUrl(normalizedUrl)
    val showUrlError = normalizedUrl != "https://" && normalizedUrl.isNotEmpty() && !urlIsValid
    val isBusy = phase is ConnectionPhase.Negotiating || phase is ConnectionPhase.Authenticating
    val reduceMotion = LocalMotionPolicy.current.reduceMotion

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = Dimensions.FormWidth)
                .padding(horizontal = Dimensions.SpaceLarge, vertical = Dimensions.SpaceXLarge),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(Dimensions.BrandMark),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Text(text = "连接你的 Agent Gateway", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "连接用户自有的 Gateway。手机始终是本机数据与设备操作的最终授权者。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(Dimensions.SpaceMedium),
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Gateway 地址") },
                    placeholder = { Text("https://gateway.example.com") },
                    singleLine = true,
                    isError = showUrlError,
                    supportingText = if (showUrlError) {
                        { Text("请输入包含主机名的 HTTPS 地址") }
                    } else {
                        { Text("仅支持经过 TLS 身份验证的 HTTPS Gateway") }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (urlIsValid && username.isNotBlank() && password.isNotEmpty() && !isBusy) {
                                val secret = password.toCharArray()
                                password = ""
                                onLogin(normalizedUrl, username.trim(), secret)
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
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
                        // Passwords are transient input. Clear the Compose state
                        // before handing the copy to the runtime, which wipes it
                        // after the authenticated request has consumed it.
                        password = ""
                        onLogin(normalizedUrl, username.trim(), secret)
                    },
                    enabled = urlIsValid &&
                        username.isNotBlank() &&
                        password.isNotEmpty() &&
                        !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isBusy) "正在连接…" else "登录并配对")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "凭据只用于本次认证，刷新凭据由 Android Keystore 保护",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = onOpenSettings) { Text("设置与平台管理") }
        }
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
            )
        }

        ConnectionPhase.Negotiating -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("正在协商协议版本…", style = MaterialTheme.typography.bodySmall)
        }

        ConnectionPhase.Authenticating -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("正在验证账号密码…", style = MaterialTheme.typography.bodySmall)
        }

        is ConnectionPhase.Connected -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "已连接 ${phase.username}",
                style = MaterialTheme.typography.bodySmall,
            )
            phase.limits?.maxSingleAttachmentBytes?.let { max ->
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "附件上限 ${max / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is ConnectionPhase.Failed -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Dimensions.SpaceCompact)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = readableFailure(phase.code), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRetry) {
                    Text("重新输入", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(Dimensions.SpaceSmall)
            .clip(CircleShape)
            .background(color),
    )
}

private fun isValidHttpsUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}.getOrDefault(false)
