package com.openandroidintelligence.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openandroidintelligence.conversation.data.remote.RemoteModelSelection
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.conversation.voice.*
import com.openandroidintelligence.conversation.workbench.WorkbenchScreen
import com.openandroidintelligence.mobile.settings.appearance.AppearanceSettingsScreen
import com.openandroidintelligence.ui.resources.R as UiR
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 保留现有工作台与系统附件选择器，只把连接入口接到 Remote。 */
@Composable
internal fun RemoteWorkbenchRoot(app: OpenAndroidIntelligenceApplication, sharedAttachments: SharedConversationAttachments,
    pendingReply: MutableState<ReplyNotificationTarget?>, pendingHandoff: MutableState<String?>) {
    val runtime = app.remoteRuntime
    val appearance by app.appearancePreferences.settings.collectAsState()
    val dark = when (appearance.theme) { ThemePreference.LIGHT -> false; ThemePreference.DARK -> true; ThemePreference.SYSTEM -> isSystemInDarkTheme() }
    val controller by runtime.controller.collectAsState()
    val connection by runtime.state.collectAsState()
    val onboarding by app.onboardingPreferences.state.collectAsState()
    var settings by rememberSaveable { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun notice(text: String) { uiScope.launch { snackbar.showSnackbar(text) } }
    val picks = rememberConversationAttachmentPickers(controller) { runtime.controller.value === it }
    val avatar = rememberRemoteAccountAvatarPicker(runtime)
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        runtime.replyNotifications.refreshPermission()
    }
    LaunchedEffect(connection.account) {
        runtime.replyNotifications.refreshPermission()
        if (connection.account != null && runtime.replyNotifications.enabled.value && runtime.replyNotifications.shouldRequestPermission()) {
            runtime.replyNotifications.markPermissionRequested()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val activity = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity
    LaunchedEffect(dark) { activity.applySystemBarContrast(dark) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val voiceTarget = remember { AtomicReference<Pair<WorkbenchController, String?>?>(null) }
    val sendingTarget = remember { AtomicReference<Pair<WorkbenchController, String?>?>(null) }
    var permissionTarget by remember { mutableStateOf<Pair<WorkbenchController, String?>?>(null) }
    // start() 的失败与交付阶段的失败是两种原因，提示沿用原来的两句话：
    // 「无法开始录音」发生在按下麦克风的同一帧，交付失败则是之后的后台写文件。
    val startFailure = remember { AtomicBoolean(false) }
    val recorder = remember(app) {
        VoiceRecorder.create(
            context = app,
            onRecorded = { voice, sessionKey ->
                // 交付在后台线程回调；真正的发送与提示回到主线程，
                // 并且必须仍停留在录音所属的对话，绝不把语音发到别的会话。
                // 组合已经销毁（作用域已取消）时任务体不会执行，必须在这里
                // 自己关闭临时音频，否则缓存目录会留一份没人释放的录音。
                if (uiScope.coroutineContext[Job]?.isActive != true) {
                    voice.close()
                    sendingTarget.set(null)
                    return@create
                }
                // 在回调线程就取走目标：交付收尾会清空 sendingTarget，晚一步就丢了。
                val target = sendingTarget.get()
                uiScope.launch {
                    try {
                        if (target == null) {
                            notice(app.getString(UiR.string.remote_voice_lost))
                        } else if (runtime.controller.value !== target.first) {
                            notice(app.getString(UiR.string.remote_voice_target_changed))
                        } else if (target.first.state.value.activeThreadId != sessionKey) {
                            notice(app.getString(UiR.string.remote_voice_target_changed))
                        } else if (!target.first.sendVoiceAttachment(voice.selection(), target.second)) {
                            notice(app.getString(UiR.string.remote_voice_not_sent))
                        }
                    } finally {
                        voice.close()
                        sendingTarget.compareAndSet(target, null)
                    }
                }
            },
            onRejected = { rejection ->
                // ALREADY_SENDING 说明上一段仍在这条交付路径上，目标不能被清掉；
                // 其余拒绝都意味着没有在途录音，顺手清掉目标，避免永久挡住下一次发送。
                if (rejection != VoiceRejection.ALREADY_SENDING) sendingTarget.set(null)
                val message = when {
                    rejection != VoiceRejection.RECORDING_FAILED ->
                        voiceRejectionMessage(rejection)
                    startFailure.getAndSet(false) ->
                        app.getString(UiR.string.perm_audio_error_start_failed)
                    else -> app.getString(UiR.string.remote_voice_failed)
                }
                uiScope.launch { notice(message) }
            },
        )
    }
    DisposableEffect(recorder) {
        onDispose { recorder.close() }
    }
    val voiceState by recorder.state.collectAsState()
    val activeRecording = voiceState.activeRecording()
    // 只有真的取消了一段录音才清掉发送目标：正在写文件/发送时的 cancel() 返回
    // false，交付仍要落到原来那条对话上，不能被这里顺手清空成"录音已丢失"。
    val cancelRecording: () -> Unit = {
        if (recorder.cancel()) voiceTarget.set(null)
    }
    val finishRecording: () -> Unit = {
        // 上一段仍在交付时 send() 会拒绝这一次；此时不能动 sendingTarget，
        // 否则会把正在交付的音频指向错误的对话。
        if (sendingTarget.get() == null) {
            val target = voiceTarget.getAndSet(null)
            // 目标必须在 send() 之前登记：交付回调可能在后台线程立刻到达，
            // 写在 send() 之后会留下"回调先到、目标还是空的"窗口。
            sendingTarget.set(target)
            val accepted = recorder.send {
                runtime.controller.value?.state?.value?.activeThreadId
            }
            // 被拒绝（未在录音 / 会话已切换）时录音器已回到 Idle，目标一并清掉。
            if (!accepted) sendingTarget.compareAndSet(target, null)
        }
    }
    val beginRecording: (Pair<WorkbenchController, String?>) -> Unit = { target ->
        if (runtime.controller.value !== target.first || target.first.state.value.activeThreadId != target.second) {
            notice(app.getString(UiR.string.remote_voice_restart_after_switch))
        } else if (!target.first.canSendVoiceAttachment(target.second)) {
            notice(app.getString(UiR.string.remote_voice_wait))
        } else if (voiceState is VoiceRecorderState.Sending) {
            notice(voiceRejectionMessage(VoiceRejection.ALREADY_SENDING))
        } else if (voiceState is VoiceRecorderState.Idle) {
            voiceTarget.set(target)
            // 失败原因统一由 onRejected 给出，这里不重复提示；
            // startFailure 让那条回调区分「起不来」与「交付失败」两种原因。
            startFailure.set(true)
            val started = recorder.start(target.second)
            // start() 失败时 onRejected 已在调用内消费掉这个标记；成功后也要复位，
            // 否则之后一次正常的录制失败会被误报成「无法开始录音」。
            startFailure.set(false)
            if (!started) voiceTarget.compareAndSet(target, null)
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = permissionTarget
        permissionTarget = null
        if (granted && target != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            beginRecording(target)
        } else if (!granted) {
            notice(app.getString(UiR.string.perm_audio_required))
        }
    }
    val startVoiceInput: () -> Unit = {
        val active = controller
        if (active == null) {
            notice(app.getString(UiR.string.remote_conversation_not_ready))
        } else if (!active.canSendVoiceAttachment(active.state.value.activeThreadId)) {
            notice(app.getString(UiR.string.remote_voice_wait))
        } else if (voiceState is VoiceRecorderState.Sending) {
            notice(voiceRejectionMessage(VoiceRejection.ALREADY_SENDING))
        } else if (voiceState is VoiceRecorderState.Idle && permissionTarget == null) {
            val target = active to active.state.value.activeThreadId
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                beginRecording(target)
            } else {
                permissionTarget = target
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    LaunchedEffect(controller) {
        val active = controller ?: return@LaunchedEffect
        active.state.collect { value -> if (recorder.onSessionChanged(value.activeThreadId)) voiceTarget.set(null) }
    }
    DisposableEffect(lifecycle, recorder) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { permissionTarget = null; cancelRecording() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); permissionTarget = null; cancelRecording() }
    }
    LaunchedEffect(runtime) { runtime.restoreSessionIfAvailable() }
    LaunchedEffect(connection.errorCode) { connection.errorCode?.let { notice(it) } }
    val shared by sharedAttachments.pending.collectAsState()
    LaunchedEffect(controller, shared) {
        controller?.let { active -> active.state.collect { sharedAttachments.deliverTo(active, picks.receive) } }
    }
    LaunchedEffect(runtime, connection.account, pendingReply.value) {
        pendingReply.value?.let { if (runtime.openReplyNotification(it)) pendingReply.value = null }
    }
    LaunchedEffect(controller, pendingHandoff.value) {
        pendingHandoff.value?.let { if (runtime.consumeAssistantHandoff(it)) pendingHandoff.value = null }
    }
    OpenAndroidIntelligenceTheme(darkTheme = dark, dynamicColor = appearance.dynamicColor, palette = appearance.palette,
        reduceMotion = appearance.reduceMotion, streamingHapticFeedback = appearance.streamingHapticFeedback) {
        Box(Modifier.fillMaxSize()) {
        if (onboarding.shouldShow) {
            com.openandroidintelligence.mobile.onboarding.OnboardingFlow(onboarding, appearance, app.appearancePreferences,
                app.onboardingPreferences::setStage, app.onboardingPreferences::setLastGateway,
                onFinished = app.onboardingPreferences::markCompleted,
                onExit = {
                    if (onboarding.replay) app.onboardingPreferences.endReplay()
                    else { app.onboardingPreferences.dismissForCurrentSession(); activity.finish() }
                }, pluginPrimitives = app.pluginHost.verifiedCapabilities().flatMap { it.primitives }.toSet(), modifier = Modifier.fillMaxSize())
        } else {
        val current = controller
        if (current != null) WorkbenchScreen(current, connection.selected?.origin?.value.orEmpty(),
            onOpenSettings = { settings = true }, onPickCamera = picks.camera, onPickGallery = picks.gallery,
            onPickDocument = picks.document,
            onVoiceInput = startVoiceInput, recording = activeRecording,
            onCancelRecording = cancelRecording, onFinishRecording = finishRecording,
            isDarkTheme = dark, onToggleTheme = { app.appearancePreferences.setTheme(if (dark) ThemePreference.LIGHT else ThemePreference.DARK) },
            onLogout = { runtime.logout() }, onPasteImages = picks.paste,
            accountName = connection.account?.accountId.orEmpty(), agentName = runtime.connectedAgentName,
            accountAvatar = avatar.image, onChangeAvatar = avatar.pick,
            pluginCards = { com.openandroidintelligence.mobile.plugins.ProtectedConversationCards(app.pluginHost,
                runtime.connectedAccountId) { settings = true } },
            settingsOpen = settings, onSettingsOpenChange = { settings = it },
            settingsPanel = { close -> RemoteSettings(app, close) }, header = { RemoteModelSelector(runtime) })
        else RemoteLoginScreen(runtime, connection.busy, onOpenSettings = { settings = true })
        if (current == null && settings) Surface(Modifier.fillMaxSize()) { RemoteSettings(app) { settings = false } }
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).safeDrawingPadding())
        }
    }
}

@Composable
private fun RemoteLoginScreen(runtime: RemoteRuntime, busy: Boolean, onOpenSettings: () -> Unit) {
    val state by runtime.state.collectAsState()
    var origin by rememberSaveable { mutableStateOf("https://dsh.r2049.cn") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(UiR.string.remote_connection_title), style = MaterialTheme.typography.headlineMedium)
        if (state.account == null) {
            OutlinedTextField(origin, { origin = it }, label = { Text(stringResource(UiR.string.remote_service_address)) }, enabled = !busy, singleLine = true)
            OutlinedTextField(email, { email = it }, label = { Text(stringResource(UiR.string.remote_account_email)) }, enabled = !busy, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(UiR.string.remote_account_password)) }, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Button(onClick = { runtime.login(origin, email, password); password = "" }, enabled = !busy && email.isNotBlank() && password.isNotBlank()) {
                Text(stringResource(UiR.string.remote_login))
            }
        } else RemoteEndpointList(runtime)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.errorCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onOpenSettings) { Text(stringResource(UiR.string.common_action_settings)) }
    }
}

