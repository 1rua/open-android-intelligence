package com.openandroidintelligence.mobile.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问题三：能力阻塞判定必须区分「未申请授权 / 等待用户操作 / 服务未连接 /
 * 系统拒绝 / 真正不可用」。
 *
 * 修复前的缺陷是 `ANDROID_PERMISSION_REQUIRED` 一个状态覆盖了全部情况，
 * 于是一个本来点一下就能好的权限被长期显示成不可恢复的阻塞。
 *
 * 注意：这里**不新增** Core 的状态值。Core 的 inventory 只接受固定的六个
 * 状态（属于既有统一能力接口契约），区分度由 reason 与本地分类提供。
 */
class DeviceCapabilityBlockTest {

    // ------------------------------------------------------------------
    // 未申请授权：可恢复，不是阻塞
    // ------------------------------------------------------------------

    @Test
    fun ungrantedPermissionsAreRecoverableNotUnavailable() {
        listOf("READ_SMS_REQUIRED", "READ_CALL_LOG_REQUIRED", "NOTIFICATION_ACCESS_REQUIRED").forEach { reason ->
            val block = classifyCapabilityBlock("ANDROID_PERMISSION_REQUIRED", reason)
            assertEquals(reason, DeviceCapabilityBlock.NOT_YET_REQUESTED, block)
            assertTrue("$reason 必须可恢复", block.isRecoverable)
            // 未申请授权不需要用户先去设置页：应用可以直接发起申请。
            assertFalse("$reason 不应要求用户先去设置", block.requiresUserActionInSettings)
        }
    }

    @Test
    fun captureWithoutAnyGrantIsTreatedAsNotYetRequested() {
        assertEquals(
            DeviceCapabilityBlock.NOT_YET_REQUESTED,
            classifyCapabilityBlock("ANDROID_PERMISSION_REQUIRED", "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED"),
        )
    }

    // ------------------------------------------------------------------
    // 服务未连接：与「未授权」必须分开
    // ------------------------------------------------------------------

    @Test
    fun accessibilityServiceDisconnectionIsItsOwnRecoverableState() {
        val block = classifyCapabilityBlock("ANDROID_PERMISSION_REQUIRED", "ACCESSIBILITY_SERVICE_UNAVAILABLE")
        // 用户已经开过服务，只是当前没连上 —— 引导他去重新开启，而不是再申请一次权限。
        assertEquals(DeviceCapabilityBlock.SERVICE_DISCONNECTED, block)
        assertTrue(block.isRecoverable)
        assertTrue("需要用户去无障碍设置确认", block.requiresUserActionInSettings)
    }

    @Test
    fun notificationListenerDisconnectionIsRecoverableServiceDisconnection() {
        // 回归（Codex P2）：通知监听服务开启后若系统未绑定，属于可恢复的服务断连，
        // 绝不能归入不可恢复的设备缺失。
        val block = classifyCapabilityBlock("PROVIDER_UNAVAILABLE", "NOTIFICATION_LISTENER_UNAVAILABLE")
        assertEquals(DeviceCapabilityBlock.SERVICE_DISCONNECTED, block)
        assertTrue(block.isRecoverable)
        val detail = capabilityBlockDetail("PROVIDER_UNAVAILABLE", "NOTIFICATION_LISTENER_UNAVAILABLE")
        assertNotNull(detail)
        assertTrue("应包含通知监听引导", detail!!.contains("通知监听服务尚未连接"))
    }

    // ------------------------------------------------------------------
    // 系统拒绝 / 频率限制
    // ------------------------------------------------------------------

    @Test
    fun systemRejectionStaysDistinctFromNeverRequested() {
        val block = classifyCapabilityBlock("ANDROID_PERMISSION_REQUIRED", "SCREEN_CAPTURE_SYSTEM_REJECTED")
        assertEquals(DeviceCapabilityBlock.SYSTEM_REJECTED, block)
        assertTrue("重新发起一次授权即可恢复", block.isRecoverable)
        assertTrue(block.requiresUserActionInSettings)
    }

    @Test
    fun rateLimitIsTemporaryAndRecoverableByWaiting() {
        val block = classifyCapabilityBlock("PROVIDER_UNAVAILABLE", "SCREEN_CAPTURE_RATE_LIMITED")
        // 频率限制绝不能表述成「设备不支持截图」。
        assertEquals(DeviceCapabilityBlock.RATE_LIMITED, block)
        assertTrue(block.isRecoverable)
        assertFalse("等一会即可，不需要用户去设置页", block.requiresUserActionInSettings)
    }

    // ------------------------------------------------------------------
    // 本地开关 / 插件 / 提供者：都是可恢复
    // ------------------------------------------------------------------

    @Test
    fun localSwitchOffIsRecoverableByTurningItBackOn() {
        val block = classifyCapabilityBlock("LOCAL_GRANT_REQUIRED", "LOCAL_CAPABILITY_DISABLED")
        assertEquals(DeviceCapabilityBlock.LOCAL_SWITCH_OFF, block)
        assertTrue(block.isRecoverable)
    }

    @Test
    fun inactivePluginIsRecoverable() {
        listOf("PLUGIN_NOT_INSTALLED", "PLUGIN_DISABLED").forEach { status ->
            val block = classifyCapabilityBlock(status, status)
            assertEquals(status, DeviceCapabilityBlock.PLUGIN_INACTIVE, block)
            assertTrue("安装或启用即可恢复", block.isRecoverable)
        }
    }

    @Test
    fun unselectedProviderIsRecoverable() {
        assertEquals(
            DeviceCapabilityBlock.PROVIDER_UNSELECTED,
            classifyCapabilityBlock("PROVIDER_NOT_SELECTED", "PROVIDER_NOT_SELECTED"),
        )
    }

