package com.openandroidintelligence.mobile.settings.capabilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主页面状态词与「已开启 ⇔ 已就绪」的等价关系。
 *
 * 这组断言锁的是诚实性而不是像素：任何一条事实为否，都不得显示成可用。
 */
class CapabilityStateTest {

    private fun facts(
        loadFailed: Boolean = false,
        declared: Boolean = true,
        policyEnabled: Boolean = true,
        policyReadable: Boolean = true,
        androidGranted: Boolean = true,
        providerReady: Boolean = true,
    ) = CapabilityFacts(loadFailed, declared, policyEnabled, policyReadable, androidGranted, providerReady)

    @Test
    fun everythingSatisfiedIsTheOnlyWayToShowEnabled() {
        assertEquals(CapabilityState.ON, capabilityState(facts()))
        assertTrue(isReady(facts()))
    }

    @Test
    fun aLoadFailureOutranksEveryOtherExplanation() {
        // 即使开关是关的、权限也没给，加载失败仍是第一原因：用户点开关不会有任何效果。
        val failing = facts(loadFailed = true, policyEnabled = false, androidGranted = false, providerReady = false)
        assertEquals(CapabilityState.UNAVAILABLE, capabilityState(failing))
        assertFalse(isReady(failing))
    }

    @Test
    fun aMissingDeclarationIsUnavailableRatherThanNotInstalled() {
        // 「未安装插件」是错误的说法：内建能力由 APK 提供，缺声明是加载问题。
        assertEquals(CapabilityState.UNAVAILABLE, capabilityState(facts(declared = false)))
    }

    @Test
    fun aSwitchedOffCapabilityIsReportedAsTheUsersOwnDecision() {
        assertEquals(CapabilityState.OFF, capabilityState(facts(policyEnabled = false)))
        // 关闭优先于权限：不需要为一个用户已关掉的能力催促授权。
        assertEquals(CapabilityState.OFF, capabilityState(facts(policyEnabled = false, androidGranted = false)))
    }

    @Test
    fun unreadableLocalPolicyFallsBackToOffInsteadOfLooksEnabled() {
        assertEquals(CapabilityState.OFF, capabilityState(facts(policyReadable = false)))
    }

    @Test
    fun aMissingSystemPermissionOrUnreadyServiceNeedsTheUser() {
        assertEquals(CapabilityState.NEEDS_AUTH, capabilityState(facts(androidGranted = false)))
        assertEquals(CapabilityState.NEEDS_AUTH, capabilityState(facts(providerReady = false)))
        assertFalse(isReady(facts(androidGranted = false)))
    }

    @Test
    fun everyStateHasExactlyOneShortLabelThePageCanRender() {
        assertEquals(listOf("已开启", "已关闭", "需授权", "不可用"), CapabilityState.entries.map { it.label })
    }
}
