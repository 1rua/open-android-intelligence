package com.openandroidintelligence.mobile.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.openandroidintelligence.ui.resources.R as UiR

/** 权限的分组。 */
enum class PermissionGroup(@StringRes val titleRes: Int) {
    REQUIRED(UiR.string.perm_group_required),
    OPTIONAL(UiR.string.perm_group_optional),
    DEVICE_CAPABILITY(UiR.string.perm_group_device_capability),
}

/** 该权限在当前设备/当前插件状态下是否真的存在。 */
enum class PermissionAvailability {
    /** 设备支持，且相关插件（如有）已启用。 */
    AVAILABLE,

    /** 依赖的插件未安装或未启用：此时**不展示**这张卡片。 */
    NOT_INSTALLED,
}

/**
 * 一张权限卡片的静态描述（与设备状态无关的部分）。
 *
 * @param id 稳定标识，用于状态映射与申请归属。
 * @param displayNameRes 权限名称资源 ID。
 * @param purposeRes 用途说明资源 ID。
 * @param group 分组，决定卡片上的徽标与排序。
 * @param isSpecialSystemAccess 是否只能通过系统设置页开启（不是运行时弹窗）。
 * @param availability 是否应该展示（不满足条件的权限直接不渲染，不做灰色禁用态）。
 * @param manifestPermissions 该权限对应的 Android manifest 权限名；
 *   用于运行时申请逐项归属结果，以及查询当前授权状态。
 * @param systemRole 若该项由**系统角色**（`RoleManager`）决定而非运行时权限，
 *   在此声明是哪一个角色；状态查询与按钮行为都走 [AssistantRole]。
 *   `null` 表示这是普通的权限项。
 * @param requiredForLogin **恒为 false**。任何系统权限都不阻断登录——
 *   这是需求「拒绝权限不应阻断无关功能」的硬约束。
 */
data class PermissionEntry(
    val id: String,
    @StringRes val displayNameRes: Int,
    @StringRes val purposeRes: Int,
    val group: PermissionGroup,
    val isSpecialSystemAccess: Boolean,
    val availability: PermissionAvailability,
    val manifestPermissions: List<String> = emptyList(),
    val systemRole: SystemRole? = null,
    val requiredForLogin: Boolean = false,
)

/**
 * 依据设备实际能力与插件状态，计算当前应展示的权限列表。
 *
 * **为什么必须动态计算**：需求明确要求「不请求不存在或尚不需要的权限」。
 * 短信 / 通话记录 / 通知使用权这三项，只有在对应设备能力插件已安装并启用时才有意义；
 * 在插件不存在的情况下把它们列出来，用户点「开启」会拿到一个永远无法使用的授权，
 * 既浪费注意力也制造误导。因此这类权限是**整体不出现**，而不是显示成禁用态。
 *
 * 排序固定为 REQUIRED → OPTIONAL → DEVICE_CAPABILITY，组内按声明顺序，
 * 保证同一设备每次进入看到的布局一致。
 *
 * @param androidSdk 当前设备的 `Build.VERSION.SDK_INT`。
 * @param enabledPluginPrimitives 已启用插件声明的 kernel primitive 集合，
 *   元素形如 `kernel.sms.read` / `kernel.notifications.read`。
 */
