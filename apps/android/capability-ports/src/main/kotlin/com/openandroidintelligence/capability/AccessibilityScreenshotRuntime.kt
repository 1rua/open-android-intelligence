package com.openandroidintelligence.capability

/**
 * 无障碍截图运行时 seam（系统调用边界）。
 *
 * 与 [ScreenProjectionRuntime] 同一套设计：真实实现放在 screen-control 模块
 * 里持有 `AccessibilityService` 实例，编排层只依赖这个接口，因此授权决策与
 * 失败分类可以用 fake 在 JVM 上完整驱动，不需要真机或模拟器。
 *
 * 三个查询刻意分开，因为它们对应三种完全不同的用户处境：
 * - [isServiceEnabledInSettings]：用户是否在系统设置里开启过（可从任意 Context 查询）；
 * - [isServiceConnected]：系统当前是否真的把服务绑定起来了（开启过 ≠ 已连接）；
 * - [hasScreenshotCapability]：已连接的服务信息是否声明了 `canTakeScreenshot`。
 *
 * 把这三件事混成一个「可用」布尔值，界面就无法告诉用户该去哪个页面做什么。
 */
interface AccessibilityScreenshotRuntime {
    /**
     * 用户是否已在系统设置中开启本应用的无障碍服务。
     *
     * 这个查询在服务未连接时依然可以回答，因此能区分「从未授权」与「服务断连」。
     */
    fun isServiceEnabledInSettings(): Boolean

    /** 服务当前是否由系统绑定并可用。设置里开启过但与系统断连时必须返回 false。 */
    fun isServiceConnected(): Boolean

    /**
     * 已连接的服务是否声明了截图能力（`AccessibilityServiceInfo.canTakeScreenshot`）。
     * 未连接时无法判断能力，返回 false。
     */
    fun hasScreenshotCapability(): Boolean

    /**
     * 取一帧当前屏幕。每次调用最多尝试一次，不做内部重试与重建。
     *
     * 成功返回 [ScreenCapture]，失败返回可分类的 [ScreenCaptureFailure]；
     * 实现不得用假帧、旧帧或空字节冒充成功。
     */
    suspend fun takeScreenshot(): ScreenCaptureOutcome
}

/** 无障碍取帧的单次结果。 */
sealed interface ScreenCaptureOutcome {
    data class Success(val capture: ScreenCapture) : ScreenCaptureOutcome

    data class Failure(val reason: ScreenCaptureFailure) : ScreenCaptureOutcome
}