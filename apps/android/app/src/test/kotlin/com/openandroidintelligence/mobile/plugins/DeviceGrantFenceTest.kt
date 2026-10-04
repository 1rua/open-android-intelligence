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
