package com.openandroidintelligence.conversation.remote

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RemoteIdentityTest {
    @Test
    fun sameNativeSessionIdsRemainIsolatedAcrossEveryPersistentCoordinate() {
        val original = remoteEndpoint()
        val endpoints = listOf(
            original,
            original.copy(origin = RemoteServiceOrigin.fromCanonicalOrigin("https://other.example")),
            original.copy(accountId = "account-b"),
            original.copy(hostId = "host-b"),
            original.copy(backendId = BackendId("codex")),
            original.copy(workspaceId = "workspace-b"),
        )
        val cache = endpoints.associate { SessionKey(it, "hermes:shared-session") to it }

        assertEquals(endpoints.size, cache.size)
        endpoints.forEach { assertEquals(it, cache[SessionKey(it, "hermes:shared-session")]) }
    }

    @Test
    fun structuredCoordinatesCannotCollideThroughDelimitersOrDisplayNames() {
        val first = remoteEndpoint().copy(accountId = "account|host", hostId = "workspace")
        val second = remoteEndpoint().copy(accountId = "account", hostId = "host|workspace")
        assertNotEquals(first, second)

        val endpoint = AgentEndpoint(first, "同名电脑 · Hermes")
        val renamed = endpoint.copy(displayName = "重命名后的电脑")
        assertEquals(endpoint.key, renamed.key)
        assertEquals(EndpointAvailability.UNKNOWN, endpoint.availability)
    }

    @Test
    fun opaqueNativeSessionIdsKeepAcpPrefixesAndAreNotConvertedIntoGatewayIds() {
        listOf("hermes:session/一", "cursor:session-with:colon", "a".repeat(2048)).forEach { id ->
            assertEquals(id, SessionKey(remoteEndpoint(), id).nativeSessionId)
        }
        assertFailsWith<IllegalArgumentException> { SessionKey(remoteEndpoint(), "") }
    }

    @Test
    fun canonicalOriginAcceptsPunycodeIpv6AndSeparateHttpScopes() {
        listOf("https://xn--bcher-kva.example", "https://[2001:db8::1]", "http://[::1]:8080", "http://localhost:0").forEach { value ->
            assertEquals(value, RemoteServiceOrigin.fromCanonicalOrigin(value).value)
        }
        assertNotEquals(
            RemoteServiceOrigin.fromCanonicalOrigin("http://remote.example"),
            RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"),
        )
        assertNotEquals(
            RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example:8443"),
            RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"),
        )
    }

    @Test
    fun canonicalOriginRejectsUnnormalizedOrExtraUrlPartsBeforeTheyCanBecomeScopes() {
        listOf(
            "https://bücher.example", "HTTPS://remote.example", "https://REMOTE.example",
            "https://remote.example:443", "http://remote.example:80", "https://remote.example:",
            "https://[2001:DB8::1]", "https://[fe80::1%25eth0]", "https://2001:db8::1",
            "https://remote.example/", "https://remote.example/base", "https://user@remote.example",
            "https://remote.example?", "https://remote.example#fragment", " https://remote.example",
            "https://remote.example:65536", "wss://remote.example",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { RemoteServiceOrigin.fromCanonicalOrigin(value) }
        }
    }

    @Test
    fun activeOwnersRejectOldConnectionsBackendInstancesAndOtherSessions() {
        val current = remoteOwner()
        val stale = listOf(
            current.copy(connectionGeneration = current.connectionGeneration - 1),
            current.copy(connectionId = "old-connection"),
            current.copy(backendInstanceId = "old-backend"),
            current.copy(session = current.session.copy(nativeSessionId = "hermes:other")),
            current.copy(session = current.session.copy(endpoint = remoteEndpoint().copy(hostId = "host-b"))),
        )

        assertTrue(current.isCurrent(current.copy()))
        stale.forEach { assertFalse(it.isCurrent(current)) }
        assertEquals(current.session, current.copy(connectionId = "new-connection").session)
    }
}

internal fun remoteEndpoint() = EndpointKey(
    origin = RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"),
    accountId = "account-a",
    hostId = "host-a",
    backendId = BackendId("hermes"),
    workspaceId = "workspace-a",
)

internal fun remoteOwner() = SessionOwner(
    session = SessionKey(remoteEndpoint(), "hermes:session-a"),
    backendInstanceId = "backend-instance-a",
    connectionId = "connection-a",
    connectionGeneration = 2,
)
