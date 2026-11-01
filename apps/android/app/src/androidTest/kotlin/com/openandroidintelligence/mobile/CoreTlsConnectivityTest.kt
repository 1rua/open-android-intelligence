package com.openandroidintelligence.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.WireRequest
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit isolated-Core diagnostic using the same registered network owner as the App. */
@RunWith(AndroidJUnit4::class)
class CoreTlsConnectivityTest {
    @Test fun systemTrustedTlsReachesTheIsolatedCoreAuthenticationBoundary() = runBlocking {
        val address = InstrumentationRegistry.getArguments().getString("oaiCoreUrl")
            ?: error("Set -e oaiCoreUrl to the isolated HTTPS Core")
        val base = URI(address)
        require(base.scheme == "https" && base.host in setOf("127.0.0.1", "localhost") &&
            base.userInfo == null && base.query == null && base.fragment == null && base.path in setOf("", "/")) {
            "ISOLATED_CORE_URL_REQUIRED"
        }
        val transport = GatewayTransport(GatewayProfile("tls-probe", "tls-probe", "tls-probe", address))
        try {
            // A real 401 proves TLS/HTTP reached Core without an account access credential.
            val response = withTimeout(10_000) {
                transport.execute(WireRequest("GET", "/open-android-intelligence/core/v1/devices", emptyList(), ByteArray(0)))
            }
            assertEquals("CORE_AUTH_BOUNDARY_NOT_REACHED", 401, response.status)
        } catch (failure: Exception) {
            val types = generateSequence<Throwable>(failure) { it.cause }.take(8).joinToString("/") {
                it.javaClass.simpleName
            }
            throw AssertionError("ISOLATED_CORE_TLS_FAILURE:$types", failure)
        }
    }
}
