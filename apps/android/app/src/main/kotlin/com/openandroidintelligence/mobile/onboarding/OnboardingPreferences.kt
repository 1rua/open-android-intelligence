package com.openandroidintelligence.mobile.onboarding

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 引导流程的持久化。
 *
 * 沿用本项目既有的持久化约定（与 [com.openandroidintelligence.mobile.AppearancePreferences]
 * 同构）：SharedPreferences + `commit()` + `check()`，**不用** `apply()`。
 * 理由是同样的：引导完成状态一旦「写失败但界面显示成功」，用户下次启动会看到
 * 与上次不一致的界面，而且没有任何地方能察觉。
 *
 * 刻意不引入 DataStore：本项目其余 14 个偏好命名空间都用 SharedPreferences，
 * 为一个开关单独引入第二套持久化机制会制造维护分叉。
 */
class OnboardingPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /** 记录流程进度。仅当阶段真正变化时才落盘，避免频繁写。 */
    fun setStage(stage: OnboardingStage) {
        val current = _state.value
        if (current.stage == stage) return
        persist(current.copy(stage = stage))
    }

    /** 记录教程页当前查看的 Gateway，供恢复时回到同一个 Tab。 */
    fun setLastGateway(gatewayId: String) {
        val current = _state.value
        if (current.lastGatewayId == gatewayId) return
        persist(current.copy(lastGatewayId = gatewayId))
    }

    /**
     * 标记引导完成。
     *
     * 调用时机是**转场动画启动之后**，不是点击瞬间：先让用户看到转场，再落盘。
     * 若此时进程被杀，写入可能丢失，用户下次仍会看到引导——这是可接受的降级
     * （重复看一遍引导），远好于「写成功但页面没走」的假完成。
     */
    fun markCompleted() {
        // 必须同时清除 replay：若不清除，重看模式下走完流程后
        // shouldShow 仍为 true（因为 replay=true），而 stage 已是 COMPLETED，
        // OnboardingFlow 对 COMPLETED 渲染 Unit —— 结果是一片空白，回不到登录页。
        persist(
            _state.value.copy(
                stage = OnboardingStage.COMPLETED,
                finished = true,
                replay = false,
            ),
        )
    }

    /**
     * 进入「重新查看」模式。
     *
     * 不改写 [OnboardingState.finished]：用户退出重看后，下次启动仍直接进主界面。
     */
    fun startReplay() {
        val current = _state.value
        _state.value = current.copy(stage = OnboardingStage.WELCOME, replay = true)
    }

    /** 退出重看模式，回到原本的完成态。 */
    fun endReplay() {
        val current = _state.value
        if (!current.replay) return
        _state.value = current.copy(
            stage = if (current.finished) OnboardingStage.COMPLETED else OnboardingStage.WELCOME,
            replay = false,
        )
    }

    /**
     * 用户在首次启动的欢迎页按了返回：**本次会话**内不再展示引导，
     * 但不写 `finished` —— 用户并没有走完流程，下次启动仍应看到引导。
     *
     * 只存在于内存（不改 SharedPreferences）：这是一个「当前这次先别烦我」的临时选择，
     * 不是持久偏好。持久化会让「按了一次返回」永久跳过引导，与需求不符。
     */
    fun dismissForCurrentSession() {
        val current = _state.value
        _state.value = current.copy(
            stage = OnboardingStage.WELCOME,
            replay = false,
            dismissedThisSession = true,
        )
    }

    private fun read(): OnboardingState {
        val stage = OnboardingStage.fromPersisted(preferences.getString(KEY_STAGE, null))
            ?: OnboardingStage.WELCOME
        return OnboardingState(
            stage = stage,
            // 只有显式记录为已完成才算完成；阶段是 completed 也算（双保险，防字段缺失）。
            finished = preferences.getBoolean(KEY_FINISHED, false) ||
                stage == OnboardingStage.COMPLETED,
            lastGatewayId = preferences.getString(KEY_LAST_GATEWAY, null)
                ?.takeIf { it.isNotBlank() },
            replay = false,
        )
    }

    private fun persist(next: OnboardingState) {
        check(
            preferences.edit()
                .putString(KEY_STAGE, next.stage.persistValue)
                .putBoolean(KEY_FINISHED, next.finished)
                .putString(KEY_LAST_GATEWAY, next.lastGatewayId)
                .commit(),
        ) { "ONBOARDING_PREFERENCE_PERSISTENCE_FAILED" }
        _state.value = next
    }

    private companion object {
        const val PREFERENCES_NAME = "open_android_intelligence_onboarding"
        const val KEY_STAGE = "onboarding_stage"
        const val KEY_FINISHED = "onboarding_finished"
        const val KEY_LAST_GATEWAY = "onboarding_last_gateway"
    }
}
