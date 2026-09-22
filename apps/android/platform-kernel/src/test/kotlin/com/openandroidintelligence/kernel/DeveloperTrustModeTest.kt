package com.openandroidintelligence.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开发者信任模式的持久化语义。
 *
 * 持久化恢复的只是「开关状态」这个事实；启用模式所需的确认文本校验永远
 * 作用于每一次真实交互——上次开过不能替这次点头。
 */
class DeveloperTrustModeTest {

    /** 记录型后端：可注入读取值、注入读取失败、记录每次写入。 */
    private class RecordingPersistence : TrustModePersistence {
        var stored: Boolean = false
        var loadFailure: Throwable? = null
        val saved = mutableListOf<Boolean>()

        override fun load(): Boolean {
            loadFailure?.let { throw it }
            return stored
        }

        override fun save(enabled: Boolean) {
            saved += enabled
        }
    }

    private fun acknowledged() = DeveloperTrustMode.Acknowledgement(
        DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT,
    )

    private fun wrongAcknowledgement() = DeveloperTrustMode.Acknowledgement("我同意，别问了")

    @Test
    fun constructorRestoresThePersistedEnabledState() {
        val persistence = RecordingPersistence().apply { stored = true }

        val trust = DeveloperTrustMode(persistence)

        assertTrue("构造时必须从后端如实恢复已开启状态", trust.isEnabled())
    }

    @Test
    fun enableAndDisablePersistTheirTransitions() {
        val persistence = RecordingPersistence()
        val trust = DeveloperTrustMode(persistence)

        assertTrue(trust.enable(acknowledged()))
        assertEquals("开启成功后必须落盘 true", listOf(true), persistence.saved)

        trust.disable()

        assertEquals("关闭必须如实落盘 false", listOf(true, false), persistence.saved)
        assertFalse(trust.isEnabled())
    }

    @Test
    fun corruptedPersistenceIsTreatedAsDisabled() {
        val persistence = RecordingPersistence().apply {
            loadFailure = IllegalStateException("corrupted record")
        }

        val trust = DeveloperTrustMode(persistence)

        assertFalse("损坏的持久化数据必须按未开启处理", trust.isEnabled())
        // 读取失败不得崩溃到无法继续使用：照常走完整开启流程并落盘。
        assertTrue(trust.enable(acknowledged()))
        assertTrue(trust.isEnabled())
        assertEquals(listOf(true), persistence.saved)
    }

    @Test
    fun withoutPersistenceTheBehaviourStaysExactlyAsBefore() {
        val trust = DeveloperTrustMode()

        assertFalse(trust.isEnabled())
        assertFalse("错误确认文本仍然被拒绝", trust.enable(wrongAcknowledgement()))
        assertFalse(trust.isEnabled())
        assertTrue(trust.enable(acknowledged()))
        assertTrue(trust.isEnabled())
        assertTrue("重复开启保持幂等", trust.enable(acknowledged()))
        trust.disable()
        assertFalse(trust.isEnabled())
    }

    @Test
    fun wrongAcknowledgementIsRejectedAndNeverPersistedEvenWhenRestored() {
        val persistence = RecordingPersistence().apply { stored = true }
        val trust = DeveloperTrustMode(persistence)
        assertTrue("恢复的是状态，不是确认", trust.isEnabled())

        assertFalse("持久化恢复不得绕过确认文本校验", trust.enable(wrongAcknowledgement()))
        assertTrue("被拒绝的确认不得改动开关状态", trust.isEnabled())
        assertTrue("被拒绝的确认不得落盘", persistence.saved.isEmpty())
    }
}
