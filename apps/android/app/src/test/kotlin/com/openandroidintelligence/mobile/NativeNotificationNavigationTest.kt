package com.openandroidintelligence.mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * 点开提醒的时机：只有真正发布出来的工作台才算交接完成。
 *
 * 这类判定不碰 Android 框架，用纯 JVM 测试覆盖，避免把“切换 Agent 期间的
 * 时序”压在 Robolectric 的重型集成用例里。
 */
class NativeNotificationNavigationTest {
    @Test fun aSelectedButNotYetPublishedWorkbenchStillWaits() {
        assertTrue("刚点选 Agent，旧工作台还没交接完",
            nativeNavigationWaitsForWorkbench("codex", "codex", "hermes"))
        assertTrue("还没选中该 Agent",
            nativeNavigationWaitsForWorkbench("codex", "hermes", "hermes"))
        assertTrue("没有已发布的工作台",
            nativeNavigationWaitsForWorkbench("codex", "codex", null))
    }

    @Test fun thePublishedWorkbenchOfTheTargetAgentCompletesTheHandoff() {
        assertFalse(nativeNavigationWaitsForWorkbench("codex", "codex", "codex"))
    }
}