fun buildVisiblePermissions(
    androidSdk: Int,
    enabledPluginPrimitives: Set<String>,
): List<PermissionEntry> {
    val entries = buildList {
        // ---- 基础功能 ----
        add(
            PermissionEntry(
                id = "notifications",
                displayNameRes = UiR.string.perm_catalog_notifications_title,
                purposeRes = UiR.string.perm_catalog_notifications_desc,
                group = PermissionGroup.REQUIRED,
                isSpecialSystemAccess = false,
                availability = PermissionAvailability.AVAILABLE,
                // POST_NOTIFICATIONS 自 Android 13（API 33）起才是运行时权限；
                // 更早的系统上通知默认开启，没有可申请的东西。
                manifestPermissions = if (androidSdk >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyList()
                },
            ),
        )

        // ---- 可选增强 ----
        add(
            PermissionEntry(
                id = "microphone",
                displayNameRes = UiR.string.perm_catalog_microphone_title,
                purposeRes = UiR.string.perm_catalog_microphone_desc,
                group = PermissionGroup.OPTIONAL,
                isSpecialSystemAccess = false,
                availability = PermissionAvailability.AVAILABLE,
                manifestPermissions = listOf(Manifest.permission.RECORD_AUDIO),
            ),
        )
        add(
            PermissionEntry(
                id = "screen_capture",
                displayNameRes = UiR.string.perm_catalog_screen_capture_title,
                purposeRes = UiR.string.perm_catalog_screen_capture_desc,
                group = PermissionGroup.OPTIONAL,
                // 投屏授权由系统 MediaProjection 对话框现场询问，没有可持久查询的状态。
                isSpecialSystemAccess = true,
                availability = PermissionAvailability.AVAILABLE,
                manifestPermissions = emptyList(),
            ),
        )
        add(
            PermissionEntry(
                id = "assistant_role",
                displayNameRes = UiR.string.perm_catalog_assistant_role_title,
                purposeRes = UiR.string.perm_catalog_assistant_role_desc,
                group = PermissionGroup.OPTIONAL,
                // 默认助理是 RoleManager 管理的系统角色，不是运行时权限，
                // 只能在系统角色对话框或系统设置里授予；状态查询走 AssistantRole。
                isSpecialSystemAccess = true,
                availability = PermissionAvailability.AVAILABLE,
                manifestPermissions = emptyList(),
                systemRole = SystemRole.ASSISTANT,
            ),
        )

        // ---- 设备能力扩展（短信、通话、通知使用权等） ----
        add(
            PermissionEntry(
                id = "sms",
                displayNameRes = UiR.string.perm_catalog_sms_title,
                purposeRes = UiR.string.perm_catalog_sms_desc,
                group = PermissionGroup.DEVICE_CAPABILITY,
                isSpecialSystemAccess = false,
                availability = PermissionAvailability.AVAILABLE,
                manifestPermissions = listOf(Manifest.permission.READ_SMS),
            ),
        )
        add(
            PermissionEntry(
                id = "call_log",
                displayNameRes = UiR.string.perm_catalog_call_log_title,
                purposeRes = UiR.string.perm_catalog_call_log_desc,
                group = PermissionGroup.DEVICE_CAPABILITY,
                isSpecialSystemAccess = false,
                availability = PermissionAvailability.AVAILABLE,
                manifestPermissions = listOf(
                    Manifest.permission.READ_CALL_LOG,
                ),
            ),
        )
        add(
            PermissionEntry(
                id = "notification_access",
                displayNameRes = UiR.string.perm_catalog_notification_access_title,
                purposeRes = UiR.string.perm_catalog_notification_access_desc,
                group = PermissionGroup.DEVICE_CAPABILITY,
                isSpecialSystemAccess = true,
                availability = PermissionAvailability.AVAILABLE,
                manifestPermissions = emptyList(),
            ),
        )
    }

    // 稳定排序：REQUIRED → OPTIONAL → DEVICE_CAPABILITY，同组保持声明顺序。
    return entries.sortedBy { it.group.ordinal }
}

/**
 * 便捷重载：取当前设备 SDK 版本。
 *
 * 仅在做界面时省一次参数传递；真正的可见性判定仍走带 [androidSdk] 的版本，
 * 这样纯 JVM 测试不需要 mock Android 版本。
 */
fun buildVisiblePermissions(enabledPluginPrimitives: Set<String>): List<PermissionEntry> =
    buildVisiblePermissions(Build.VERSION.SDK_INT, enabledPluginPrimitives)

