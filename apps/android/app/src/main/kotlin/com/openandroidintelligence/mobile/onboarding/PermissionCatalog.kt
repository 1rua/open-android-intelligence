package com.openandroidintelligence.mobile.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 权限的分组。
 *
 * 分组不是视觉分类，而是**对用户承诺不同的东西**：
 * - [REQUIRED] 关掉会明显损失主要体验，但仍然不阻断登录；
 * - [OPTIONAL] 纯增强，跳过没有任何后果；
 * - [DEVICE_CAPABILITY] 授予后也只是「应用能访问」，Agent 能不能用还要另过一道授权。
 */
enum class PermissionGroup {
    REQUIRED,
    OPTIONAL,
    DEVICE_CAPABILITY,
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
 * @param displayName 权限名称。
 * @param purpose 用途说明：必须讲清「授权后会怎样、不授权会怎样」，
 *   而不是「为了更好的体验」这类空话。
 * @param group 分组，决定卡片上的徽标与排序。
 * @param isSpecialSystemAccess 是否只能通过系统设置页开启（不是运行时弹窗）。
 * @param availability 是否应该展示（不满足条件的权限直接不渲染，不做灰色禁用态）。
 * @param manifestPermissions 该权限对应的 Android manifest 权限名；
 *   用于运行时申请逐项归属结果，以及查询当前授权状态。
 * @param requiredForLogin **恒为 false**。任何系统权限都不阻断登录——
 *   这是需求「拒绝权限不应阻断无关功能」的硬约束。
 */
data class PermissionEntry(
    val id: String,
    val displayName: String,
    val purpose: String,
    val group: PermissionGroup,
    val isSpecialSystemAccess: Boolean,
    val availability: PermissionAvailability,
    val manifestPermissions: List<String> = emptyList(),
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
                displayName = "通知",
                purpose = "接收 Agent 回复、生成完成与审批提醒。关闭后仍可使用，但需要保持应用在前台。",
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
                displayName = "麦克风",
                purpose = "发送语音消息。仅在按下录音时采集，音频直接发往你的 Gateway。",
                group = PermissionGroup.OPTIONAL,
                isSpecialSystemAccess = false,
                availability = PermissionAvailability.AVAILABLE,
                manifestPermissions = listOf(Manifest.permission.RECORD_AUDIO),
            ),
        )
        add(
            PermissionEntry(
                id = "screen_capture",
                displayName = "屏幕采集",
                purpose = "圈选屏幕内容发送给 Agent。每次使用都需系统弹窗确认，不会后台持续录屏。",
                group = PermissionGroup.OPTIONAL,
                // 投屏授权由系统 MediaProjection 对话框现场询问，没有可持久查询的状态。
                isSpecialSystemAccess = true,
                availability = PermissionAvailability.AVAILABLE,
                manifestPermissions = emptyList(),
            ),
        )

        // ---- 设备能力：完全由插件存在性决定 ----
        if (KERNEL_NOTIFICATIONS_READ in enabledPluginPrimitives) {
            add(
                PermissionEntry(
                    id = "notification_access",
                    displayName = "通知使用权",
                    purpose = "仅当安装并启用通知插件后需要。用于让 Agent 查询通知，需在系统设置中手动开启。",
                    group = PermissionGroup.DEVICE_CAPABILITY,
                    isSpecialSystemAccess = true,
                    availability = PermissionAvailability.AVAILABLE,
                    manifestPermissions = emptyList(),
                ),
            )
        }
        if (KERNEL_SMS_READ in enabledPluginPrimitives) {
            add(
                PermissionEntry(
                    id = "sms",
                    displayName = "短信读取",
                    purpose = "仅当安装并启用短信插件后需要。用于让 Agent 查询短信，应用本身不读取这些数据。",
                    group = PermissionGroup.DEVICE_CAPABILITY,
                    isSpecialSystemAccess = false,
                    availability = PermissionAvailability.AVAILABLE,
                    manifestPermissions = listOf(Manifest.permission.READ_SMS),
                ),
            )
        }
        if (KERNEL_CALL_LOG_READ in enabledPluginPrimitives) {
            add(
                PermissionEntry(
                    id = "call_log",
                    displayName = "通话记录读取",
                    purpose = "仅当安装并启用通话记录插件后需要。用于让 Agent 查询通话记录。",
                    group = PermissionGroup.DEVICE_CAPABILITY,
                    isSpecialSystemAccess = false,
                    availability = PermissionAvailability.AVAILABLE,
                    manifestPermissions = listOf(Manifest.permission.READ_CALL_LOG),
                ),
            )
        }
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
    // 特殊系统访问权限（通知使用权）没有可查询的运行时状态，
    // 由调用方在回到前台时另行读取系统设置；这里按「未开启」处理，不做假声明。
    if (entry.isSpecialSystemAccess && entry.manifestPermissions.isEmpty()) {
        return PermissionStatus.NOT_REQUIRED
    }
    if (entry.manifestPermissions.isEmpty()) {
        // 早期 Android 上通知默认开启；没有可申请的权限即视为已可用。
        return PermissionStatus.GRANTED
    }
    val allGranted = entry.manifestPermissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    if (allGranted) return PermissionStatus.GRANTED

    // 未授予时如何区分 DENIED 与 BLOCKED？
    //
    // `shouldShowRequestPermissionRationale` 在这里**不能单独作为判据**：
    // 它对「首次申请前」和「已永久拒绝后」都返回 false，两者状态完全不同
    // （前者再点会弹框，后者再点什么都不会发生）。
    //
    // 可靠判据需要一个外部的「已申请过」标记。获得该标记的路径有两条：
    // 1. [SessionPermissionRequests]：本会话内点过申请但没得到授权 → 视为已申请；
    // 2. 调用方在 Activity 重建前持久化的历史标记。
    //
    // 没有标记时保守返回 [PermissionStatus.DENIED]：再次申请仍可能弹框，
    // 最坏情况是多弹一次系统框；反过来误标 BLOCKED 会让用户永远点不开权限。
    val rationaleVisible = entry.manifestPermissions.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED &&
            shouldShowRationale(context, permission)
    }
    if (rationaleVisible) return PermissionStatus.DENIED

    // 本会话内已经申请过、且仍未授予 ⇒ 系统不再给弹框机会，只能去设置页。
    val requestedThisSession = entry.manifestPermissions.any { it in requestedInSession }
    return if (requestedThisSession) PermissionStatus.BLOCKED else PermissionStatus.DENIED
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

/** 已启用插件的 kernel primitive 标识（与各 plugins 插件的 manifest.json 保持一致）。 */
internal const val KERNEL_NOTIFICATIONS_READ = "kernel.notifications.read"
internal const val KERNEL_SMS_READ = "kernel.sms.read"
internal const val KERNEL_CALL_LOG_READ = "kernel.call-log.read"

/** 权限页底部的固定说明：需求要求区分系统权限与 Agent 授权。 */
const val PERMISSION_NOTICE_TITLE = "权限 ≠ 授权"
const val PERMISSION_NOTICE_BODY =
    "系统权限只决定应用能否访问某项能力；Agent 能否真正调用，取决于插件是否启用、" +
        "以及你是否在「设备能力与配对授权」里按账号授予。"
