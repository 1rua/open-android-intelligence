package com.openandroidintelligence.mobile.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导流程状态机的测试。
 *
 * 这里锁定的是「中途退出后能恢复合理状态」与「引导完成 ≠ 连接成功」两条需求：
 * 前者靠阶段推进/回退正确，后者靠 [OnboardingState.shouldShow] 只取决于流程进度。
 */
class OnboardingStateTest {

    /** 全新安装：显示引导，从欢迎页开始。 */
    @Test
    fun `fresh install shows onboarding from welcome`() {
        val state = OnboardingState.Initial
        assertEquals(OnboardingStage.WELCOME, state.stage)
        assertEquals(true, state.shouldShow)
        assertEquals(1, state.stepIndex)
    }

    /** 阶段推进顺序：欢迎 → 权限 → 部署 → 教程 → 完成。 */
    @Test
    fun `next advances through all four screens`() {
        var state = OnboardingState.Initial
        assertEquals(OnboardingStage.PERMISSIONS, state.next())
        state = state.copy(stage = OnboardingStage.PERMISSIONS)
        assertEquals(OnboardingStage.DEPLOYMENT, state.next())
        state = state.copy(stage = OnboardingStage.DEPLOYMENT)
        assertEquals(OnboardingStage.GUIDE, state.next())
        state = state.copy(stage = OnboardingStage.GUIDE)
        assertEquals(OnboardingStage.COMPLETED, state.next())
    }

    /** 回退：欢迎页之前没有更早的页面，由调用方决定退出。 */
    @Test
    fun `previous returns null at welcome`() {
        assertEquals(null, OnboardingState.Initial.previous())
        assertEquals(
            OnboardingStage.WELCOME,
            OnboardingState.Initial.copy(stage = OnboardingStage.PERMISSIONS).previous(),
        )
        assertEquals(
            OnboardingStage.PERMISSIONS,
            OnboardingState.Initial.copy(stage = OnboardingStage.DEPLOYMENT).previous(),
        )
        assertEquals(
            OnboardingStage.DEPLOYMENT,
            OnboardingState.Initial.copy(stage = OnboardingStage.GUIDE).previous(),
        )
    }

    /** 已完成：不再自动显示引导，直接进登录。 */
    @Test
    fun `completed state stops showing onboarding`() {
        val state = OnboardingState(stage = OnboardingStage.COMPLETED, finished = true)
        assertEquals(false, state.shouldShow)
    }

    /**
     * 关键约束：`finished` 只表示「走完了引导」，与 Gateway 是否连接无关。
     *
     * 用编译期可检查的方式验证：`OnboardingState` 的公开 API 里不存在任何
     * 描述连接/认证状态的成员。这比反射字段名更稳（不依赖 JVM 合成字段），
     * 也更能表达意图——状态里根本没有容纳伪状态的位置。
     */
    @Test
    fun `finished onboarding carries no connection pretence`() {
        val state = OnboardingState(stage = OnboardingStage.COMPLETED)
        // 未标记 finished 时，即使阶段是 COMPLETED 也不应停止展示引导——
        // 完成与否只由显式标记决定，不由「走到最后一页」推断。
        assertEquals(false, state.finished)
        assertEquals(true, state.shouldShow)

        val finished = state.copy(finished = true)
        assertEquals(false, finished.shouldShow)
        assertEquals(false, finished.replay)

        // 公开成员里不允许出现**连接/认证结果**语义：引导状态不承载连接真相。
        //
        // 注意 `lastGatewayId` 是例外且必须保留：它记录「上次查看哪个教程的 Tab」，
        // 是纯界面记忆，与连接状态无关。所以这里只排除结果性词汇。
        val forbidden = listOf("connected", "authenticated", "paired", "loggedin", "session")
        val members = OnboardingState::class.java.methods
            .map { it.name.lowercase() }
            .filterNot { it.contains("$") }
        forbidden.forEach { word ->
            assertTrue(
                "OnboardingState 不应暴露与 '$word' 相关的状态成员",
                members.none { it.contains(word) },
            )
        }
    }

    /** 主动重看：即使已标记完成，也应显示引导。 */
    @Test
    fun `replay shows onboarding even when finished`() {
        val state = OnboardingState(
            stage = OnboardingStage.WELCOME,
            finished = true,
            replay = true,
        )
        assertEquals(true, state.shouldShow)
    }

    /** 阶段持久化值可往返，保证进程恢复后回到同一页。 */
    @Test
    fun `stage round trips through persisted value`() {
        OnboardingStage.entries.forEach { stage ->
            assertEquals(stage, OnboardingStage.fromPersisted(stage.persistValue))
        }
        assertEquals(null, OnboardingStage.fromPersisted("nonsense"))
        assertEquals(null, OnboardingStage.fromPersisted(null))
    }

    /** 每个页面阶段的步号正确、总数恒为 4。 */
    @Test
    fun `step indexes map to the four screens`() {
        val stages = listOf(
            OnboardingStage.WELCOME,
            OnboardingStage.PERMISSIONS,
            OnboardingStage.DEPLOYMENT,
            OnboardingStage.GUIDE,
        )
        assertEquals(listOf(1, 2, 3, 4), stages.map { OnboardingState(stage = it).stepIndex })
        stages.forEach { assertEquals(4, OnboardingState(stage = it).stepCount) }
        assertEquals(null, OnboardingState(stage = OnboardingStage.COMPLETED).stepIndex)
    }
}
