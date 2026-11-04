package com.openandroidintelligence.mobile.plugins

/**
 * 一项能力当前为什么不能调用，以及**它是否还能被用户操作恢复**。
 *
 * 问题三的根因：原来 `ANDROID_PERMISSION_REQUIRED` 这一个状态同时覆盖了
 * 「从未申请」「服务断连」「系统拒绝」三种情况，远端与界面都无法判断该不该
 * 继续等待，于是一个本来点一下就能好的权限被长期当成不可恢复的阻塞。
 *
 * 修正方式不是新增 Core 状态值（Core 的 inventory 只接受固定的 6 个状态，
 * 属于既有统一能力接口的契约，不能为界面方便而扩张），而是在**原因码**
 * 层面把可恢复性讲清楚：
 *
 * - `status` 仍是契约允许的那 6 个值之一，协议不变；
 * - `reason` 使用区分度更高的稳定码；
 * - 本文件的分类让界面与诊断能判断「这是可恢复的、下一步该做什么」。
 */
enum class DeviceCapabilityBlock {
    /** 未申请授权：用户还没做过这个动作。属于正常起点，可一键申请。 */
    NOT_YET_REQUESTED,

    /** 等待用户操作：需要用户去系统设置里亲手开启，应用无法代劳。 */
    AWAITING_USER_ACTION,

    /** 服务未连接：用户开启过但系统当前没绑定（含被系统解绑、进程重启后未重连）。 */
    SERVICE_DISCONNECTED,

    /** 系统拒绝：用户明确拒绝了授权，或系统策略不允许。 */
    SYSTEM_REJECTED,

    /** 频率限制：暂时性的限速，等待后即可重试。 */
    RATE_LIMITED,

    /** 本机能力开关关闭：用户主动关的，打开即可恢复。 */
    LOCAL_SWITCH_OFF,

    /** 插件未安装或未启用：安装/启用即可恢复。 */
    PLUGIN_INACTIVE,

    /** 尚未选择提供者：选一个即可恢复。 */
    PROVIDER_UNSELECTED,

    /** 同步中/待同步：等待即可，不是错误。 */
    SYNCING,

    /** 真正不可用：设备能力缺失或配置重复且当前无检测手段。 */
    UNAVAILABLE,
    ;

    /**
     * 该阻塞是否可由此应用的用户操作（或简单重试）恢复。
     *
     * 这是问题三的核心判据：只有 [UNAVAILABLE] 属于不可恢复。
     * 其余全部可恢复，界面与远端都不应把它当成终态阻塞。
     */
    val isRecoverable: Boolean
        get() = this != UNAVAILABLE

    /** 是否需要用户到系统设置里操作（无法由应用直接完成）。 */
    val requiresUserActionInSettings: Boolean
        get() = this == AWAITING_USER_ACTION || this == SYSTEM_REJECTED || this == SERVICE_DISCONNECTED
}

/**
 * 把能力快照里的 `(status, reason)` 解释为可操作的阻塞分类。
 *
 * 入参就是 Core 看到的同一对值，因此界面显示与远端判定不会各说一套。
 */
