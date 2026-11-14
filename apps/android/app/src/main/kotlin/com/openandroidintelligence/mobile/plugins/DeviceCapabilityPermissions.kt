package com.openandroidintelligence.mobile.plugins

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import com.openandroidintelligence.ui.resources.R as UiR

/**
 * 一项 Android 设备能力的**授权形态**。
 *
 * 这个区分是问题二的核心：不同能力要靠完全不同的系统入口才能授予，
 * 把它们统一成「申请权限」会导致用户点了按钮却什么都没发生。
 */
enum class DeviceCapabilityGrantKind {
    /** 普通运行时权限：可以调用系统权限申请对话框。 */
    RUNTIME_PERMISSION,

    /** 无障碍服务：必须跳转到系统无障碍设置，由用户亲手开启，无法用代码申请。 */
    ACCESSIBILITY_SERVICE,

    /** 通知使用权：必须跳转到系统「通知使用权」设置页。 */
    NOTIFICATION_ACCESS,

    /**
     * MediaProjection：必须由**前台 Activity** 发起系统授权对话框。
     * 不存在可持久查询的授权状态，跳设置页是无关的。
     */
    MEDIA_PROJECTION,
}

/**
 * 一条「设备能力 → Android 授权」需求。
 *
 * 由插件实际声明的 kernel primitive 动态推导，不写死任何 Agent 或 Gateway：
 * 新增能力时只需在 [androidPermissionRequirements] 里补一条映射。
 *
 * @param key 稳定标识，用于测试定位与去重。
 * @param label 给用户看的名称。
 * @param grantKind 授权形态，决定点击后走哪条系统入口。
 * @param manifestPermission 运行时权限的权限名；非运行时权限为 null。
 * @param purpose 用途说明，讲清「授权后能做什么、不授权会怎样」。
 * @param isFallback 是否为备用方案。备用方案只在首选入口不可用时才需要用户动作，
 *   界面据此决定是否提示、以及是否默认展示对应的按钮。
 */
data class AndroidPermissionRequirement(
    val key: String,
    @StringRes val labelRes: Int,
    val grantKind: DeviceCapabilityGrantKind,
    val manifestPermission: String? = null,
    @StringRes val purposeRes: Int,
    val isFallback: Boolean = false,
) {
    val label: String get() = (currentApplicationContext() ?: error("Context required")).getString(labelRes)
    val purpose: String get() = (currentApplicationContext() ?: error("Context required")).getString(purposeRes)
}

/**
 * 由 kernel primitive 集合推导所需的 Android 授权需求。
 *
 * 推导规则只依赖能力声明本身与系统版本，因此：
 * - 插件没声明某原语 → 不出现该项，不请求用不上的权限；
 * - 插件新增声明 → 入口自动出现，无需改动界面代码；
 * - 不涉及任何特定 Agent / Gateway 的名字。
 *
 * **屏幕截图优先无障碍**：Android 11（API 30）起无障碍服务可以按需取帧，
 * 用户只需在系统设置里开启一次，之后不再重复确认。因此截图能力的首要系统入口
 * 是无障碍设置，MediaProjection 只是需要用户明确同意的备用方案。系统不支持
 * 无障碍截图时，投屏才是唯一可用路径。
 */
