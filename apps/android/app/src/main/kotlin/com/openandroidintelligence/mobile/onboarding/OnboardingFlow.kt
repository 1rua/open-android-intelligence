package com.openandroidintelligence.mobile.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials
import com.openandroidintelligence.mobile.onboarding.motion.ButtonRevealTransition
import com.openandroidintelligence.mobile.onboarding.motion.RevealOrigin
import com.openandroidintelligence.mobile.onboarding.motion.RevealPhase
import com.openandroidintelligence.mobile.onboarding.motion.RevealDurationMillis as RevealDurationMillisOfMotion
import com.openandroidintelligence.mobile.onboarding.screens.DeploymentChoiceScreen
import com.openandroidintelligence.mobile.onboarding.screens.GatewayGuideScreen
import com.openandroidintelligence.mobile.onboarding.screens.PermissionsScreen
import com.openandroidintelligence.mobile.onboarding.screens.WelcomeScreen
import com.openandroidintelligence.mobile.plugins.NOTIFICATION_PLUGIN_ID
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.launch

/**
 * 首次启动引导的流程容器。
 *
 * 职责边界：
 * 1. **只负责引导流程本身**：阶段切换、返回键、权限状态查询、以及最后一段转场动画。
 * 2. **不碰登录**：流程结束后调用 [onFinished]，由宿主继续走既有的登录 / 工作台分支。
 *    这样登录、配对、会话逻辑一行都不用改（需求五.5、六.9）。
 * 3. **不伪造状态**：完成引导只写「用户走完了引导」，与 Gateway 是否连接完全无关。
 *
 * @param onFinished 用户点下「我已部署」或「部署完毕」并完成转场后回调。
 *   宿主据此把引导标记为完成，并显示原有的登录界面。
 * @param onExit 用户在欢迎页再按返回时退出引导（不标记完成，下次启动仍会展示）。
 * @param pluginPrimitives 已启用插件声明的 kernel primitive 集合，
 *   用于决定「设备能力」权限卡片是否出现（不存在的能力不展示）。
 */