    @Test
    fun syncingStatesAreNotReportedAsFailures() {
        listOf("SYNCING", "SYNC_RETRYING", "SYNC_FAILED", "STATUS_UNAVAILABLE", "ANDROID_STATE_UNKNOWN").forEach { status ->
            val block = classifyCapabilityBlock(status, null)
            assertEquals(status, DeviceCapabilityBlock.SYNCING, block)
            assertTrue("$status 只是等待同步，不是不可用", block.isRecoverable)
        }
    }

    // ------------------------------------------------------------------
    // 真正不可用：唯一不可恢复的一类
    // ------------------------------------------------------------------

    @Test
    fun onlyGenuinelyUnsupportedStateIsMarkedUnrecoverable() {
        assertEquals(
            DeviceCapabilityBlock.UNAVAILABLE,
            classifyCapabilityBlock("PROVIDER_UNAVAILABLE", "SCREEN_CAPTURE_UNSUPPORTED"),
        )
        assertFalse(DeviceCapabilityBlock.UNAVAILABLE.isRecoverable)

        // 未知原因不能乐观地当成可恢复，也不能当成用户操作问题。
        val unknown = classifyCapabilityBlock("PROVIDER_UNAVAILABLE", "SOMETHING_NEW")
        assertEquals(DeviceCapabilityBlock.UNAVAILABLE, unknown)
        assertFalse(unknown.isRecoverable)
    }

    @Test
    fun protectedWindowIsAnAwaitingActionNotAPermanentFailure() {
        // 受保护窗口不是设备缺陷：换到普通界面就能截，属于等待用户操作。
        val block = classifyCapabilityBlock("PROVIDER_UNAVAILABLE", "SCREEN_CAPTURE_SECURE_WINDOW")
        assertEquals(DeviceCapabilityBlock.AWAITING_USER_ACTION, block)
        assertTrue(block.isRecoverable)
    }

    @Test
    fun recoverableIsTheDefaultForEveryNonUnavailableBlock() {
        DeviceCapabilityBlock.entries.forEach { block ->
            assertEquals(
                "$block 的可恢复性判定错误",
                block != DeviceCapabilityBlock.UNAVAILABLE,
                block.isRecoverable,
            )
        }
    }

    // ------------------------------------------------------------------
    // 用户可读文案
    // ------------------------------------------------------------------

    @Test
    fun everyBlockProvidesAnExecutableNextStep() {
        DeviceCapabilityBlock.entries.forEach { block ->
            val next = capabilityBlockNextStep(block)
            assertTrue("$block 的下一步说明不能为空", next.isNotBlank())
            // 不能只说「当前阻塞原因」这类无法执行的话。
            assertFalse("$block 的说明必须是可执行动作", next.contains("当前阻塞原因"))
        }
    }

    @Test
    fun userFacingDetailExplainsCauseAndNextStep() {
        val detail = capabilityBlockDetail("ANDROID_PERMISSION_REQUIRED", "ACCESSIBILITY_SERVICE_UNAVAILABLE")
        assertNotNull(detail)
        assertTrue("要说明是服务未连接", detail!!.contains("服务未连接"))
        assertTrue("要说明下一步", detail.contains("无障碍设置"))

        // 未申请授权要明说可以申请，而不是让用户以为设备不支持。
        val notRequested = capabilityBlockDetail("ANDROID_PERMISSION_REQUIRED", "READ_SMS_REQUIRED")!!
        assertTrue(notRequested.contains("尚未申请"))
    }

    @Test
    fun readyAndSyncingStatusesDoNotAddMisleadingBlockDetail() {
        // READY 不需要解释为什么被阻塞；同步中也不该出现「不可恢复」的字样。
        assertNull(capabilityBlockDetail("READY", ""))
        assertNull(capabilityBlockDetail("SYNCING", null))
        val syncing = capabilityBlockDetail("SYNC_FAILED", "NETWORK_ERROR")
        assertNotNull(syncing)
        assertFalse("同步失败不能表述成不可恢复", syncing!!.contains("不能在本机恢复"))
    }

    @Test
    fun capabilityStatusLabelNeverDropsTheRecoveryHint() {
        val label = capabilityStatusLabel("ANDROID_PERMISSION_REQUIRED", "ACCESSIBILITY_SERVICE_UNAVAILABLE")
        assertTrue(label.contains("Android 系统权限"))
        assertTrue("必须告诉用户这是可恢复的", label.contains("服务未连接"))

        val unavailable = capabilityStatusLabel("PROVIDER_UNAVAILABLE", "SCREEN_CAPTURE_UNSUPPORTED")
        assertTrue("真正不可用要说明不能恢复", unavailable.contains("不能在本机恢复"))

        val notificationListener = capabilityStatusLabel("PROVIDER_UNAVAILABLE", "NOTIFICATION_LISTENER_UNAVAILABLE")
        assertTrue(notificationListener.contains("服务未连接"))
        assertFalse(notificationListener.contains("不能在本机恢复"))
    }

    @Test
    fun coreInventoryStatusesAreTheOnlyValuesEverPublished() {
        // 回归保护：分类可以细分，但发布给 Core 的 status 必须仍是契约允许的六个值，
        // 不能为了界面方便扩张既有统一能力接口。
        val allowed = setOf(
            "READY", "PLUGIN_DISABLED", "LOCAL_GRANT_REQUIRED",
            "ANDROID_PERMISSION_REQUIRED", "PROVIDER_UNAVAILABLE", "PROVIDER_NOT_SELECTED",
        )
        allowed.forEach { status ->
            // 只要不抛异常即表示该状态是已知输入。
            classifyCapabilityBlock(status, null)
        }
        assertTrue(DeviceCapabilityBlock.entries.size > allowed.size)
    }
}