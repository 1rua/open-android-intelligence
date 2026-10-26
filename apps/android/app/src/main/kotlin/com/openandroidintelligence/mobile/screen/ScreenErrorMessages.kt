package com.openandroidintelligence.mobile.screen

/**
 * 屏幕能力错误码 → 用户可读文案。
 *
 * 界面**永不直接显示**这些内部错误码（`SCREEN_SESSION_REQUIRED` 之类）：
 * 设计规格要求「不出现内部术语」，且这些码对本机用户没有可执行意义。
 * 每个码都映射成「发生了什么 + 现在该做什么」。
 *
 * 依据 docs/guides/android-screen-interfaces.zh-CN.md 的错误表分组：
 * 本地授权 / 系统服务 / 控件与版本 / 平台执行 / 图片生命周期 / 本地控制确认。
 */
fun readableScreenError(code: String?): String = when (code) {
    // ===== 本地授权 =====
    "SCREEN_SESSION_REQUIRED", "SCREEN_SESSION_EXPIRED", "SCREEN_SESSION_BINDING_CHANGED" ->
        "屏幕会话已失效，请重新确认要允许的屏幕权限"
    "SCREEN_PERMISSION_REQUIRED" ->
        "这次操作需要的权限不在当前会话里，请重新确认屏幕权限"
    "SCREEN_CONNECTION_REQUIRED" ->
        "还没有连接到已配对的设备，请先登录并完成配对"
    "SCREEN_CONFIRMATION_REQUIRED", "SCREEN_CONFIRMATION_LIMIT" ->
        "这次操作还需要你在手机上的确认才能执行"
    "SCREEN_REQUEST_EXPIRED" ->
        "这次操作已经超时，请重新发起"

    // ===== 系统服务 =====
    "ACCESSIBILITY_SERVICE_UNAVAILABLE" ->
        "无障碍服务还没有开启，请在系统设置中启用后返回"
    "SCREEN_DEVICE_LOCKED" ->
        "设备已锁屏，解锁后才能读取或操作屏幕"
    "SCREEN_WINDOW_UNAVAILABLE" ->
        "现在没有可以读取的窗口，请确认目标应用已打开"
    "SCREEN_DISPLAY_UNAVAILABLE" ->
        "暂时无法获取屏幕信息，请稍后重试"
    "SCREEN_TARGET_NOT_INTERACTABLE" ->
        "目标控件当前不接受操作，可能是页面还在变化"

    // ===== 截图授权 =====
    "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED" ->
        "还没有获得系统的投屏授权，请在下方完成一次系统授权"
    "SCREEN_CAPTURE_FAILED" ->
        "这次截图没有成功，请稍后再试"
    "SCREEN_CAPTURE_ENCODING_FAILED" ->
        "截图处理失败，请尝试降低截图宽度"
    "SCREEN_IMAGE_TOO_LARGE", "SCREEN_IMAGE_LIMIT_REACHED" ->
        "截图数量或体积超出上限，请稍后再试"
    "SCREEN_IMAGE_UNAVAILABLE" ->
        "这张截图已过期或已释放，请重新截取"

    // ===== 控件与版本 =====
    "SCREEN_STALE_REFERENCE" ->
        "屏幕内容已经变化，请重新读取后重新确认要操作的位置"
    "SCREEN_WINDOW_CHANGED" ->
        "目标窗口已经变化，请重新读取屏幕再操作"
    "SCREEN_NODE_NOT_FOUND" ->
        "要操作的控件已经不在当前界面上，请重新读取屏幕"
    "SCREEN_TARGET_NOT_EDITABLE" ->
        "这个控件不能输入文字"
    "SCREEN_TARGET_NOT_SCROLLABLE" ->
        "这个控件不能滚动"

    // ===== 平台执行 =====
    "SCREEN_COORDINATES_INVALID" ->
        "坐标超出当前屏幕范围，请重新选取位置"
    "SCREEN_ACTION_REJECTED" ->
        "系统拒绝了这次操作"
    "SCREEN_APP_NOT_FOUND" ->
        "没有找到要打开的应用，请确认它已安装"
    "SCREEN_APP_LAUNCH_DENIED" ->
        "系统不允许在后台启动这个应用，请先回到前台再试"
    "SCREEN_ACTION_OUTCOME_UNKNOWN" ->
        "操作结果无法确认，请先重新读取屏幕再决定下一步，不要直接重试"
    "SCREEN_PROVIDER_UNAVAILABLE" ->
        "当前版本未接入屏幕能力"
    "SCREEN_REQUEST_IN_PROGRESS" ->
        "上一个屏幕请求还在进行，请稍候"
    "SCREEN_RESPONSE_TOO_LARGE" ->
        "屏幕内容超出单次返回上限，请缩小范围后再读"
    "SCREEN_EXECUTION_CONTEXT_REQUIRED" ->
        "屏幕请求的执行环境不可用，请稍后重试"

    // ===== 本地控制确认 =====
    "LOCAL_CONFIRMATION_REQUIRED" ->
        "这次操作需要在手机上的确认，未确认所以没有执行"
    "LOCAL_CAPABILITY_DENIED" ->
        "本机授权不足，这次操作没有被执行"
    "GATEWAY_AUTHORIZATION_REVOKED" ->
        "当前账号的授权已被撤销，这次操作没有执行"

    // ===== 其它通用拒绝 =====
    "CAPABILITY_DENIED" ->
        "当前条件不满足，这次操作没有被执行"
    "SCHEMA_INVALID" ->
        "请求参数不合法，这次操作没有被执行"

    null -> "当前条件不满足，这次操作没有被执行"
    else -> "这次操作没有完成，请稍后重试"
}

/**
 * 权限项的短说明：用于总览里回答「这一项到底给了什么」。
 *
 * 三项权限互相独立，读取许可**不能**带出截图或控制许可，所以文案必须说清边界。
 */
fun screenPermissionDescription(permission: com.openandroidintelligence.control.ScreenPermission): String =
    when (permission) {
        com.openandroidintelligence.control.ScreenPermission.READ ->
            "读取其他应用的界面结构：文字、控件位置和窗口信息，不包含密码框内容"
        com.openandroidintelligence.control.ScreenPermission.CAPTURE ->
            "取得屏幕画面。需要单独的系统投屏授权，不会因为允许读取而自动获得"
        com.openandroidintelligence.control.ScreenPermission.CONTROL ->
            "点击、输入、滑动、返回等操作。每次操作仍需你在手机上单独确认"
    }

/** 权限项的正式名称（用于列表标题）。 */
fun screenPermissionHeadline(permission: com.openandroidintelligence.control.ScreenPermission): String =
    when (permission) {
        com.openandroidintelligence.control.ScreenPermission.READ -> "读取屏幕内容"
        com.openandroidintelligence.control.ScreenPermission.CAPTURE -> "截取屏幕画面"
        com.openandroidintelligence.control.ScreenPermission.CONTROL -> "操作其他应用"
    }

/** 会话剩余时间的用户可读表示。 */
fun formatRemaining(remainingMillis: Long): String {
    if (remainingMillis <= 0L) return "已到期"
    val totalSeconds = remainingMillis / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "剩余 $minutes 分 $seconds 秒" else "剩余 $seconds 秒"
}
