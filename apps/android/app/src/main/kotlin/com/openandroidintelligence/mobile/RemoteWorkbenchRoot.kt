package com.openandroidintelligence.mobile

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openandroidintelligence.conversation.data.remote.RemoteModelSelection
import com.openandroidintelligence.conversation.motion.AppSharedTransitionHost
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.motion.ProvideSharedVisibilityScope
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalWindowMaxWidth
import com.openandroidintelligence.conversation.voice.*
import com.openandroidintelligence.conversation.workbench.WorkbenchScreen
import com.openandroidintelligence.conversation.workbench.WorkspaceConversationGroup
import com.openandroidintelligence.conversation.workbench.WorkspaceMenuState
import com.openandroidintelligence.mobile.settings.appearance.AppearanceSettingsScreen
import com.openandroidintelligence.ui.resources.R as UiR
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 保留现有工作台与系统附件选择器，只把连接入口接到 Remote。 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun RemoteWorkbenchRoot(app: OpenAndroidIntelligenceApplication, sharedAttachments: SharedConversationAttachments,
    pendingReply: MutableState<ReplyNotificationTarget?>, pendingHandoff: MutableState<String?>) {
    val runtime = app.remoteRuntime
    val loginEntry = rememberRemoteLoginEntryBridge(runtime)
    val loginEntryState by loginEntry.state.collectAsState()
    val appearance by app.appearancePreferences.settings.collectAsState()
    val dark = when (appearance.theme) { ThemePreference.LIGHT -> false; ThemePreference.DARK -> true; ThemePreference.SYSTEM -> isSystemInDarkTheme() }
    val controller by runtime.controller.collectAsState()
    val clientConnection by runtime.accountRuntime.state.collectAsState()
    val agentConn by runtime.agentConnection.collectAsState()
    val workspaceCtx by runtime.workspaceContext.collectAsState()
    val onboarding by app.onboardingPreferences.state.collectAsState()
    var settings by rememberSaveable { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun notice(text: String) { uiScope.launch { snackbar.showSnackbar(text, withDismissAction = true) } }
    val picks = rememberConversationAttachmentPickers(controller) { runtime.controller.value === it }
    val avatar = rememberRemoteAccountAvatarPicker(runtime)
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        runtime.replyNotifications.refreshPermission()
    }
    val activeAccount = agentConn.account
    LaunchedEffect(activeAccount) {
        runtime.replyNotifications.refreshPermission()
        if (activeAccount != null && runtime.replyNotifications.enabled.value && runtime.replyNotifications.shouldRequestPermission()) {
            runtime.replyNotifications.markPermissionRequested()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val activity = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity
    val requestScreenCaptureAuthorization = com.openandroidintelligence.mobile.screen.rememberScreenCaptureAuthorization(app, activity, ::notice)
    val pluginRevision by app.pluginHost.revision.collectAsState()
    val nativeUi = remember(pluginRevision) { app.pluginHost.nativeUiProvider() }
    var displayedNativeUi by remember { mutableStateOf(nativeUi) }
    LaunchedEffect(nativeUi, settings) {
        if (!settings) displayedNativeUi = nativeUi
    }
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
                    } catch (t: Throwable) {
                        notice(app.getString(UiR.string.remote_voice_not_sent))
                    } finally {
                        voice.close()
                        sendingTarget.set(null)
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
            if (!accepted) sendingTarget.set(null)
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
            uiScope.launch { showRemoteAudioPermissionDenied(activity, snackbar) }
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
    ReceivePendingConversationShares(sharedAttachments, controller, picks.receive) { settings = false }
    RemotePendingConversationNavigation(
        agentConn.account, controller, pendingReply, pendingHandoff,
        openReply = runtime::openReplyNotification,
        consumeHandoff = runtime::consumeAssistantHandoff,
        onOpened = { settings = false; activity.intent.data = null },
    )
    OpenAndroidIntelligenceTheme(darkTheme = dark, dynamicColor = appearance.dynamicColor, palette = appearance.palette,
        reduceMotion = appearance.reduceMotion, streamingHapticFeedback = appearance.streamingHapticFeedback) {
        val reduceMotion = LocalMotionPolicy.current.isReduced
        AppSharedTransitionHost(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize()) {
                    if (onboarding.shouldShow) {
                        com.openandroidintelligence.mobile.onboarding.OnboardingFlow(onboarding, appearance, app.appearancePreferences,
                            app.onboardingPreferences::setStage, app.onboardingPreferences::setLastGateway,
                            onFinished = app.onboardingPreferences::markCompleted,
                            onExit = {
                                if (onboarding.replay) app.onboardingPreferences.endReplay()
                                else { app.onboardingPreferences.dismissForCurrentSession(); activity.finish() }
                            }, pluginPrimitives = app.pluginHost.verifiedCapabilities().flatMap { it.primitives }.toSet(), modifier = Modifier.fillMaxSize())
                    } else AnimatedContent(
                        targetState = displayedNativeUi,
                        transitionSpec = {
                            fadeIn(MotionSpecs.fade(reduceMotion)) togetherWith fadeOut(MotionSpecs.fade(reduceMotion))
                        },
                        label = "native-plugin-surface",
                    ) { activeNative ->
                        ProvideSharedVisibilityScope {
                            if (activeNative != null) {
                                PlatformSettingsDrawer(
                                    open = settings && displayedNativeUi == activeNative,
                                    onDismiss = { settings = false },
                                    settingsContent = { presentation, close ->
                                        RemoteSettings(
                                            app,
                                            requestScreenCaptureAuthorization,
                                            presentation,
                                            close,
                                            hostOpen = settings && displayedNativeUi == activeNative,
                                        )
                                    },
                                ) {
                                    RemoteNativePluginSurface(app.pluginHost, activeNative, controller,
                                        workspaceCtx.activeWorkspace?.origin?.value.orEmpty()) { settings = true }
                                }
                            } else AnimatedContent(
                                targetState = controller != null,
                                transitionSpec = {
                                    AppTransitions.enter(reduceMotion, forward = targetState) togetherWith
                                        AppTransitions.exit(reduceMotion, forward = targetState)
                                },
                                label = "root-surface",
                            ) { onWorkbench ->
                                ProvideSharedVisibilityScope {
                                    val current = controller
                                    if (!onWorkbench || current == null) {
                                        RemoteLoginSettingsHost(
                                            busy = agentConn.busy || loginEntryState.busy,
                                            connected = remoteWorkbenchConnected(null, clientConnection,
                                                agentConn.busy || loginEntryState.busy),
                                            signedIn = agentConn.account != null && agentConn.phase != AgentConnectionPhase.AUTH_REVOKED,
                                            initialOrigin = runtime.loginOrigin(),
                                            error = loginEntryState.errorCode ?: agentConn.errorCode,
                                            settingsOpen = settings && controller == null && displayedNativeUi == null,
                                            onSettingsOpenChange = { settings = it },
                                            onLogin = { origin, account, secret -> runtime.login(origin, account, secret) },
                                            onScanPairing = loginEntry::scanPairing,
                                            onRetry = { runtime.dismissConnectionError(); loginEntry.dismissError() },
                                            onOAuthLogin = { loginEntry.startOAuth(activity, RemoteOAuthProvider.valueOf(it.uppercase())) },
                                            settingsContent = { presentation, close ->
                                                RemoteSettings(
                                                    app,
                                                    requestScreenCaptureAuthorization,
                                                    presentation,
                                                    close,
                                                    hostOpen = settings && controller == null && displayedNativeUi == null,
                                                )
                                            },
                                            endpointContent = { RemoteLoginAgentSelector(runtime) },
                                        )
                                    } else WorkbenchSettingsHost { presentation ->
                                        val workbenchThreads by remember(current) {
                                            current.state.map { it.threads }.distinctUntilChanged()
                                        }.collectAsState(initial = Loadable.Idle)
                                        val workspacesByMenuKey = remember(workspaceCtx.workspaces) {
                                            workspaceCtx.workspaces.associateBy { runtime.endpointId(it.endpoint.key) }
                                        }
                                        val workspaceMenu = remember(
                                            agentConn.activeAgentName,
                                            workspaceCtx,
                                            workbenchThreads,
                                            workspacesByMenuKey,
                                        ) {
                                            WorkspaceMenuState(
                                                agentTitle = agentConn.activeAgentName,
                                                groups = workspaceCtx.workspaces.map { ws ->
                                                    val menuKey = runtime.endpointId(ws.endpoint.key)
                                                    val isActive = ws.endpoint.key == workspaceCtx.activeWorkspace
                                                    val shortTitle = ws.endpoint.displayName.substringAfterLast(" · ")
                                                        .ifBlank { ws.endpoint.displayName }
                                                    val threads = if (isActive && workbenchThreads != Loadable.Idle) {
                                                        workbenchThreads
                                                    } else {
                                                        workspaceCtx.sessionsByWorkspace[ws.endpoint.key] ?: Loadable.Idle
                                                    }
                                                    WorkspaceConversationGroup(
                                                        workspaceKey = menuKey,
                                                        title = shortTitle,
                                                        subtitle = ws.path.takeIf { it.isNotBlank() },
                                                        isActive = isActive,
                                                        threads = threads,
                                                    )
                                                },
                                                catalogLoading = workspaceCtx.catalogPhase == WorkspaceCatalogPhase.LOADING,
                                                catalogError = workspaceCtx.catalogPhase == WorkspaceCatalogPhase.FAILED,
                                                onHydrate = { runtime.hydrateWorkspaces(force = false) },
                                                onRefreshCatalog = { runtime.hydrateWorkspaces(force = true) },
                                                onForceRefresh = { runtime.forceRefreshActiveConnection() },
                                                onOpenWorkspaceThread = { wsKey, threadId ->
                                                    workspacesByMenuKey[wsKey]?.let { runtime.selectWorkspace(it, initialThreadId = threadId) }
                                                },
                                                onCreateWorkspaceThread = { wsKey ->
                                                    workspacesByMenuKey[wsKey]?.let { runtime.createSessionInWorkspace(it) }
                                                },
                                            )
                                        }
                                        var dismissedError by remember(agentConn.errorCode) { mutableStateOf(false) }
                                        val mappedHealth = when (agentConn.phase) {
                                            AgentConnectionPhase.CONNECTING -> StreamHealth.CONNECTING
                                            AgentConnectionPhase.RECONNECTING -> StreamHealth.RECONNECTING
                                            AgentConnectionPhase.OFFLINE, AgentConnectionPhase.AUTH_REVOKED -> StreamHealth.FAILED
                                            AgentConnectionPhase.CONNECTED -> StreamHealth.LIVE
                                            AgentConnectionPhase.DISCONNECTED -> null
                                        }
                                        val offlineBannerVisible = agentConn.offlineConfirmed &&
                                            (agentConn.phase == AgentConnectionPhase.OFFLINE || agentConn.phase == AgentConnectionPhase.RECONNECTING)
                                        val hasWorkspaceSessions = workspaceCtx.activeWorkspace?.let {
                                            workspaceCtx.sessionsByWorkspace[it] is Loadable.Ready
                                        } == true
                                        val sessionReady = workspaceCtx.activeWorkspace != null &&
                                            (workbenchThreads is Loadable.Ready || hasWorkspaceSessions)
                                        val bannerError = resolveWorkbenchBannerError(
                                            agentConn = agentConn,
                                            offlineBannerVisible = offlineBannerVisible,
                                            dismissedError = dismissedError,
                                            sessionReady = sessionReady,
                                        )
                                        val workbenchSettingsOpen = settings && controller != null && displayedNativeUi == null
                                        WorkbenchScreen(current, workspaceCtx.activeWorkspace?.origin?.value.orEmpty(),
                                            onOpenSettings = { settings = true }, onPickCamera = picks.camera, onPickGallery = picks.gallery,
                                            onPickDocument = picks.document,
                                            onVoiceInput = startVoiceInput, recording = activeRecording,
                                            onCancelRecording = cancelRecording, onFinishRecording = finishRecording,
                                            isDarkTheme = dark,
                                            onToggleTheme = { app.appearancePreferences.setTheme(if (dark) ThemePreference.LIGHT else ThemePreference.DARK) },
                                            onLogout = { runtime.logout() }, onPasteImages = picks.paste,
                                            accountName = agentConn.account?.accountId.orEmpty(), agentName = runtime.connectedAgentName,
                                            accountAvatar = avatar.image, onChangeAvatar = avatar.pick,
                                            connectionHealth = mappedHealth,
                                            pluginCards = { com.openandroidintelligence.mobile.plugins.ProtectedConversationCards(app.pluginHost,
                                                runtime.connectedAccountId) { settings = true } },
                                            settingsOpen = workbenchSettingsOpen,
                                            onSettingsOpenChange = { settings = it },
                                            settingsPanel = { close -> RemoteSettings(app, requestScreenCaptureAuthorization, presentation, close, hostOpen = workbenchSettingsOpen) },
                                            workspaceMenu = workspaceMenu,
                                            availableWidthOverride = LocalWindowMaxWidth.current,
                                            header = {
                                                RemoteWorkbenchNotices(
                                                    offline = offlineBannerVisible,
                                                    error = bannerError,
                                                    onRetry = { runtime.reconnectInPlace() },
                                                    onDismiss = {
                                                        dismissedError = true
                                                        runtime.dismissConnectionError()
                                                    },
                                                    reconnecting = agentConn.reconnectingInPlace,
                                                    phase = agentConn.phase,
                                                    sessionReady = sessionReady,
                                                )
                                                RemoteModelSelector(runtime)
                                            })
                                    }
                                }
                            }
                        }
                    }
                    RemotePairingDialog(loginEntry)
                    SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding()
                        .padding(start = Dimensions.ScreenHorizontal, end = Dimensions.ScreenHorizontal,
                            bottom = if (controller != null) Dimensions.ComposerPrimaryAction + Dimensions.SpaceMedium else Dimensions.SpaceMedium))
                }
            }
        }
    }
}

