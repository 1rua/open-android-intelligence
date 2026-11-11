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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.openandroidintelligence.ui.resources.R as UiR
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
    accountLoginStep: AccountLoginStep = AccountLoginStep.None,
    onAccountProof: (CharArray, Boolean) -> Unit = { proof, _ -> proof.fill('\u0000') },
    onRecoveryCodesSaved: () -> Unit = {},
    onCancelAccountLogin: () -> Unit = {},
) {
    // 预填上一次成功登录（或被设置导入写入）的 Core 地址：换机后不必重新输入。
    // 只填地址，不填账号与密码；用户仍可随时改。读取失败时保持空值。
    val prefillContext = LocalContext.current
    val initialPrefill = remember(prefillContext) { LoginGatewayPrefill.read(prefillContext).orEmpty() }
    var url by rememberSaveable { mutableStateOf(initialPrefill) }
    // 用户还没动过地址栏时跟随预填变化（设置里导入备份后地址栏立即更新）；
    // 一旦用户自己输入过，就不再覆盖他写的内容。
    var lastAppliedPrefill by rememberSaveable { mutableStateOf(initialPrefill) }
    val prefilled = lastAppliedPrefill
    val urlMatchesPrefill = url == prefilled
    LaunchedEffect(prefillContext, urlMatchesPrefill) {
        if (!urlMatchesPrefill) return@LaunchedEffect
        LoginGatewayPrefill.observe(prefillContext).collect { latest ->
            // 清空（导入失败回滚会把地址撤掉）与写入同样要同步，否则表单会继续
            // 显示那次失败导入留下的地址，与「本机设置已恢复」的结论自相矛盾。
            val next = latest.orEmpty()
            if (next != url) {
                url = next
                lastAppliedPrefill = next
            }
        }
    }
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
    val isBusy = phase is ConnectionPhase.Initializing || phase is ConnectionPhase.Reconnecting ||
        phase is ConnectionPhase.Negotiating || phase is ConnectionPhase.Authenticating || isManagingProfiles
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
                    text = if (phase is ConnectionPhase.Connected) stringResource(UiR.string.conn_status_connected) else stringResource(UiR.string.conn_status_disconnected),
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
                text = stringResource(UiR.string.conn_login_welcome_subtitle),
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
                        TextButton(onClick={ invitation=!invitation; password="";invitePayload="" }) {
                            Text(if (invitation) stringResource(UiR.string.conn_login_tab_password) else stringResource(UiR.string.conn_login_tab_invite_code))
                        }
                        if (invitation) FormInputField(
                            label = stringResource(UiR.string.conn_login_qr_content),
                            value = invitePayload,
                            onValueChange = { value ->
                                invitePayload=value
                                val uri=android.net.Uri.parse(value)
                                if (uri.scheme=="oai" && uri.host=="pair") {
                                    uri.getQueryParameter("gateway")?.let { url=it }; uri.getQueryParameter("account")?.let { username=it }; uri.getQueryParameter("code")?.let { password=it }
                                }
                            },
                            placeholder = stringResource(UiR.string.conn_login_paste_invite_hint),
                        )
                    }
                    FormInputField(
                        label = stringResource(UiR.string.conn_onboarding_welcome_core_address_label),
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
                        placeholder = stringResource(UiR.string.conn_server_address_example),
                        isError = showUrlError,
                        supportingText = if (showUrlError) stringResource(UiR.string.conn_server_address_validation_error) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )

                    // 明文地址允许使用，但绝不静默：只要地址栏是该地址，警告就一直显示。
                    if (plaintextAddress) {
                        PlaintextConnectionNotice()
                    }

                    FormInputField(
                        label = stringResource(UiR.string.conn_login_username_label),
                        value = username,
                        onValueChange = { username = it },
                        onClear = { username = "" },
                        placeholder = stringResource(UiR.string.conn_login_username_hint),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )

                    FormInputField(
                        label = if (invitation) stringResource(UiR.string.conn_login_invite_code_label) else stringResource(UiR.string.conn_login_password_label),
                        value = password,
                        onValueChange = { password = it },
                        placeholder = stringResource(UiR.string.conn_login_password_hint),
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
                            Text(stringResource(UiR.string.conn_status_connecting), style = MaterialTheme.typography.labelLarge)
                        } else {
                            Text(stringResource(UiR.string.conn_login_title), style = MaterialTheme.typography.labelLarge)
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
                    text = stringResource(UiR.string.conn_login_keystore_security_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (savedProfiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Dimensions.SpaceLarge))
                Text(
                    text = stringResource(UiR.string.conn_account_saved_title),
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
                                ) { Text(stringResource(UiR.string.common_action_remove)) }
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
                    text = stringResource(UiR.string.conn_login_invite_invalid_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (onDeviceKey != null && savedProfiles.any { it.gatewayBaseUrl==normalizedUrl && it.username==username.trim() }) {
                TextButton(enabled=!isBusy,onClick={ password="";onDeviceKey(normalizedUrl,username.trim()) }) { Text(stringResource(UiR.string.conn_login_use_paired_key)) }
            }
            if (endpoint?.isTls == true && username.isNotBlank() && phase is ConnectionPhase.Failed) {
                TextButton(enabled = !isBusy, onClick = { showIdentityConfirmation = true }) { Text(stringResource(UiR.string.conn_login_reconfirm_identity)) }
            }

            // ===== 4. 页脚入口 =====
            TextButton(onClick = onOpenSettings) {
                Text(
                    stringResource(UiR.string.common_action_settings),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (accountLoginStep !== AccountLoginStep.None) {
        AccountLoginDialog(accountLoginStep, onAccountProof, onRecoveryCodesSaved, onCancelAccountLogin)
    }
    removingProfile?.let { profile ->
        AlertDialog(onDismissRequest = { removingProfile = null }, title = { Text(stringResource(UiR.string.conn_account_remove_dialog_title)) },
            text = { Text(stringResource(UiR.string.conn_account_remove_dialog_message, profile.username)) },
            confirmButton = { TextButton(onClick = { onRemoveProfile(profile.localProfileId); removingProfile = null }) { Text(stringResource(UiR.string.common_action_remove)) } },
            dismissButton = { TextButton(onClick = { removingProfile = null }) { Text(stringResource(UiR.string.common_action_cancel)) } })
    }
    if (showIdentityConfirmation) {
        AlertDialog(onDismissRequest = { showIdentityConfirmation = false }, title = { Text(stringResource(UiR.string.conn_reconfirm_identity_dialog_title)) },
            text = { Text(stringResource(UiR.string.conn_reconfirm_identity_dialog_message)) },
            confirmButton = { TextButton(onClick = { password = ""; onReconfirmIdentity(normalizedUrl, username.trim()); showIdentityConfirmation = false }) { Text(stringResource(UiR.string.conn_reconfirm_identity_confirm)) } },
            dismissButton = { TextButton(onClick = { showIdentityConfirmation = false }) { Text(stringResource(UiR.string.common_action_cancel)) } })
    }
}

@Composable
private fun AccountLoginDialog(
    step: AccountLoginStep,
    onProof: (CharArray, Boolean) -> Unit,
    onCodesSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    var input by remember(step) { mutableStateOf("") }
    var recovery by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        title = { Text(when (step) {
            is AccountLoginStep.Verification -> stringResource(UiR.string.conn_2fa_dialog_title)
            is AccountLoginStep.Enrollment -> stringResource(UiR.string.conn_2fa_bind_authenticator)
            is AccountLoginStep.RecoveryCodes -> stringResource(UiR.string.conn_2fa_save_recovery_code)
            AccountLoginStep.None -> ""
        }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                when (step) {
                    is AccountLoginStep.Verification -> {
                        Text(if (recovery) stringResource(UiR.string.conn_2fa_recovery_code_prompt) else stringResource(UiR.string.conn_2fa_totp_prompt))
                        TextButton(onClick = { input = ""; recovery = !recovery }) {
                            Text(if (recovery) stringResource(UiR.string.conn_2fa_switch_to_totp) else stringResource(UiR.string.conn_2fa_switch_to_recovery))
                        }
                        OutlinedTextField(value = input, onValueChange = { input = it },
                            label = { Text(if (recovery) stringResource(UiR.string.conn_2fa_recovery_code_label) else stringResource(UiR.string.conn_2fa_totp_label)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = if (recovery) KeyboardType.Password else KeyboardType.NumberPassword))
                        step.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    is AccountLoginStep.Enrollment -> {
                        Text(stringResource(UiR.string.conn_2fa_bind_instruction))
                        Text(step.provisioningUri)
                        step.secret?.let { Text(it) }
                        OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text(stringResource(UiR.string.conn_2fa_totp_label)) },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        step.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    is AccountLoginStep.RecoveryCodes -> {
                        Text(stringResource(UiR.string.conn_2fa_recovery_instruction))
                        step.codes.forEach { Text(it) }
                    }
                    AccountLoginStep.None -> Unit
                }
            }
        },
        confirmButton = {
            if (step is AccountLoginStep.RecoveryCodes) {
                TextButton(onClick = onCodesSaved) { Text(stringResource(UiR.string.conn_2fa_saved_confirm)) }
            } else {
                TextButton(enabled = input.isNotBlank(), onClick = {
                    val proof = input.toCharArray()
                    input = ""
                    onProof(proof, step is AccountLoginStep.Verification && recovery)
                }) { Text(stringResource(UiR.string.common_action_verify)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(UiR.string.common_action_cancel)) } },
    )
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
                        contentDescription = if (passwordVisible) stringResource(UiR.string.common_accessibility_hide_password) else stringResource(UiR.string.common_accessibility_show_password),
                    )
                }
            }
        } else if (onClear != null && value.isNotEmpty()) {
            {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(UiR.string.common_accessibility_clear_text),
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
            text = stringResource(UiR.string.conn_status_not_connected_gateway),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConnectionPhase.Initializing -> PhaseProgressRow(text = stringResource(UiR.string.conn_status_connecting_core))

        ConnectionPhase.Reconnecting -> PhaseProgressRow(text = stringResource(UiR.string.conn_status_reconnecting_core))

        ConnectionPhase.Negotiating -> PhaseProgressRow(text = stringResource(UiR.string.conn_status_connecting))

        ConnectionPhase.Authenticating -> PhaseProgressRow(text = stringResource(UiR.string.conn_status_verifying_credentials))

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
            Text(stringResource(UiR.string.conn_status_offline_mode_hint), style = MaterialTheme.typography.bodySmall)
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
                text = stringResource(UiR.string.conn_status_connected_user_format, phase.username),
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
                    Text(stringResource(UiR.string.common_action_retry), color = MaterialTheme.colorScheme.onErrorContainer)
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
                text = stringResource(UiR.string.conn_security_http_warning_part1) +
                    stringResource(UiR.string.conn_security_http_warning_part2),
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
                TransportSecurity.PLAINTEXT -> stringResource(UiR.string.conn_security_badge_unencrypted)
                TransportSecurity.TLS_PINNED -> stringResource(UiR.string.conn_security_badge_tls_pinned)
                TransportSecurity.TLS_SYSTEM_TRUST -> stringResource(UiR.string.conn_security_badge_system_ca)
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