fun androidPermissionRequirements(
    primitives: Set<String>,
    /** 当前系统是否支持无障碍截图（API 30+）。缺省视为支持，由调用方按真实版本传入。 */
    accessibilityScreenshotSupported: Boolean = true,
): List<AndroidPermissionRequirement> = buildList {
    if ("kernel.sms.read" in primitives) {
        add(
            AndroidPermissionRequirement(
                key = "sms",
                labelRes = UiR.string.perm_req_sms_title,
                grantKind = DeviceCapabilityGrantKind.RUNTIME_PERMISSION,
                manifestPermission = Manifest.permission.READ_SMS,
                purposeRes = UiR.string.perm_req_sms_purpose,
            ),
        )
    }
    if ("kernel.sms.send" in primitives) {
        add(
            AndroidPermissionRequirement(
                key = "sms-send",
                labelRes = UiR.string.perm_req_sms_send_title,
                grantKind = DeviceCapabilityGrantKind.RUNTIME_PERMISSION,
                manifestPermission = Manifest.permission.SEND_SMS,
                purposeRes = UiR.string.perm_req_sms_send_purpose,
            ),
        )
        add(
            AndroidPermissionRequirement(
                key = "sms-subscription",
                labelRes = UiR.string.perm_req_sms_subscription_title,
                grantKind = DeviceCapabilityGrantKind.RUNTIME_PERMISSION,
                manifestPermission = Manifest.permission.READ_PHONE_STATE,
                purposeRes = UiR.string.perm_req_sms_subscription_purpose,
            ),
        )
    }
    if ("kernel.call-log.read" in primitives) {
        add(
            AndroidPermissionRequirement(
                key = "call-log",
                labelRes = UiR.string.perm_req_call_log_title,
                grantKind = DeviceCapabilityGrantKind.RUNTIME_PERMISSION,
                manifestPermission = Manifest.permission.READ_CALL_LOG,
                purposeRes = UiR.string.perm_req_call_log_purpose,
            ),
        )
    }
    if ("kernel.notifications.read" in primitives) {
        add(
            AndroidPermissionRequirement(
                key = "notifications",
                labelRes = UiR.string.perm_req_notification_access_title,
                grantKind = DeviceCapabilityGrantKind.NOTIFICATION_ACCESS,
                purposeRes = UiR.string.perm_req_notification_access_purpose,
            ),
        )
    }

    // 屏幕读取、控制与无障碍截图共用同一个无障碍服务：任何一项需要它时都只出现一条入口。
    val wantsAccessibility = primitives.any {
        it == "kernel.screen.read" || it == "kernel.screen.control" ||
            (it == "kernel.screen.capture" && accessibilityScreenshotSupported)
    }
    val wantsProjection = "kernel.screen.capture" in primitives
    if (wantsAccessibility) {
        add(
            AndroidPermissionRequirement(
                key = "accessibility",
                labelRes = UiR.string.perm_req_accessibility_title,
                grantKind = DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE,
                purposeRes = if ("kernel.screen.capture" in primitives) {
                    UiR.string.perm_req_accessibility_capture_purpose
                } else {
                    UiR.string.perm_req_accessibility_control_purpose
                },
            ),
        )
    }
    if (wantsProjection) {
        add(
            AndroidPermissionRequirement(
                key = "screen-capture",
                labelRes = if (accessibilityScreenshotSupported) {
                    UiR.string.perm_req_screen_projection_fallback_title
                } else {
                    UiR.string.perm_req_screen_projection_title
                },
                grantKind = DeviceCapabilityGrantKind.MEDIA_PROJECTION,
                purposeRes = if (accessibilityScreenshotSupported) {
                    UiR.string.perm_req_screen_projection_fallback_purpose
                } else {
                    UiR.string.perm_req_screen_projection_purpose
                },
                isFallback = accessibilityScreenshotSupported,
            ),
        )
    }
}.distinctBy { it.key }

/** 一条需求当前的**真实**授权状态。 */
enum class DeviceCapabilityGrantState {
    /** 已授权：现在就能用。 */
    GRANTED,

    /** 尚未授权，但系统仍允许再次申请（这是正常起点，不是错误）。 */
    NOT_REQUESTED,

    /**
     * 用户拒绝过，但系统仍可能再次弹框。
     * 与 [NOT_REQUESTED] 分开，是为了让界面说明「上次被拒绝了」而不是「从未申请」。
     */
    DENIED,

    /**
     * 只能在系统设置里开启、且当前未开启（无障碍 / 通知使用权）。
     * 这些没有「申请」这个动作，只有跳设置。
     */
    REQUIRES_SETTINGS,

    /**
     * MediaProjection 只有「本次会话是否有系统授权」，没有可持久查询的状态。
     * 不属于任何阻塞，只是使用时现场确认。
     */
    ASKED_ON_USE,
}

/**
 * 授权状态查询器。
 *
 * 全部通过系统真实接口查询，不缓存、不推测：从系统设置返回后重新调用即可
 * 得到最新结果，界面据此更新状态并触发 Core 同步。
 */
