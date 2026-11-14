package com.openandroidintelligence.mobile.onboarding

import com.openandroidintelligence.mobile.onboarding.data.RemoteTutorials
import org.junit.Assert.*
import org.junit.Test

/** Remote 连接指引保留真实步骤、回退、验证与无虚构命令约束。 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class RemoteTutorialsTest {
    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
    @Test fun `唯一指引面向Remote且旧教程选择回到Remote`() {
        assertEquals(listOf("remote"), RemoteTutorials.all(context).map { it.id })
        listOf(null, "core", "openclaw", "hermes", "dsh", "unknown").forEach { assertEquals("remote", RemoteTutorials.byId(context, it).id) }
    }
    @Test fun `指引覆盖登录发现手动选择与本机授权并以真实状态验证`() {
        val guide = RemoteTutorials.all(context).single()
        assertTrue(guide.steps.size >= 3)
        val text = guide.steps.joinToString("\n") { it.title + it.body }
        listOf("登录", "同一个 Remote 账号", "Host", "后端", "工作区", "本机", "确认").forEach { assertTrue(it, text.contains(it)) }
        assertFalse(guide.steps.last().verifyHint.isNullOrBlank())
        assertTrue(guide.steps.all { it.terminal == null })
    }
    @Test fun `官方来源与指引元数据完整`() {
        val guide = RemoteTutorials.all(context).single()
        assertEquals("https://github.com/liguobao/ds-harness-remote", guide.repoUrl)
        assertTrue(guide.chips.isNotEmpty())
        assertTrue(guide.tagline.isNotBlank() && guide.displayName.isNotBlank() && guide.shortName.isNotBlank())
    }
}
