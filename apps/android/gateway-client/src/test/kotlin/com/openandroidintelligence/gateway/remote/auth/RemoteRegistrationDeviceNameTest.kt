package com.openandroidintelligence.gateway.remote.auth

import com.openandroidintelligence.gateway.remote.api.RemoteAuthCodec
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.runtime.*
import org.junit.Assert.*
import org.junit.Test

class RemoteRegistrationDeviceNameTest {
    @Test fun 注册名称独立于稳定ID和静态密钥并保持真实上游结构() {
        val identity = RemoteStaticIdentityFactory.generate(RemoteDeviceId("019a0000-0000-7000-8000-000000000001"))
        val authorization = RemoteAccountAuthorizationToken("a".repeat(32))
        for (role in RemoteRole.entries) {
            val first = RemoteAuthCodec.registerAccountRole(identity, role, authorization, "系统手机名")
            val renamed = RemoteAuthCodec.registerAccountRole(identity, role, authorization, "用户新名称")
            assertEquals("/api/v1/devices/register", renamed.path)
            assertEquals(authorization.value, renamed.bearerToken)
            val originalDevice = first.body.value("device").objectValue()
            val renamedDevice = renamed.body.value("device").objectValue()
            assertEquals("用户新名称", renamedDevice.requiredText("name"))
            assertEquals(originalDevice.requiredText("deviceId"), renamedDevice.requiredText("deviceId"))
            assertEquals(originalDevice.requiredText("identityKey"), renamedDevice.requiredText("identityKey"))
            assertEquals(role.wireValue, renamedDevice.requiredText("role"))
        }
    }
}