fun classifyCapabilityBlock(status: String, reason: String?): DeviceCapabilityBlock = when (status) {
    // READY 表示没有阻塞；调用方应先判断 status，本函数只用于有阻塞时的分类。
    "READY" -> DeviceCapabilityBlock.SYNCING
    "PLUGIN_NOT_INSTALLED" -> DeviceCapabilityBlock.PLUGIN_INACTIVE
    "PLUGIN_DISABLED" -> DeviceCapabilityBlock.PLUGIN_INACTIVE
    "PROVIDER_NOT_SELECTED" -> DeviceCapabilityBlock.PROVIDER_UNSELECTED
    "SYNCING", "SYNC_RETRYING", "SYNC_REQUIRED", "SYNC_FAILED", "STATUS_UNAVAILABLE",
    "ANDROID_STATE_UNKNOWN", "AUTHORIZATION_MODEL_UNSUPPORTED" -> DeviceCapabilityBlock.SYNCING
    "LOCAL_GRANT_REQUIRED" -> when (reason) {
        "LOCAL_CAPABILITY_DISABLED", "LOCAL_GRANT_REQUIRED", null, "" -> DeviceCapabilityBlock.LOCAL_SWITCH_OFF
        else -> DeviceCapabilityBlock.AWAITING_USER_ACTION
    }
    "ANDROID_PERMISSION_REQUIRED" -> when (reason) {
        // 无障碍服务：开过但没连上 → 服务断连；从没开过 → 等待用户去设置里开。
        "ACCESSIBILITY_SERVICE_UNAVAILABLE" -> DeviceCapabilityBlock.SERVICE_DISCONNECTED
        "SCREEN_CAPTURE_SYSTEM_REJECTED" -> DeviceCapabilityBlock.SYSTEM_REJECTED
        "SCREEN_CAPTURE_RATE_LIMITED" -> DeviceCapabilityBlock.RATE_LIMITED
        "SCREEN_DEVICE_LOCKED" -> DeviceCapabilityBlock.AWAITING_USER_ACTION
        // 从未申请过的运行时权限 / 通知使用权：可一键申请或跳设置，不是阻塞。
        "READ_SMS_REQUIRED", "READ_CALL_LOG_REQUIRED", "NOTIFICATION_ACCESS_REQUIRED",
        "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED", null, "" -> DeviceCapabilityBlock.NOT_YET_REQUESTED
        else -> DeviceCapabilityBlock.AWAITING_USER_ACTION
    }
    "PROVIDER_UNAVAILABLE" -> when (reason) {
        // 通知监听服务未连接：开过但未连接，属于可恢复的服务断连状态。
        "NOTIFICATION_LISTENER_UNAVAILABLE" -> DeviceCapabilityBlock.SERVICE_DISCONNECTED
        // 受保护窗口不是设备缺陷：换到普通界面就能截，属于等待用户操作。
        "SCREEN_CAPTURE_SECURE_WINDOW" -> DeviceCapabilityBlock.AWAITING_USER_ACTION
        // 只有「系统/无障碍服务本身不支持截图」才是真正不可用。
        "SCREEN_CAPTURE_UNSUPPORTED" -> DeviceCapabilityBlock.UNAVAILABLE
        // 频率限制与本次取帧失败都是瞬时的，等一会重试即可。
        "SCREEN_CAPTURE_RATE_LIMITED", "SCREEN_CAPTURE_FAILED", "SCREEN_CAPTURE_ENCODING_FAILED" ->
            DeviceCapabilityBlock.RATE_LIMITED
        else -> DeviceCapabilityBlock.UNAVAILABLE
    }
    else -> DeviceCapabilityBlock.UNAVAILABLE
}

/**
 * 给用户看的下一步说明。
 *
 * 每条都指向一个具体动作，不说「当前阻塞原因」这类无法执行的话。
 */
fun capabilityBlockNextStep(block: DeviceCapabilityBlock, reason: String? = null): String = when (block) {
    DeviceCapabilityBlock.NOT_YET_REQUESTED -> "可在「系统权限与后台任务」中申请对应权限"
    DeviceCapabilityBlock.AWAITING_USER_ACTION -> "需要在系统设置中手动开启"
    DeviceCapabilityBlock.SERVICE_DISCONNECTED -> when (reason) {
        "NOTIFICATION_LISTENER_UNAVAILABLE" -> "通知监听服务尚未连接，请在系统设置中确认本应用的通知使用权已开启"
        else -> "无障碍服务已开启但当前未连接，请回到无障碍设置确认服务已连接"
    }
    DeviceCapabilityBlock.SYSTEM_REJECTED -> "上次授权被系统拒绝，请重新发起一次授权"
    DeviceCapabilityBlock.RATE_LIMITED -> "系统暂时限速，稍等片刻重试即可"
    DeviceCapabilityBlock.LOCAL_SWITCH_OFF -> "该能力已关闭，开启对应开关即可恢复"
    DeviceCapabilityBlock.PLUGIN_INACTIVE -> "安装或启用对应插件即可恢复"
    DeviceCapabilityBlock.PROVIDER_UNSELECTED -> "在插件条目里选择由哪个插件提供这项能力"
    DeviceCapabilityBlock.SYNCING -> "正在同步本机能力设置，稍候会自动更新"
    DeviceCapabilityBlock.UNAVAILABLE -> "当前设备或系统版本不支持这项能力"
}

fun capabilityBlockNextStep(block: DeviceCapabilityBlock): String = capabilityBlockNextStep(block, null)