class DeviceCapabilityGrantInspector(
    private val context: Context,
    private val notificationAccessGranted: () -> Boolean,
    /**
     * 无障碍服务是否**真的可用**（设置里已开启且系统当前已连接）。
     *
     * 只查设置里的启用标记是不够的：用户开启过、但系统解绑后，
     * 界面会把该项显示成「已授权」并禁用设置按钮，恰好堵死唯一的恢复入口。
     */
    private val accessibilityEnabled: () -> Boolean,
    private val captureAuthorized: () -> Boolean,
    private val deniedRuntimePermissions: (String) -> Boolean = { false },
) {
    /** 查询单项需求的真实状态。 */
    fun state(requirement: AndroidPermissionRequirement): DeviceCapabilityGrantState = when (requirement.grantKind) {
        DeviceCapabilityGrantKind.RUNTIME_PERMISSION -> {
            val permission = requirement.manifestPermission
            when {
                permission == null -> DeviceCapabilityGrantState.GRANTED
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ->
                    DeviceCapabilityGrantState.GRANTED
                deniedRuntimePermissions(permission) -> DeviceCapabilityGrantState.DENIED
                else -> DeviceCapabilityGrantState.NOT_REQUESTED
            }
        }
        DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE ->
            if (accessibilityEnabled()) DeviceCapabilityGrantState.GRANTED
            else DeviceCapabilityGrantState.REQUIRES_SETTINGS
        DeviceCapabilityGrantKind.NOTIFICATION_ACCESS ->
            if (notificationAccessGranted()) DeviceCapabilityGrantState.GRANTED
            else DeviceCapabilityGrantState.REQUIRES_SETTINGS
        // 投屏没有可持久查询的状态：已授权即 GRANTED，否则如实标为「使用时询问」。
        DeviceCapabilityGrantKind.MEDIA_PROJECTION ->
            if (captureAuthorized()) DeviceCapabilityGrantState.GRANTED
            else DeviceCapabilityGrantState.ASKED_ON_USE
    }

    /**
     * 可以合并成一次系统申请对话框的运行时权限。
     *
     * 只收集 [DeviceCapabilityGrantState.NOT_REQUESTED] 的项：
     * 已拒绝过的再弹框在多数系统上不会有任何反应，应该改为引导去设置页。
     */
    fun runtimePermissionsToRequest(requirements: List<AndroidPermissionRequirement>): List<String> =
        requirements.mapNotNull { requirement ->
            if (requirement.grantKind != DeviceCapabilityGrantKind.RUNTIME_PERMISSION) return@mapNotNull null
            val permission = requirement.manifestPermission ?: return@mapNotNull null
            permission.takeIf { state(requirement) == DeviceCapabilityGrantState.NOT_REQUESTED }
        }
}

/**
 * 现在是否还需要用户去做一次备用方案的授权。
 *
 * 首选入口（无障碍截图）已就绪时，备用方案一律不再提示——这正是“无障碍可用时不要求
 * 屏幕共享授权”这条需求的可执行形式。仅当存在备用方案且首选入口尚未就绪时才为 true。
 */
fun needsFallbackConsent(
    requirements: List<AndroidPermissionRequirement>,
    stateOf: (AndroidPermissionRequirement) -> DeviceCapabilityGrantState,
): Boolean {
    val fallback = requirements.filter { it.isFallback }
    if (fallback.isEmpty()) return false
    val primary = requirements.filterNot { it.isFallback }
    return primary.any { stateOf(it) != DeviceCapabilityGrantState.GRANTED }
}

/**
 * 是否需要在点击时直接跳系统无障碍设置，而不是弹权限框。
 *
 * 单独抽出来是因为这个判断在界面与测试里都要用，且必须与
 * [DeviceCapabilityGrantKind] 保持单一事实来源。
 */
fun AndroidPermissionRequirement.opensAccessibilitySettings(): Boolean =
    grantKind == DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE

/** 是否为需要前台 Activity 发起系统对话框的投屏授权。 */
fun AndroidPermissionRequirement.opensMediaProjectionDialog(): Boolean =
    grantKind == DeviceCapabilityGrantKind.MEDIA_PROJECTION

/** 是否为需要跳系统「通知使用权」页的授权。 */
fun AndroidPermissionRequirement.opensNotificationAccessSettings(): Boolean =
    grantKind == DeviceCapabilityGrantKind.NOTIFICATION_ACCESS

internal fun currentApplicationContext(): Context? {
    return runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentAppMethod = activityThreadClass.getMethod("currentApplication")
        currentAppMethod.invoke(null) as? Context
    }.getOrNull() ?: runCatching {
        val appProvider = Class.forName("androidx.test.core.app.ApplicationProvider")
        val getContext = appProvider.getMethod("getApplicationContext")
        getContext.invoke(null) as? Context
    }.getOrNull()
}

/**
 * 系统未提供某个设置页时的准确手动引导（问题二要求「无法自动跳转时显示准确的手动操作引导」）。
 *
 * 原文案只有笼统的「无法打开设置」，用户在定制 ROM 上找不到入口。
 */
fun manualGrantGuidance(requirement: AndroidPermissionRequirement, context: Context? = null): String {
    val ctx = context ?: currentApplicationContext() ?: error("Context is required for manualGrantGuidance")
    return when (requirement.grantKind) {
        DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE ->
            ctx.getString(UiR.string.perm_guidance_accessibility)
        DeviceCapabilityGrantKind.NOTIFICATION_ACCESS ->
            ctx.getString(UiR.string.perm_guidance_notification_access)
        DeviceCapabilityGrantKind.MEDIA_PROJECTION ->
            ctx.getString(UiR.string.perm_guidance_screen_projection)
        DeviceCapabilityGrantKind.RUNTIME_PERMISSION ->
            ctx.getString(UiR.string.perm_guidance_runtime_format, ctx.getString(requirement.labelRes))
    }
}
