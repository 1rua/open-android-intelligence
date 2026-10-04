package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.events.*
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.Json
import java.io.IOException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlatformSnapshotRecoveryTest {
    private val profile=GatewayProfile("acct","dev","sess","https://gateway.example",accessToken="test")
    private fun transport(kind: String,pending: List<String> = listOf("req_missed")) = object: GatewayByteTransport {
        var attempts=0
        override suspend fun execute(request: WireRequest): WireResponse {
            assertEquals("/open-android-intelligence/v2/sync/snapshot",request.target)
            return WireResponse(200,emptyList(),Json.canonical(Json.of(mapOf("protocol" to "2.1",
                "data" to mapOf("baselineCursor" to "evt_platform_baseline","pendingDeviceRequests" to pending)))).toByteArray())
        }
        override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
            if (++attempts==1) throw EventCursorExpiredException()
            assertTrue(request.target.contains("cursor=evt_"+kind+"_baseline"))
            emit(("id: evt_"+kind+"_next\nevent: gateway.notice\ndata: {}\n\n").toByteArray())
        }
    }
    private class Cursors(private var cursor: String): EventCursorStore {
        override fun load(accountId: String)=cursor
        override fun save(accountId: String,cursor: String) { this.cursor=cursor }
        override fun clear(accountId: String) { cursor="" }
    }

    @Test fun bothExpiredStreamsRecoverIndependentlyAndPlatformSnapshotRestoresRequestsBeforeItsCursorCommits()=runBlocking {
        for (platformFirst in listOf(false,true)) {
            val businessCursors=Cursors("evt_business_old");val platformCursors=Cursors("evt_platform_old")
            val business=GatewayHttpClient(profile,transport("business"),{ByteArray(64)},businessCursors)
            val platform=GatewayHttpClient(profile,transport("platform",listOf("req_missed","req_missed")),{ByteArray(64)},platformCursors)
            var mirrorInstalled=false;val order=mutableListOf<String>()
            business.setCursorRecovery {
                assertEquals("evt_business_old",businessCursors.load("acct"))
                mirrorInstalled=true;"evt_business_baseline"
            }
            platform.setCursorRecovery {
                recoverPlatformSnapshot(platform,
                    syncCapabilities={order+="capabilities"},
                    acceptRequest={id ->
                        assertEquals("evt_platform_old",platformCursors.load("acct"))
                        order+=id
                    },
                    recoverLocalRequests={order+="journal"})
            }
            if (platformFirst) {
                platform.events().take(1).toList()
                assertEquals("evt_business_old",businessCursors.load("acct"))
                assertFalse(mirrorInstalled)
                business.events().take(1).toList()
            } else {
                business.events().take(1).toList()
                assertEquals("evt_platform_old",platformCursors.load("acct"))
                assertTrue(mirrorInstalled)
                platform.events().take(1).toList()
            }
            assertTrue(mirrorInstalled)
            assertEquals(listOf("capabilities","req_missed","journal"),order)
            assertTrue(platformCursors.load("acct") in setOf("evt_platform_baseline","evt_platform_next"))
            assertTrue(businessCursors.load("acct") in setOf("evt_business_baseline","evt_business_next"))
        }
    }

    @Test fun aFailedRequestRecoveryDoesNotAdvanceThePlatformCursor()=runBlocking {
        val cursors=Cursors("evt_platform_old")
        val client=GatewayHttpClient(profile,transport("platform"),{ByteArray(64)},cursors)
        var journalRecovered=false
        client.setCursorRecovery {
            recoverPlatformSnapshot(client,{}, {throw IOException("DEVICE_LOOKUP_FAILED")}, {journalRecovered=true})
        }
        assertEquals("DEVICE_LOOKUP_FAILED",runCatching {client.events().take(1).toList()}.exceptionOrNull()?.message)
        assertEquals("evt_platform_old",cursors.load("acct"))
        assertFalse(journalRecovered)
    }
}