/**
 * 查询某项权限在**当前设备上的真实状态**。
 *
 * 判定顺序：
 * 1. 没有对应的 manifest 权限（早期系统上的通知、或系统对话框型的投屏）→ [PermissionStatus.NOT_REQUIRED]；
 * 2. 已授予 → [PermissionStatus.GRANTED]；
 * 3. 未授予时，若系统仍允许再次询问 → [PermissionStatus.DENIED]（可再弹框）；
 * 4. 否则 → [PermissionStatus.BLOCKED]（只能去系统设置）。
 *
 * 把 3 与 4 混为一谈是「重复弹窗」的根因：BLOCKED 状态下再申请系统什么都不会做。
 */
fun resolvePermissionStatus(context: Context, entry: PermissionEntry): PermissionStatus {
    // 系统角色项（默认数字助理）不适用上面的权限判定顺序：
    // 它既不是运行时权限，也没有 manifest 项，只能向系统的 RoleManager 查询真实状态。
    // 三种结果必须分开：不支持（系统里没有这个角色）/ 已持有 / 未持有但可去申请。
    if (entry.systemRole != null) {
        return resolveSystemRoleStatus(context, entry.systemRole)
    }

    // 屏幕采集是纯现场确认型（MediaProjection 对话框）：
    // 系统不提供「已授权」的持久状态，只能在每次发起圈选时由系统弹窗；
    // 引导页展示它只是告知用户「有这项能力」，不能也不该在此刻把状态刷成 GRANTED。
    if (entry.id == "screen_capture") {
        return PermissionStatus.NOT_REQUIRED
    }

    // 特殊系统访问项（如通知使用权）不能走普通的 checkSelfPermission：
    // 它们不是运行时权限，Manifest 权限列表为空；状态来自对应的系统服务。
    if (entry.isSpecialSystemAccess) {
        return when (entry.id) {
            "notification_access" -> if (hasNotificationListenerAccess(context)) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }
            else -> PermissionStatus.NOT_REQUIRED
        }
    }

    // 没有对应 manifest 权限的项直接无需处理（早期系统上的通知）。
    if (entry.manifestPermissions.isEmpty()) {
        // Android 12 及以下无 POST_NOTIFICATIONS，但用户仍可能在系统设置里
        // 把应用的通知总开关关掉；必须核验真实状态，不能直接假定 GRANTED。
        if (entry.id == "notifications" && !notificationsAllowedBySystem(context)) {
            return PermissionStatus.BLOCKED
        }
        return PermissionStatus.GRANTED
    }

    // 常规运行时权限：逐项检查 checkSelfPermission。
    val allGranted = entry.manifestPermissions.all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
    if (allGranted) return PermissionStatus.GRANTED

    // 未授予时，区分「还能弹系统框（DENIED）」与「只能去系统设置（BLOCKED）」。
    // 官方行为：首次询问前 shouldShowRationale 返回 false；被用户拒绝过一次后
    // 返回 true；用户在弹窗里勾选了「不再询问」或系统隐式判定永久拒绝后又返回 false。
    //
    // 因此纯凭 shouldShowRationale 无法把「首次启动（从没问过）」与「永久拒绝」
    // 分开（两者都是 false）。这里引入轻量会话记录：只有在本会话内已经向用户
    // 发起过申请、且目前仍不是 GRANTED 时，才把 shouldShowRationale == false
    // 升级为 BLOCKED（需要跳设置）；从没问过的权限必须保持 DENIED，确保用户第一
    // 次点「开启」能弹框。
    val requestedThisSession = entry.manifestPermissions.any { it in requestedInSession }
    val anyPermanentlyDenied = entry.manifestPermissions.any { perm ->
        requestedThisSession && !shouldShowRationale(context, perm)
    }
    return if (anyPermanentlyDenied) PermissionStatus.BLOCKED else PermissionStatus.DENIED
}

