package com.openandroidintelligence.mobile.plugins

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

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
 */
data class AndroidPermissionRequirement(
    val key: String,
    val label: String,
    val grantKind: DeviceCapabilityGrantKind,
    val manifestPermission: String? = null,
    val purpose: String,
)

/**
 * 由 kernel primitive 集合推导所需的 Android 授权需求。
 *
 * 这是问题二的修复点：原来只处理 READ_SMS 与 READ_CALL_LOG，其他设备能力
 * （无障碍、通知读取、屏幕截图）在「检查 Android 系统权限」里完全没有入口。
 *
 * 推导规则只依赖能力声明本身，因此：
 * - 插件没声明某原语 → 不出现该项，不请求用不上的权限；
 * - 插件新增声明 → 入口自动出现，无需改动界面代码；
 * - 不涉及任何特定 Agent / Gateway 的名字。
 */
fun androidPermissionRequirements(primitives: Set<String>): List<AndroidPermissionRequirement> = buildList {
    if ("kernel.sms.read" in primitives) {
        add(
            AndroidPermissionRequirement(
                key = "sms",
                label = "短信读取",
                grantKind = DeviceCapabilityGrantKind.RUNTIME_PERMISSION,
                manifestPermission = Manifest.permission.READ_SMS,
                purpose = "读取本机短信内容供 Agent 查询。不授权时短信能力不可用，其他功能不受影响。",
            ),
        )
    }
    if ("kernel.call-log.read" in primitives) {
        add(
            AndroidPermissionRequirement(
                key = "call-log",
                label = "通话记录读取",
                grantKind = DeviceCapabilityGrantKind.RUNTIME_PERMISSION,
                manifestPermission = Manifest.permission.READ_CALL_LOG,
                purpose = "读取本机通话记录供 Agent 查询。不授权时通话记录能力不可用。",
            ),
        )
    }
    if ("kernel.notifications.read" in primitives) {
        add(
            AndroidPermissionRequirement(
                key = "notifications",
                label = "通知使用权",
                grantKind = DeviceCapabilityGrantKind.NOTIFICATION_ACCESS,
                purpose = "读取新到通知的内容供 Agent 处理。需要先在系统「通知使用权」里允许本应用。",
            ),
        )
    }
    // 屏幕读取与控制都依赖无障碍服务：没有它连语义树都拿不到。
    if (primitives.any { it == "kernel.screen.read" || it == "kernel.screen.control" }) {
        add(
            AndroidPermissionRequirement(
                key = "accessibility",
                label = "无障碍服务",
                grantKind = DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE,
                purpose = "读取屏幕内容并执行点按等操作。必须在系统无障碍设置里开启本应用的服务。",
            ),
        )
    }
    if ("kernel.screen.capture" in primitives) {
        add(
            AndroidPermissionRequirement(
                key = "screen-capture",
                label = "屏幕截图",
                grantKind = DeviceCapabilityGrantKind.MEDIA_PROJECTION,
                purpose = "截取屏幕画面供 Agent 查看。优先使用已开启的无障碍服务；未开启时才需要一次系统投屏授权。",
            ),
        )
    }
}

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
     * 当前真正需要执行动作的需求。
     *
     * 已授权的不会重复申请 —— 问题二要求「已授权时正确显示状态，避免无意义地重复申请」。
     */
    fun pending(requirements: List<AndroidPermissionRequirement>): List<AndroidPermissionRequirement> =
        requirements.filter { state(it) != DeviceCapabilityGrantState.GRANTED }

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

/**
 * 系统未提供某个设置页时的准确手动引导（问题二要求「无法自动跳转时显示准确的手动操作引导」）。
 *
 * 原文案只有笼统的「无法打开设置」，用户在定制 ROM 上找不到入口。
 */
fun manualGrantGuidance(requirement: AndroidPermissionRequirement): String = when (requirement.grantKind) {
    DeviceCapabilityGrantKind.ACCESSIBILITY_SERVICE ->
        "无法自动打开无障碍设置。请手动进入：系统设置 → 无障碍 → 已下载的服务 / 已安装的应用 → 找到本应用 → 开启，并确认服务显示「已连接」。"
    DeviceCapabilityGrantKind.NOTIFICATION_ACCESS ->
        "无法自动打开通知使用权设置。请手动进入：系统设置 → 通知 → 通知使用权（或「设备和应用通知」→ 特殊访问权限）→ 找到本应用 → 允许。"
    DeviceCapabilityGrantKind.MEDIA_PROJECTION ->
        "截图需要一次系统屏幕录制授权。请在应用内重新点击「系统投屏授权」发起；若系统未提供录屏服务，则本机无法截图。"
    DeviceCapabilityGrantKind.RUNTIME_PERMISSION ->
        "无法自动申请该权限。请手动进入：系统设置 → 应用 → 本应用 → 权限 → 允许${requirement.label}。"
}
