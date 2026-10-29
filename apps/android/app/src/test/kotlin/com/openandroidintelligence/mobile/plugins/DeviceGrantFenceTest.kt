package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.TestDocumentKeys
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class DeviceGrantFenceTest {
    @Test fun aStoredResultCannotLeaveTheDeviceAfterItsLocalAuthorizationRevisionChanges() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val name="local-revision-fence"
        val journal=EncryptedDocuments(context,"device-execution:$name",TestDocumentKeys)
        val expires=Instant.now().plusSeconds(60).toString()
        var lookups=0
        var mutations=0
        val transport=object: GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                if(request.method!="GET") { mutations++;error("STALE_RESULT_MUST_NOT_LEAVE_DEVICE") }
                lookups++
                return WireResponse(200,emptyList(),Json.canonical(Json.of(mapOf("protocol" to "2.1",
                    "data" to mapOf("request" to mapOf("deviceId" to "dev","pairingGeneration" to 1,
                        "grantRevision" to 7,"state" to "claimed","expiresAt" to expires))))).toByteArray())
            }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
        }
        val trust=DeveloperTrustMode()
        val host=ProductionPluginHost(context,PairingGrantStateHolder(InMemoryPairingGrantStore(),AndroidAuditStore()),
            trust,NativePluginLoader(trust),"install")
        journal.write("req_old_result",Json.canonical(Json.of(mapOf("stage" to "completed","expiresAt" to expires,
            "localAuthorizationRevision" to 1,"hostAuthorizationDigest" to executionHostAuthorizationDigest(host),
            "result" to mapOf("outcome" to "succeeded","data" to mapOf("private" to "withheld-content"))))).toByteArray())
        val http=GatewayHttpClient(GatewayProfile("acct","dev","sess","https://gateway.example"),
            transport,{ByteArray(64)},InMemoryEventCursorStore())
        val executionScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val driver=DeviceExecutionDriver(context,executionScope,http,host,"acct","dev",1,name,{7},{true},
                TestDocumentKeys,localAuthorizationRevision={2L})
            driver.recoverPending()
            withTimeout(2_000) { executionScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertEquals(1,lookups)
            assertEquals(0,mutations)
            assertNotNull(journal.read("req_old_result"))
        } finally { executionScope.cancel() }
    }

    @Test fun anUnnegotiatedModelCannotLookupClaimOrRecoverADeviceRequest() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        var calls=0
        val transport=object: GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse { calls++; error("UNEXPECTED_DEVICE_CALL") }
            override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
        }
        val trust=DeveloperTrustMode()
        val host=ProductionPluginHost(context,PairingGrantStateHolder(InMemoryPairingGrantStore(),AndroidAuditStore()),
            trust,NativePluginLoader(trust),"install")
        val http=GatewayHttpClient(GatewayProfile("acct","dev","sess","https://gateway.example"),
            transport,{ByteArray(64)},InMemoryEventCursorStore())
        val executionScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val driver=DeviceExecutionDriver(context,executionScope,http,host,"acct","dev",1,"unsupported",{7},{true},
                TestDocumentKeys,authorizationCurrent={false})
            driver.accept("req_unsupported")
            driver.recoverPending()
            assertEquals(0,calls)
        } finally { executionScope.cancel() }
    }

    @Test fun aGatewayRejectionOrStalePayloadRemovesTheEncryptedJournalWithoutClaimingOrRepeatingRecovery()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        for (status in listOf(409,200)) {
            val scopeKey="fence_"+status
            val journal=EncryptedDocuments(context,"device-execution:"+scopeKey,TestDocumentKeys)
            journal.write("req_old",Json.canonical(Json.of(mapOf("stage" to "received",
                "expiresAt" to Instant.now().plusSeconds(60).toString()))).toByteArray())
            var lookups=0
            val transport=object: GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse {
                    assertEquals("GET",request.method);lookups++
                    val envelope=if(status==409) mapOf("error" to mapOf("code" to "GRANT_STALE"))
                        else mapOf("data" to mapOf("request" to mapOf("deviceId" to "dev","pairingGeneration" to 1,"grantRevision" to 6)))
                    return WireResponse(status,emptyList(),Json.canonical(Json.of(envelope+("protocol" to "2.1"))).toByteArray())
                }
                override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
            }
            val audit=AndroidAuditStore();val grants=PairingGrantStateHolder(InMemoryPairingGrantStore(),audit)
            val trust=DeveloperTrustMode()
            val host=ProductionPluginHost(context,grants,trust,NativePluginLoader(trust),"install")
            val http=GatewayHttpClient(GatewayProfile("acct","dev","sess","https://gateway.example"),
                transport,{ByteArray(64)},InMemoryEventCursorStore())
            val executionScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            try {
                val driver=DeviceExecutionDriver(context,executionScope,http,host,"acct","dev",1,scopeKey,{7},{true},TestDocumentKeys)
                driver.recoverPending();driver.recoverPending()
                assertEquals(1,lookups);assertTrue(journal.keys().isEmpty())
            } finally { executionScope.cancel() }
        }
    }
}