@Composable
private fun RemoteModelSelector(runtime: RemoteRuntime) {
    val workspace by runtime.workspaceContext.collectAsState()
    val models = workspace.models ?: return
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Box {
            TextButton(onClick = { expanded = true }, enabled = !workspace.modelBusy) {
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
                TextButton(onClick = { efforts = true }, enabled = !workspace.modelBusy) { Text(selected.effortId ?: stringResource(UiR.string.remote_select_effort)) }
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
private fun RemoteSettings(
    app: OpenAndroidIntelligenceApplication,
    requestScreenCaptureAuthorization: () -> Unit,
    presentation: SettingsPresentation = SettingsPresentation.SIDEBAR,
    onBack: () -> Unit,
    hostOpen: Boolean = true,
) {
    SettingsScreen(app.platformSettingsEnvironment(), onBack = onBack, runtime = app.remoteRuntime.localSettings, remoteRuntime = app.remoteRuntime,
        presentation = presentation, onRequestScreenCaptureAuthorization = requestScreenCaptureAuthorization, hostOpen = hostOpen)
}

/** 本机选中的原生全屏界面继续使用原宿主外壳与当前工作台操作。 */
@Composable
internal fun RemoteNativePluginSurface(
    host: com.openandroidintelligence.mobile.plugins.ProductionPluginHost,
    nativeUi: Pair<String, com.openandroidintelligence.plugin.ui.NativeUiProvider>,
    controller: WorkbenchController?,
    origin: String,
    openSettings: () -> Unit,
) {
    val workbench = controller?.state?.collectAsState()?.value
    com.openandroidintelligence.mobile.plugins.NativePluginSurface(
        host, nativeUi.first, nativeUi.second,
        com.openandroidintelligence.plugin.ui.NativeUiContext(origin, workbench?.activeThreadId,
            workbench?.activeThreadTitle.orEmpty(), workbench?.draft.orEmpty(),
            (workbench?.timeline as? com.openandroidintelligence.conversation.state.Loadable.Ready)?.value.orEmpty()
                .map { com.openandroidintelligence.plugin.ui.NativeUiMessage(it.key, it.sender, it.text, it.isStreaming) },
            { controller?.editDraft(it) }, { controller?.sendDraft() }, { controller?.stopGeneration() }, openSettings),
    )
}
