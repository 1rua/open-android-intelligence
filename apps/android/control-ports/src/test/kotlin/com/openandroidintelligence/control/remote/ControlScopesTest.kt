package com.openandroidintelligence.control.remote

import org.junit.Assert.*
import org.junit.Test

class ControlScopesTest {
    @Test
    fun 持久请求键隔离双方身份且不随重连授权或任务改变() {
        val principal = principal()
        val key = ControlRequestKey(principal, "request-00000001")
        val access = ControlAccessBinding(principal, 1, "connection-a", 1)
        val next = access.copy(authorizationEpoch = 2, connectionId = "connection-b", connectionGeneration = 2)
        assertEquals(key.storageKey, ControlRequestKey(next.principal, key.requestId).storageKey)
        listOf(principal.copy(controllerDeviceId = "other-client"), principal.copy(hostDeviceId = "other-phone"),
            principal.copy(accountId = "other-account"), principal.copy(controllerKeyFingerprint = "sha256:" + "b".repeat(64)))
            .forEach { assertNotEquals(key.storageKey, ControlRequestKey(it, key.requestId).storageKey) }
        assertNotEquals(principal.copy(accountId = "a|b", controllerDeviceId = "c").storageKey,
            principal.copy(accountId = "a", controllerDeviceId = "b|c").storageKey)
    }

    @Test
    fun 活跃任务产物拒绝另一任务设备授权或进程但允许同主体重连() {
        val principal = principal()
        val access = ControlAccessBinding(principal, 1, "connection-a", 1)
        val task = ControlTaskScope(principal, "task-a", 1, "process-a")
        val artifact = ControlArtifactScope(ControlRequestScope(task, "request-00000001"), "artifact-a", "v1")
        assertTrue(artifact.permits(access.copy(connectionId = "connection-b", connectionGeneration = 2), task, "process-a"))
        assertFalse(artifact.permits(access, task.copy(taskSessionId = "task-b"), "process-a"))
        assertFalse(artifact.permits(access, task, "process-b"))
        assertFalse(artifact.permits(access.copy(authorizationEpoch = 2), task, "process-a"))
        assertFalse(artifact.permits(access.copy(principal = principal.copy(controllerDeviceId = "other")), task, "process-a"))
    }

    @Test
    fun 非规范地址不能成为控制主体作用域() {
        listOf("https://EXAMPLE.com", "https://example.com/", "https://example.com:443", "https://u@example.com", "https://example.com?q=x")
            .forEach { assertThrows(ControlRequestException::class.java) { ControlOrigin.fromCanonical(it) } }
        assertEquals("https://xn--bcher-kva.example", ControlOrigin.fromCanonical("https://xn--bcher-kva.example").value)
        assertEquals("https://[2001:db8::1]", ControlOrigin.fromCanonical("https://[2001:db8::1]").value)
    }

    private fun principal() = RemoteControlPrincipal(ControlOrigin.fromCanonical("https://remote.example"), "account", "client", "phone",
        "sha256:" + "a".repeat(64), "sha256:" + "c".repeat(64))
}