@Composable
fun OnboardingFlow(
    state: OnboardingState,
    onStageChange: (OnboardingStage) -> Unit,
    onGatewaySelected: (String) -> Unit,
    onFinished: () -> Unit,
    onExit: () -> Unit,
    pluginPrimitives: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val lifecycleOwner = LocalLifecycleOwner.current

    // ---- 权限状态：进入页面时查询一次，回到前台时再查（用户可能去系统设置里改过） ----
    val statuses = remember { mutableStateMapOf<String, PermissionStatus>() }
    fun refreshPermissions() {
        val entries = buildVisiblePermissions(pluginPrimitives)
        entries.forEach { entry ->
            statuses[entry.id] = resolvePermissionStatus(context, entry)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refreshPermissions()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- 运行时权限申请：只由用户点击触发，绝不在进入页面时自动弹 ----
    // 记录本次申请对应的权限 id，结果回来后只更新那一张卡片。
    var pendingPermissionId by remember { mutableStateOf<String?>(null) }
    val singlePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingPermissionId?.let { id ->
            statuses[id] = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }
        pendingPermissionId = null
        refreshPermissions()
    }
    val multiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        results.forEach { (manifestPermission, granted) ->
            val id = manifestPermissionToEntryId(manifestPermission)
            if (id != null) {
                statuses[id] = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
            }
        }
        pendingPermissionId = null
        refreshPermissions()
    }

    val entries = remember(pluginPrimitives) { buildVisiblePermissions(pluginPrimitives) }

    fun requestPermission(id: String) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        pendingPermissionId = id
        when (id) {
            // 屏幕采集不是运行时权限：走 MediaProjection 授权对话框，由业务侧在使用时发起。
            // 引导页只解释机制，不主动拉起投屏授权（那会让用户以为必须现在授权）。
            "screen_capture" -> Unit
            // 通知使用权是特殊权限：只能引导用户去系统设置页开启。
            "notification_access" -> openNotificationAccessSettings(context)
            "sms", "call_log" -> {
                val needed = entry.manifestPermissions
                    .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (needed.isEmpty()) {
                    statuses[id] = PermissionStatus.GRANTED
                } else {
                    notePermissionRequested(needed)
                    multiPermissionLauncher.launch(needed.toTypedArray())
                }
            }
            else -> {
                val permission = entry.manifestPermissions.firstOrNull() ?: return
                notePermissionRequested(listOf(permission))
                singlePermissionLauncher.launch(permission)
            }
        }
    }

    // ---- 转场状态 ----
    var revealPhase by remember { mutableStateOf<RevealPhase>(RevealPhase.Idle) }

    /**
     * 启动「按钮展开」转场。
     *
     * 进度必须**回写到 [RevealPhase]**，否则 [ButtonRevealTransition] 拿到的 progress
     * 会永远停在 0，几何展开一帧都不会动（视觉上只剩一张静止的按钮形状）。
     * 这里由 `Animatable` 逐帧驱动，并在每一帧把当前值写进 phase。
     *
     * 时序：
     * 1. 先置 Expanding(origin, 0f) 让覆盖层立刻以页面同色出现 —— 不产生空白帧；
     * 2. 逐帧推进 progress 到 1，再置 Settled；
     * 3. 最后才通知宿主「引导已完成」，保证 onFinished 收到时画面已经稳定，
     *    不会出现「登录页闪一下又被引导盖住」的抖动。
     */
    fun beginReveal(origin: RevealOrigin) {
        revealPhase = RevealPhase.Expanding(origin, 0f)
        scope.launch {
            val progress = Animatable(0f)
            val spec = if (reduceMotion) {
                tween<Float>(MotionSpecs.Fast)
            } else {
                tween<Float>(
                    durationMillis = RevealDurationMillis,
                    easing = MotionSpecs.EmphasizedEasing,
                )
            }
            progress.animateTo(targetValue = 1f, animationSpec = spec) {
                // 每帧把最新进度写回 phase：这是转场能真正「动起来」的唯一通路。
                revealPhase = RevealPhase.Expanding(origin, value)
            }
            revealPhase = RevealPhase.Settled
            onFinished()
        }
    }

    // 系统返回：流程内逐级回退；欢迎页再按则退出引导。
    BackHandler(enabled = true) {
        val previous = state.previous()
        if (previous != null) onStageChange(previous) else onExit()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            // 阶段之间用统一的淡入淡出：引导页之间是「同一流程内的连续步骤」，
            // 用位移会让人误以为是层级的跳转；只有最后进入登录才用几何展开。
            AnimatedContent(
                targetState = state.stage,
                transitionSpec = {
                    fadeIn(MotionSpecs.emphasized(reduceMotion)) togetherWith
                        fadeOut(MotionSpecs.fade(reduceMotion))
                },
                label = "onboarding-stage",
            ) { stage ->
                when (stage) {
                    OnboardingStage.WELCOME -> WelcomeScreen(
                        onStart = { onStageChange(OnboardingStage.PERMISSIONS) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    OnboardingStage.PERMISSIONS -> PermissionsScreen(
                        entries = entries,
                        statuses = statuses.toMap(),
                        onRequestPermission = ::requestPermission,
                        onOpenSystemSettings = { id ->
                            if (id == "notification_access") {
                                openNotificationAccessSettings(context)
                            } else {
                                openAppDetailsSettings(context)
                            }
                        },
                        onNext = { onStageChange(OnboardingStage.DEPLOYMENT) },
                        onSkipOptional = { onStageChange(OnboardingStage.DEPLOYMENT) },
                        onBack = { onStageChange(OnboardingStage.WELCOME) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    OnboardingStage.DEPLOYMENT -> DeploymentChoiceScreen(
                        onAlreadyDeployed = ::beginReveal,
                        onNotDeployed = { onStageChange(OnboardingStage.GUIDE) },
                        onBack = { onStageChange(OnboardingStage.PERMISSIONS) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    OnboardingStage.GUIDE -> GatewayGuideScreen(
                        selectedGatewayId = state.lastGatewayId
                            ?: GatewayTutorials.ALL.first().id,
                        onSelectGateway = onGatewaySelected,
                        onDone = ::beginReveal,
                        onBack = { onStageChange(OnboardingStage.DEPLOYMENT) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // 已完成后不应再渲染任何引导页；宿主会立刻切到登录。
                    OnboardingStage.COMPLETED -> Unit
                }
            }
        }

        // 转场覆盖层：只有 Expanding / Settled 时才参与绘制。
        // 内容是引导页本身（不是登录页）的淡出 —— 登录页由宿主在 onFinished 后接管，
        // 因此这里只需要把引导页「化掉」，扩展面本身与页面同色，不会产生空白帧。
        if (revealPhase !is RevealPhase.Idle) {
            ButtonRevealTransition(
                phase = revealPhase,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 转场期间叠一层与页面同色的表面，随展开进度淡出引导页残留。
                // 进度从 phase 里取（唯一的进度真相源），不再另存一份。
                val expandingProgress = (revealPhase as? RevealPhase.Expanding)?.progress ?: 1f
                FadingOnboardingSurface(
                    progress = expandingProgress,
                    reduceMotion = reduceMotion,
                )
            }
        }
    }
}

/**
 * 页面级转场时长。
 *
 * 直接引用 [RevealDurationMillis]（motion 包内定义）：几何展开与流程驱动的时长
 * 必须是同一个值，两处各写一份迟早会漂移。
 */
private val RevealDurationMillis = RevealDurationMillisOfMotion

/**
 * 转场期间叠在扩展面之上的引导页残留：随展开进度淡出。
 *
 * 不用整页截屏或位图，只做 alpha 收束，避免 Blank frame。
 */
@Composable
private fun FadingOnboardingSurface(progress: Float, reduceMotion: Boolean) {
    // 进度在 0.42 之前仍是引导页主导，之后完全让位。
    // 减少动态时线性淡出；否则跟随展开进度加速收束。
    val alpha = if (reduceMotion) 1f - progress else (1f - progress / 0.42f).coerceIn(0f, 1f)
    if (alpha <= 0.01f) return
    Surface(
        modifier = Modifier
            .fillMaxSize()
            // graphicsLayer 的 alpha 用 lambda：进度每帧变化只触发绘制，不触发重组。
            .graphicsLayer { this.alpha = alpha },
        color = MaterialTheme.colorScheme.surface,
    ) {}
}

/** 打开本应用的系统详情页：权限被永久拒绝后的唯一可靠出口。 */
private fun openAppDetailsSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** 打开系统「通知使用权」列表；Android 没有可供本应用直接勾选的接口。 */
private fun openNotificationAccessSettings(context: Context): Boolean = try {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: android.content.ActivityNotFoundException) {
    false
}

/**
 * 由 manifest 权限名反查权限卡片 id，用于多权限申请的逐项结果归属。
 *
 * 只处理本项目实际会申请的权限；未知权限返回 null（不制造假状态）。
 */
private fun manifestPermissionToEntryId(permission: String): String? = when (permission) {
    Manifest.permission.POST_NOTIFICATIONS -> "notifications"
    Manifest.permission.RECORD_AUDIO -> "microphone"
    Manifest.permission.READ_SMS -> "sms"
    Manifest.permission.READ_CALL_LOG -> "call_log"
    else -> null
}
