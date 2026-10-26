package com.openandroidintelligence.tailscale.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class TailscaleCompanionTest {

    @Test
    fun companionProtocolContainsNoGatewayCredentialFields() {
        // 读取 AIDL 契约，确保没有泄露任何凭据或敏感字段
        val candidates = listOf(
            File("apps/android/companion-bridge/src/main/aidl/com/openandroidintelligence/companion/ICompanionTransport.aidl"),
            File("../companion-bridge/src/main/aidl/com/openandroidintelligence/companion/ICompanionTransport.aidl"),
            File("../../companion-bridge/src/main/aidl/com/openandroidintelligence/companion/ICompanionTransport.aidl"),
        )
        val aidlFile = candidates.firstOrNull { it.exists() }
        assertNotNull("ICompanionTransport.aidl 必须存在", aidlFile)
        val aidlText = aidlFile!!.readText()

        listOf("password", "refresh", "accessToken", "devicePrivateKey", "secret").forEach { forbidden ->
            assertFalse(
                "AIDL 接口禁止包含凭据字段: $forbidden",
                aidlText.contains(forbidden, ignoreCase = true),
            )
        }
    }
}

