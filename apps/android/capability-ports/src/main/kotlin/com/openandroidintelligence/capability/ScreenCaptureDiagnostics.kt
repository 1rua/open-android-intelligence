package com.openandroidintelligence.capability

/**
 * 截图来源：谁在实际提供这一帧像素。
 *
 * 这不是「能力开关」，只是诊断事实 —— Agent 与 Core 的授权判定仍走既有
 * 统一能力接口，与这里的取景来源无关。
 */
enum class ScreenCaptureOrigin {
    /** Android 11+ 无障碍服务 `takeScreenshot()`：按需单帧，不需要重复系统确认。 */
    ACCESSIBILITY,

    /** MediaProjection：需要用户明确同意系统投屏对话框，作为备用方案保留。 */
    MEDIA_PROJECTION,
}

/**
 * 截图不可用的诊断结论。
 *
 * 每条都必须是「可执行的下一步」，不能是含糊的失败：
 * 失败、系统拒绝、频率限制、服务断连四类必须彼此可区分，否则用户无法判断
 * 是该重试、该等待，还是该去系统设置里重新开启服务。
 */
enum class ScreenCaptureFailure {
    /**
     * 尚未取得任何截图授权：既没有连接无障碍服务，也没有 MediaProjection 系统同意。
     * 下一步：走其中一条授权路径，这不是错误。
     */
    NOT_AUTHORIZED,

    /**
     * 无障碍服务声明了截图能力但当前未连接（用户关掉了服务或系统解绑）。
     * 下一步：回到系统无障碍设置重新开启并确认服务已连接。
     */
    SERVICE_DISCONNECTED,

    /**
     * 系统侧的授权被拒绝：用户拒绝了投屏对话框，或系统判定该服务不允许截图。
     * 下一步：重新发起一次显式授权；不能静默重试。
     */
    SYSTEM_REJECTED,

    /**
     * 触碰了系统的截屏保护（受保护窗口 / FLAG_SECURE / 安全窗口）。
     * 下一步：没有技术绕法，只能让用户换到非受保护界面。
     */
    SECURE_WINDOW,

    /**
     * 触发了系统截图频率限制（Android 对无障碍截图的限速）。
     * 下一步：等待下个允许窗口后重试；属于暂时状态，不是不可恢复阻塞。
     */
    RATE_LIMITED,

    /**
     * 截帧动作本身失败（编码、取帧、显示已失效等）。
     * 下一步：可重试；连续失败才需要检查设备状态。
     */
    CAPTURE_FAILED,

    /** 设备锁屏。下一步：解锁后重试。 */
    DEVICE_LOCKED,

    /**
     * 系统版本不支持无障碍截图（低于 Android 11 / API 30）。
     * 下一步：只能使用 MediaProjection 备用路径。
     */
    UNSUPPORTED,
    ;

    /**
     * 该原因是否可以由用户操作或简单重试恢复。
     *
     * 用于问题三：把「尚未申请授权、等待用户操作、频率限制」这类状态与
     * 真正的不可用区分开，禁止长期标成不可恢复的阻塞。
     */
    val isRecoverable: Boolean
        get() = when (this) {
            NOT_AUTHORIZED, SERVICE_DISCONNECTED, SYSTEM_REJECTED, RATE_LIMITED, CAPTURE_FAILED, DEVICE_LOCKED -> true
            // 受保护窗口只能靠用户切界面，不是稳定可恢复的本机能力；
            // 不支持则是设备事实，重试没有意义。
            SECURE_WINDOW, UNSUPPORTED -> false
        }

    /** 稳定协议码：跨 Core / 客户端 / 诊断日志使用，不得随文案变化。 */
    val code: String
        get() = when (this) {
            NOT_AUTHORIZED -> "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED"
            SERVICE_DISCONNECTED -> "ACCESSIBILITY_SERVICE_UNAVAILABLE"
            SYSTEM_REJECTED -> "SCREEN_CAPTURE_SYSTEM_REJECTED"
            SECURE_WINDOW -> "SCREEN_CAPTURE_SECURE_WINDOW"
            RATE_LIMITED -> "SCREEN_CAPTURE_RATE_LIMITED"
            CAPTURE_FAILED -> "SCREEN_CAPTURE_FAILED"
            DEVICE_LOCKED -> "SCREEN_DEVICE_LOCKED"
            UNSUPPORTED -> "SCREEN_CAPTURE_UNSUPPORTED"
        }
}

/**
 * 把 Android `AccessibilityService` 的截图错误常量映射为可诊断原因。
 *
 * 常量数值取自 API 36 的 `AccessibilityService`：
 * 1 internal error、2 no accessibility access、3 interval time short、
 * 4 invalid display、5 invalid window、6 secure window。
 * 这里按数值收口，避免引用在低版本上不存在的字段（这些字段 API 30/34 才引入）。
 */
fun accessibilityScreenshotFailure(errorCode: Int): ScreenCaptureFailure = when (errorCode) {
    // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT：系统限速，属于可恢复的暂时状态。
    3 -> ScreenCaptureFailure.RATE_LIMITED
    // ERROR_TAKE_SCREENSHOT_SECURE_WINDOW：受保护窗口，系统直接拒绝，没有绕过路径。
    6 -> ScreenCaptureFailure.SECURE_WINDOW
    // ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS：系统判定该服务无权截图。
    2 -> ScreenCaptureFailure.SYSTEM_REJECTED
    // internal error / invalid display / invalid window：本次取帧失败，可重试。
    1, 4, 5 -> ScreenCaptureFailure.CAPTURE_FAILED
    else -> ScreenCaptureFailure.CAPTURE_FAILED
}

/**
 * 一次截图尝试的真实结论。
 *
 * 用密封类型而不是「可空帧 + 字符串错误」，避免调用方把「失败」误当成
 * 「成功但没有数据」，也避免任何一处静默退化。
 */
sealed interface ScreenCaptureResult {
    data class Success(val capture: ScreenCapture, val origin: ScreenCaptureOrigin) : ScreenCaptureResult

    data class Failure(val reason: ScreenCaptureFailure, val origin: ScreenCaptureOrigin?) : ScreenCaptureResult
}