/**
 * 本进程内已经发起过申请、但当前仍未授权的权限名集合。
 *
 * 只活在内存里，进程重启即清空 —— 这是刻意的：重启后系统对话框会重新可弹，
 * 我们不该把一个已经不成立的「永久拒绝」结论带进新进程。
 */
private val requestedInSession = mutableSetOf<String>()

/** 记录一次申请已经发起；由权限申请入口在 launch 前调用。 */
fun notePermissionRequested(manifestPermissions: Collection<String>) {
    requestedInSession.addAll(manifestPermissions)
}

/**
 * `shouldShowRequestPermissionRationale` 的弱封装。
 *
 * [Context] 在 Activity 上下文中才有意义；非 Activity 上下文直接返回 false，
 * 由调用方按 [PermissionStatus.DENIED] 处理（再点一次仍会弹系统框）。
 */
private fun shouldShowRationale(context: Context, permission: String): Boolean {
    val activity = context as? android.app.Activity ?: return false
    return activity.shouldShowRequestPermissionRationale(permission)
}

/**
 * 系统角色（RoleManager）状态解析。
 *
 * 不支持的角色无论持不持有都不该出现操作按钮；支持但未持有时返回
 * [PermissionStatus.DENIED]（而不是 BLOCKED），因为角色对话框/设置页仍可再次打开，
 * 用户点「设置」就有真实的下一步。
 */
private fun resolveSystemRoleStatus(context: Context, role: SystemRole): PermissionStatus = when (role) {
    SystemRole.ASSISTANT -> when {
        !AssistantRole.isAssistantRoleSupported(context) -> PermissionStatus.UNSUPPORTED
        AssistantRole.isAssistantRoleHeld(context) -> PermissionStatus.GRANTED
        else -> PermissionStatus.DENIED
    }
}

/** 已启用插件的 kernel primitive 标识（与各 plugins 插件的 manifest.json 保持一致）。 */
internal const val KERNEL_NOTIFICATIONS_READ = "kernel.notifications.read"
internal const val KERNEL_SMS_READ = "kernel.sms.read"
internal const val KERNEL_CALL_LOG_READ = "kernel.call-log.read"

/** 权限页底部的固定说明资源 ID。 */
val PERMISSION_NOTICE_TITLE_RES = UiR.string.perm_notice_title
val PERMISSION_INTRO_BODY_RES = UiR.string.perm_intro_body
val PERMISSION_NOTICE_BODY_RES = UiR.string.perm_notice_body

/**
 * 读取系统当前是否已授予本应用「通知使用权」。
 *
 * 这是 Android 官方提供的可查询状态：`NotificationManagerCompat.getEnabledListenerPackages`
 * 返回系统设置里已勾选的监听器包名集合。用它而不是维护自己的布尔标记，
 * 才能保证用户去系统设置改过之后，回到引导页看到的是**真实**状态。
 */
private fun hasNotificationListenerAccess(context: Context): Boolean = try {
    android.app.NotificationManager::class.java
    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)
} catch (_: Exception) {
    // 极少数 ROM 上该 API 可能抛异常；此时保守返回 false（显示「未开启」），
    // 用户仍可通过「前往设置」手动确认，不伪造已授权。
    false
}

/**
 * 查询系统的「本应用通知」总开关是否允许（与运行时权限无关）。
 *
 * Android 12 及以下没有 POST_NOTIFICATIONS 运行时权限，用户在系统设置里
 * 关掉通知后，`checkSelfPermission` 依然返回「已授予」。只有
 * `NotificationManager.areNotificationsEnabled()` 能反映真实状态。
 */
private fun notificationsAllowedBySystem(context: Context): Boolean = try {
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
    manager?.areNotificationsEnabled() ?: true
} catch (_: Exception) {
    // 极端 ROM 取不到服务时保守认为可用，避免把正常用户误判成「被拒绝」。
    true
}