@Composable
internal fun RemoteEndpointList(runtime: RemoteRuntime) {
    val state by runtime.state.collectAsState()
    Text(stringResource(UiR.string.remote_hosts), style = MaterialTheme.typography.titleMedium)
    TextButton(onClick = { runtime.refreshHosts() }, enabled = !state.busy) { Text(stringResource(UiR.string.remote_refresh_hosts)) }
    state.hosts.forEach { host ->
        OutlinedButton(onClick = { runtime.discover(host) }, enabled = !state.busy && host.online, modifier = Modifier.fillMaxWidth()) {
            Text(host.name + " · " + stringResource(if (host.online) UiR.string.common_status_online else UiR.string.common_status_offline))
        }
    }
    Text(stringResource(UiR.string.remote_workspaces), style = MaterialTheme.typography.titleMedium)
    state.endpoints.forEach { endpoint ->
        TextButton(onClick = { runtime.select(endpoint) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(endpoint.endpoint.displayName)
        }
    }
    state.backendFailures.forEach { (backend, code) -> Text("$backend · $code", color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun RemoteModelSelector(runtime: RemoteRuntime) {
    val state by runtime.state.collectAsState()
    val models = state.models ?: return
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Box {
            TextButton(onClick = { expanded = true }, enabled = !state.modelBusy) {
                Text(models.models.firstOrNull { it.key == models.selected?.key }?.displayName ?: stringResource(UiR.string.remote_select_model))
            }
            DropdownMenu(expanded, { expanded = false }) {
                models.models.forEach { model -> DropdownMenuItem(text = { Text(model.displayName) }, onClick = {
                    expanded = false; runtime.selectModel(RemoteModelSelection(model.key))
                }) }
            }
        }
        val selected = models.selected
        if (selected != null) {
            var efforts by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { efforts = true }, enabled = !state.modelBusy) { Text(selected.effortId ?: stringResource(UiR.string.remote_select_effort)) }
                DropdownMenu(efforts, { efforts = false }) {
                    models.efforts[selected.key].orEmpty().forEach { effort -> DropdownMenuItem(text = { Text(effort.displayName) }, onClick = {
                        efforts = false; runtime.selectModel(selected.copy(effortId = effort.id))
                    }) }
                }
            }
        }
    }
}

@Composable
private fun RemoteSettings(app: OpenAndroidIntelligenceApplication, onBack: () -> Unit) {
    SettingsScreen(app.platformSettingsEnvironment(), onBack = onBack, runtime = null, remoteRuntime = app.remoteRuntime,
        presentation = SettingsPresentation.SIDEBAR)
}

@Composable
internal fun RemoteConnectionSettingsScreen(runtime: RemoteRuntime, presentation: SettingsPresentation, onBack: () -> Unit) {
    val client by runtime.accountRuntime.state.collectAsState()
    val host by runtime.accountRuntime.hostState.collectAsState()
    com.openandroidintelligence.mobile.settings.SettingsPageScaffold(stringResource(UiR.string.remote_connection_title), presentation, onBack) { padding ->
        com.openandroidintelligence.mobile.settings.SettingsPageColumn(padding) {
            com.openandroidintelligence.conversation.components.SettingsSectionCard {
                com.openandroidintelligence.conversation.components.SettingsListItem(stringResource(UiR.string.remote_client_role), supporting = when (client) {
                    com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState.SIGNED_OUT -> stringResource(UiR.string.remote_role_signed_out)
                    com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState.READY -> stringResource(UiR.string.remote_role_ready)
                    com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState.CONNECTING -> stringResource(UiR.string.remote_role_connecting)
                    com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState.CONNECTED -> stringResource(UiR.string.remote_role_connected)
                    com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState.DISCONNECTED -> stringResource(UiR.string.remote_role_disconnected)
                })
                com.openandroidintelligence.conversation.components.SettingsListItem(stringResource(UiR.string.remote_host_role), supporting = when (host) {
                    com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState.STOPPED -> stringResource(UiR.string.remote_role_stopped)
                    com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState.CONNECTING -> stringResource(UiR.string.remote_role_connecting)
                    com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState.ONLINE -> stringResource(UiR.string.remote_role_online)
                    com.openandroidintelligence.gateway.remote.runtime.RemoteHostConnectionState.FAILED -> stringResource(UiR.string.remote_role_failed)
                })
            }
            RemoteEndpointList(runtime)
            TextButton(onClick = { runtime.logout() }) { Text(stringResource(UiR.string.common_action_logout)) }
        }
    }
}
