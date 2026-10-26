package com.openandroidintelligence.mobile.onboarding

/**
 * 引导流程的阶段。
 *
 * 用「阶段」而不是单一布尔，是因为需求要求「中途退出后应能恢复合理的引导状态」：
 * 用户在第 3 步杀掉进程，下次应该回到第 3 步附近，而不是从头再来。
 *
 * 阶段只描述**用户走到哪一步**，不描述身份或连接状态——登录是否成功始终由
 * `ConnectionPhase` 决定，两者不得混淆（引导完成 ≠ Gateway 已连接）。
 */
enum class OnboardingStage(val persistValue: String) {
    /** 欢迎介绍页。 */
    WELCOME("welcome"),

    /** 系统权限页。 */
    PERMISSIONS("permissions"),

    /** Agent Gateway 部署状态选择页。 */
    DEPLOYMENT("deployment"),

    /** Gateway 部署教程页。 */
    GUIDE("guide"),

    /** 引导已完成，后续启动直接进入登录/工作台。 */
    COMPLETED("completed"),
    ;

    companion object {
        fun fromPersisted(value: String?): OnboardingStage? =
            entries.firstOrNull { it.persistValue == value }
    }
}

/**
 * 引导流程的对外可见状态。
 *
 * @param stage 当前阶段。
 * @param finished 是否已走完整个流程（持久化记录）。
 * @param lastGatewayId 上次查看教程的 Gateway；恢复时据此回到同一个 Tab。
 * @param replay 是否由用户在设置里主动「重新查看」。重看不改写持久化的完成状态，
 *   退出后仍回到原来的主界面。
 */
data class OnboardingState(
    val stage: OnboardingStage = OnboardingStage.WELCOME,
    val finished: Boolean = false,
    val lastGatewayId: String? = null,
    val replay: Boolean = false,
    /**
     * 用户是否在**本次会话**里主动按返回退出了引导。
     *
     * 只存在于内存，不进 SharedPreferences：它表达的是「这次先别烦我」，
     * 而不是「以后都不要引导了」。下次启动时该标志自然为 false，引导照常展示。
     */
    val dismissedThisSession: Boolean = false,
) {
    /**
     * 本次启动是否应展示引导。
     *
     * 只有「未完成」才自动展示；已完成一律直达登录。用户主动重看时由
     * [replay] 单独驱动，不修改 [finished]。本次会话已主动跳过时不再展示。
     */
    val shouldShow: Boolean get() = !dismissedThisSession && (replay || !finished)

    /** 流程内的下一个阶段；[OnboardingStage.COMPLETED] 没有下一个。 */
    fun next(): OnboardingStage = when (stage) {
        OnboardingStage.WELCOME -> OnboardingStage.PERMISSIONS
        OnboardingStage.PERMISSIONS -> OnboardingStage.DEPLOYMENT
        OnboardingStage.DEPLOYMENT -> OnboardingStage.GUIDE
        OnboardingStage.GUIDE -> OnboardingStage.COMPLETED
        OnboardingStage.COMPLETED -> OnboardingStage.COMPLETED
    }

    /** 流程内的上一个阶段；欢迎页之前没有更早的页面（由调用方决定退出）。 */
    fun previous(): OnboardingStage? = when (stage) {
        OnboardingStage.WELCOME -> null
        OnboardingStage.PERMISSIONS -> OnboardingStage.WELCOME
        OnboardingStage.DEPLOYMENT -> OnboardingStage.PERMISSIONS
        OnboardingStage.GUIDE -> OnboardingStage.DEPLOYMENT
        OnboardingStage.COMPLETED -> null
    }

    /** 页面对应的进度序号（1..4）；非页面阶段返回 null。 */
    val stepIndex: Int?
        get() = when (stage) {
            OnboardingStage.WELCOME -> 1
            OnboardingStage.PERMISSIONS -> 2
            OnboardingStage.DEPLOYMENT -> 3
            OnboardingStage.GUIDE -> 4
            OnboardingStage.COMPLETED -> null
        }

    /** 页面总数：欢迎、权限、部署选择、教程。 */
    val stepCount: Int get() = 4

    companion object {
        /** 全新安装时的初始状态。 */
        val Initial = OnboardingState()
    }
}
