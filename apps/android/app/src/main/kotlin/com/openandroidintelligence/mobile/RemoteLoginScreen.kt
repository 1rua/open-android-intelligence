package com.openandroidintelligence.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.openandroidintelligence.conversation.components.M3MorphingButton
import com.openandroidintelligence.conversation.components.M3MorphingButtonDefaults
import com.openandroidintelligence.conversation.components.SettingsSectionCard
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.motion.ProvideSharedVisibilityScope
import com.openandroidintelligence.conversation.motion.SharedTransitionKeys
import com.openandroidintelligence.conversation.motion.m3SharedBounds
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.motion.LocalSharedTransitionScope
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import com.openandroidintelligence.ui.resources.R as UiR

/** 登录入口沿用原品牌、卡片与设置抽屉；认证动作由 Remote 接线层提供。 */
@Composable
internal fun RemoteLoginSettingsHost(
    busy: Boolean,
    connected: Boolean,
    signedIn: Boolean,
    initialOrigin: String,
    error: String?,
    settingsOpen: Boolean,
    onSettingsOpenChange: (Boolean) -> Unit,
    onLogin: (String, String, String) -> Unit,
    onScanPairing: () -> Unit,
    onOAuthLogin: (String) -> Unit,
    onRetry: () -> Unit,
    settingsContent: @Composable (SettingsPresentation, () -> Unit) -> Unit,
    endpointContent: @Composable () -> Unit,
) {
    PlatformSettingsDrawer(
        open = settingsOpen,
        onDismiss = { onSettingsOpenChange(false) },
        settingsContent = settingsContent,
    ) {
        RemoteLoginScreen(busy, connected, signedIn, initialOrigin, error,
            onOpenSettings = { onSettingsOpenChange(true) }, onLogin, onScanPairing, onOAuthLogin,
            onRetry = onRetry,
            endpointContent = endpointContent)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun RemoteLoginScreen(
    busy: Boolean,
    connected: Boolean,
    signedIn: Boolean,
    initialOrigin: String,
    error: String?,
    onOpenSettings: () -> Unit,
    onLogin: (String, String, String) -> Unit,
    onScanPairing: () -> Unit,
    onOAuthLogin: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    endpointContent: @Composable () -> Unit = {},
) {
    var selfHosted by rememberSaveable { mutableStateOf(false) }
    var origin by rememberSaveable { mutableStateOf(initialOrigin) }
    var lastPrefill by rememberSaveable { mutableStateOf(initialOrigin) }
    LaunchedEffect(initialOrigin) {
        if (origin == lastPrefill) {
            origin = initialOrigin
            lastPrefill = initialOrigin
        }
    }
    var account by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val reduceMotion = LocalMotionPolicy.current.isReduced
    val normalizedOrigin = normalizeRemoteLoginOrigin(origin)
    val address = ServiceAddress.parse(normalizedOrigin)
    val canLogin = !busy && address != null && account.isNotBlank() && password.isNotEmpty()
    val login = {
        if (canLogin) {
            val secret = password
            password = ""
            onLogin(normalizedOrigin, account.trim(), secret)
        }
    }

    Box(modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = Dimensions.FormWidth).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimensions.ScreenHorizontal, vertical = Dimensions.SpaceLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(Dimensions.SpaceSmall).clip(CircleShape)
                    .background(if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(Dimensions.SpaceSmall))
                Text(stringResource(if (connected) UiR.string.conn_status_connected else UiR.string.conn_status_disconnected),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Dimensions.SpaceXLarge))
            SignalStitch(modifier = Modifier.height(Dimensions.BrandMark))
            Spacer(Modifier.height(Dimensions.SpaceCompact))
            Text(stringResource(UiR.string.remote_login_brand), style = AppTextStyles.BrandWordmark,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Dimensions.SpaceTiny))
            Text(stringResource(UiR.string.remote_login_subtitle), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Dimensions.SpaceLarge))

            if (signedIn) endpointContent() else SettingsSectionCard(
                modifier = Modifier.testTag("remote-login-card"),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentPadding = PaddingValues(Dimensions.SpaceMedium)) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium)) {
                    TextButton(enabled = !busy, onClick = {
                        selfHosted = !selfHosted
                        password = ""
                        passwordVisible = false
                    }) {
                        Text(stringResource(if (selfHosted) UiR.string.remote_login_use_official else UiR.string.remote_login_use_self_hosted))
                    }
                    val hasSharedScope = LocalSharedTransitionScope.current != null && !reduceMotion
                    AnimatedContent(
                        targetState = selfHosted,
                        transitionSpec = {
                            val baseTransition = fadeIn(MotionSpecs.fade(reduceMotion)) togetherWith
                                fadeOut(MotionSpecs.fade(reduceMotion))
                            if (hasSharedScope) {
                                baseTransition
                            } else {
                                baseTransition.using(SizeTransform { _, _ -> MotionSpecs.spatial(reduceMotion) })
                            }
                        },
                        label = "remote-login-card",
                    ) { showSelfHosted ->
                        ProvideSharedVisibilityScope {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .m3SharedBounds(
                                        key = SharedTransitionKeys.loginCard(),
                                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                        clipShape = MaterialTheme.shapes.medium,
                                    )
                                    .testTag(if (showSelfHosted) "remote-self-hosted-login" else "remote-official-login"),
                                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium),
                            ) {
                                if (showSelfHosted) {
                                    RemoteLoginInput(stringResource(UiR.string.remote_service_address), origin, { input ->
                                        var cleaned = input
                                        while (cleaned.startsWith("https://http://", true) || cleaned.startsWith("http://http://", true) ||
                                            cleaned.startsWith("https://https://", true) || cleaned.startsWith("http://https://", true)) {
                                            cleaned = cleaned.substring(cleaned.indexOf("://") + 3)
                                        }
                                        origin = cleaned
                                    }, busy,
                                        isError = origin.isNotBlank() && address == null,
                                        supportingText = if (origin.isNotBlank() && address == null) stringResource(UiR.string.conn_server_address_validation_error) else null,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                                        onClear = { origin = "" })
                                    if (address?.scheme.equals("http", ignoreCase = true)) RemotePlaintextConnectionNotice()
                                    RemoteLoginInput(stringResource(UiR.string.remote_login_account), account, { account = it }, busy,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), onClear = { account = "" })
                                    RemoteLoginInput(stringResource(UiR.string.remote_login_credential), password, { password = it }, busy,
                                        passwordVisible = passwordVisible, onTogglePassword = { passwordVisible = !passwordVisible },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { login() }))
                                    RemoteLoginPhase(busy, error, onRetry)
                                    M3MorphingButton(
                                        onClick = { login() },
                                        morphed = busy,
                                        enabled = canLogin,
                                        restingCornerPercent = 28f,
                                        morphedCornerPercent = 50f,
                                        pressedCornerPercent = 20f,
                                        colors = M3MorphingButtonDefaults.primaryColors(),
                                        sharedBoundsKey = SharedTransitionKeys.morphingButton("remote-login-primary"),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall),
                                    ) {
                                        if (busy) {
                                            CircularProgressIndicator(
                                                Modifier.size(Dimensions.SmallIcon),
                                                strokeWidth = Dimensions.StrokeStitch,
                                                color = LocalContentColor.current,
                                            )
                                            Spacer(Modifier.width(Dimensions.SpaceSmall))
                                        }
                                        Text(
                                            stringResource(if (busy) UiR.string.conn_status_connecting else UiR.string.remote_login_server),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                } else {
                                    FilledTonalButton(onClick = onScanPairing, enabled = !busy, shape = MaterialTheme.shapes.medium,
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.onSurface),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall)) {
                                        Text(stringResource(UiR.string.remote_login_scan_pairing))
                                    }
                                    Text(stringResource(UiR.string.remote_login_scan_hint), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    M3MorphingButton(
                                        onClick = { onOAuthLogin("github") },
                                        morphed = busy,
                                        enabled = !busy,
                                        restingCornerPercent = 28f,
                                        morphedCornerPercent = 50f,
                                        pressedCornerPercent = 20f,
                                        colors = M3MorphingButtonDefaults.primaryColors(),
                                        sharedBoundsKey = SharedTransitionKeys.morphingButton("remote-login-primary"),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall),
                                    ) {
                                        Text(stringResource(UiR.string.remote_login_github))
                                    }
                                    FilledTonalButton(onClick = { onOAuthLogin("zhihu") }, enabled = !busy, shape = MaterialTheme.shapes.medium,
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.onSurface),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall)) {
                                        Text(stringResource(UiR.string.remote_login_zhihu))
                                    }
                                    FilledTonalButton(onClick = { onOAuthLogin("google") }, enabled = !busy, shape = MaterialTheme.shapes.medium,
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.onSurface),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimensions.MinimumTouchTarget + Dimensions.SpaceSmall)) {
                                        Text(stringResource(UiR.string.remote_login_google))
                                    }
                                    RemoteLoginPhase(busy, error, onRetry)
                                }
                            }
                        }
                    }
                }
            }
            if (signedIn) RemoteLoginPhase(busy, error, onRetry)
            Spacer(Modifier.height(Dimensions.SpaceMedium))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimensions.SmallIcon))
                Spacer(Modifier.width(Dimensions.SpaceTiny))
                Text(stringResource(UiR.string.conn_login_keystore_security_hint), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(UiR.string.common_action_settings), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 输入框继续使用原 M3 圆角、浮动标签和密码可见性语义。 */
@Composable
private fun RemoteLoginInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onClear: (() -> Unit)? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
) {
    OutlinedTextField(value, onValueChange, enabled = !busy, singleLine = true,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        isError = isError,
        supportingText = supportingText?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        visualTransformation = if (onTogglePassword != null && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = when {
            onTogglePassword != null -> ({ IconButton(onClick = onTogglePassword) {
                Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (passwordVisible) UiR.string.common_accessibility_hide_password else UiR.string.common_accessibility_show_password))
            } })
            onClear != null && value.isNotEmpty() -> ({ IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, stringResource(UiR.string.common_accessibility_clear_text))
            } })
            else -> null
        },
        shape = MaterialTheme.shapes.medium, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        modifier = Modifier.fillMaxWidth())
}

/** 延续原登录入口：省略协议时默认 HTTPS，不改变显式 HTTP 地址。 */
private fun normalizeRemoteLoginOrigin(value: String): String {
    val trimmed = value.trim()
    return (if (trimmed.isNotEmpty() && !trimmed.contains("://")) "https://$trimmed" else trimmed).removeSuffix("/")
}
