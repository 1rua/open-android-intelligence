package com.openandroidintelligence.mobile.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.mobile.BuildConfig
import com.openandroidintelligence.mobile.TestDocumentKeys
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lost ACKs, local changes, process restarts and foreign revisions use the production publisher/journal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CapabilityPublisherTest {
    private class Remote : GatewayByteTransport {
        var revision = 1
        var digest: String? = null
        var version = 2
        var failBefore = false
        var failAfter = false
        var policyRevision = 0
        val bodies = mutableListOf<JsonValue.JObject>()
        override suspend fun execute(request: WireRequest): WireResponse {
            val data = if (request.method == "GET") mapOf("grantRevision" to revision, "grantDigest" to digest,
                "capabilityPublicationVersion" to version, "capabilityPolicy" to mapOf("revision" to policyRevision, "enabled" to emptyMap<String,Boolean>()))
            else {
                val body = JsonFields.obj(Json.parse(request.body.decodeToString()))!!
                bodies += body
                if (failBefore) { failBefore = false; throw IOException("POST not accepted") }
                check(JsonFields.int(body,"expectedGrantRevision") == revision) { "GRANT_STALE" }
                val payload = body.fields.filter { it.first != "expectedGrantRevision" }
                val next = Json.sha256(JsonValue.JObject(payload))
                if (next != digest) revision++
                digest = next
                if (failAfter) { failAfter = false; throw IOException("POST accepted, ACK lost") }
                mapOf("grantRevision" to revision, "grantDigest" to digest)
            }
            return WireResponse(200,emptyList(),Json.canonical(Json.of(mapOf("protocol" to "2.1","data" to data))).encodeToByteArray())
        }
        override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
    }
    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val binding = PairingGrantBinding("https://gateway.example","account","installation")
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(),AndroidAuditStore()).apply { bind(binding) }
        val trust = DeveloperTrustMode()
        val host = ProductionPluginHost(context,grants,trust,NativePluginLoader(trust),"installation")
        val remote = Remote()
        var foreignChanges = 0
        val scope = "publisher-test-${java.util.UUID.randomUUID()}"
        private val http = GatewayHttpClient(GatewayProfile("account","device","session","https://gateway.example",accessToken="test"),
            remote,{ ByteArray(64) },InMemoryEventCursorStore())
        fun publisher() = CapabilityPublisher(context,http,scope,remote.revision,{ grants.state.value?.revision ?: 0L },host,
            foreignChange = { foreignChanges++; grants.clearCurrent(); grants.bind(binding) },keyProvider=TestDocumentKeys)
    }

    @Test fun acceptedPublicationWithLostAckIsRecoveredByGetAfterProcessRestart() = runBlocking {
        val fixture = Fixture()
        fixture.remote.failAfter = true
        assertNotNull(runCatching { fixture.publisher().sync() }.exceptionOrNull())
        assertEquals(2,fixture.remote.revision)
        val restarted = fixture.publisher()
        assertTrue(restarted.observeChange(2,fixture.remote.digest))
        restarted.sync()
        assertEquals(1,fixture.remote.bodies.size)
        assertEquals(0,fixture.foreignChanges)
        assertEquals(2,restarted.grantRevision)
        assertNull(EncryptedDocuments(fixture.context,"capability-publication:${fixture.scope}",TestDocumentKeys).read("pending"))
    }

    @Test fun unsentPendingPublicationIsReplacedAfterLocalAuthorizationChanges() = runBlocking {
        val fixture = Fixture()
        fixture.grants.updatePrimitive("kernel.sms.read",true)
        fixture.remote.failBefore = true
        val publisher = fixture.publisher()
        assertNotNull(runCatching { publisher.sync() }.exceptionOrNull())
        fixture.grants.updatePrimitive("kernel.sms.read",false)
        fixture.publisher().sync()
        assertEquals(listOf(1L,2L),fixture.remote.bodies.map { JsonFields.long(it,"localGrantRevision") })
        assertEquals(0,fixture.foreignChanges)
    }

    @Test fun foreignRevisionClearsLocalGrantOnceAndNeverReusesOldPendingBody() = runBlocking {
        val fixture = Fixture()
        fixture.grants.updatePrimitive("kernel.sms.read",true)
        fixture.remote.failBefore = true
        val publisher = fixture.publisher()
        assertNotNull(runCatching { publisher.sync() }.exceptionOrNull())
        fixture.remote.revision = 7
        fixture.remote.digest = null
        publisher.sync()
        assertEquals(1,fixture.foreignChanges)
        assertTrue(fixture.grants.state.value!!.granted.isEmpty())
        assertEquals(0L,JsonFields.long(fixture.remote.bodies.last(),"localGrantRevision"))
        publisher.sync()
        assertEquals(1,fixture.foreignChanges)
        assertEquals(2,fixture.remote.bodies.size)
    }

    @Test fun gatewayPolicyChangeDoesNotRevokeAndroidGrantOrRepublishBindings() = runBlocking {
        val fixture = Fixture()
        fixture.grants.updatePrimitive("kernel.notifications.read",true)
        val publisher = fixture.publisher()
        publisher.sync()
        fixture.remote.policyRevision = 3
        publisher.sync()
        assertEquals(1,fixture.remote.bodies.size)
        assertEquals(0,fixture.foreignChanges)
        assertTrue("kernel.notifications.read" in fixture.grants.state.value!!.granted)
        assertEquals(3,JsonFields.int(publisher.gatewayPolicy.value,"revision"))
    }

    @Test fun oldGatewayStillReceivesItsOriginalPublicationShape() = runBlocking {
        val fixture = Fixture()
        fixture.remote.version = 1
        fixture.publisher().sync()
        assertNull(JsonFields.field(fixture.remote.bodies.single(),"inventory"))
    }

    @Test fun unreadableInstallationOmitsInventoryAndLostAckRecoveryDoesNotClaimAbsence() = runBlocking {
        assumeTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        val fixture = Fixture()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        fixture.host.installStore.installRoot.deleteRecursively()
        try {
            val host = fixture.host
            host.attach(PluginKernel(HostEnvelope(host.hostCapabilities),PhoneLimits(host.phoneCapabilities),host.runtimes,
                AndroidAuditStore(),fixture.trust,host.nativeLoader,host.selector,
                { fixture.grants.currentKernelGrant(it) },host::mediate))
            fixture.publisher().sync()
            assertNotNull(JsonFields.field(fixture.remote.bodies.last(),"inventory"))

            // An installed directory whose declaration cannot be verified is
            // not evidence that a notification provider was never installed.
            val manifest = File(host.installStore.installRoot,"org.example.broken/manifest.json")
            manifest.parentFile!!.mkdirs(); manifest.writeText("{")
            host.reload()
            assertEquals("PLUGIN_MANIFEST_INVALID",host.loadFailures["org.example.broken"])
            fixture.remote.failAfter = true
            assertNotNull(runCatching { fixture.publisher().sync() }.exceptionOrNull())
            val incomplete = fixture.remote.bodies.last()
            assertNull(JsonFields.field(incomplete,"inventory"))
            assertEquals(emptyList<JsonValue.JObject>(),JsonFields.objects(incomplete,"bindings"))
            val acceptedCount = fixture.remote.bodies.size
            fixture.publisher().sync()
            assertEquals(acceptedCount,fixture.remote.bodies.size)
            assertEquals(0,fixture.foreignChanges)

            host.uninstall("org.example.broken")
            fixture.publisher().sync()
            assertTrue(host.loadFailures.isEmpty())
            assertNotNull(JsonFields.field(fixture.remote.bodies.last(),"inventory"))
            assertNotEquals(Json.canonical(incomplete),Json.canonical(fixture.remote.bodies.last()))
        } finally {
            fixture.host.installStore.installRoot.deleteRecursively()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }
}
