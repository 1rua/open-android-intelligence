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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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
        var lookups = 0
        var catalog = emptyList<JsonValue.JObject>()
        var afterPublication: suspend () -> Unit = {}
        var beforeLookup: (Int) -> Unit = {}
        val bodies = mutableListOf<JsonValue.JObject>()
        override suspend fun execute(request: WireRequest): WireResponse {
            if (request.method == "GET") { lookups++; beforeLookup(lookups) }
            val data = if (request.method == "GET") mapOf("grantRevision" to revision, "grantDigest" to digest,
                "capabilityPublicationVersion" to version, "capabilities" to catalog, "capabilityPolicy" to mapOf("revision" to policyRevision, "enabled" to emptyMap<String,Boolean>()))
            else {
                val body = JsonFields.obj(Json.parse(request.body.decodeToString()))!!
                bodies += body
                if (failBefore) { failBefore = false; throw IOException("POST not accepted") }
                check(JsonFields.int(body,"expectedGrantRevision") == revision) { "GRANT_STALE" }
                val payload = body.fields.filter { it.first != "expectedGrantRevision" }
                val next = Json.sha256(JsonValue.JObject(payload))
                if (next != digest) revision++
                digest = next
                catalog = JsonFields.objects(body, "inventory")
                afterPublication()
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
        var authorizationAvailable = true
        var bindingAvailable = true
        var ownerAvailable = true
        val scope = "publisher-test-${java.util.UUID.randomUUID()}"
        private val http = GatewayHttpClient(GatewayProfile("account","device","session","https://gateway.example",accessToken="test"),
            remote,{ ByteArray(64) },InMemoryEventCursorStore())
        fun publisher() = CapabilityPublisher(context,http,scope,remote.revision,{ grants.state.value?.revision ?: 0L },host,
            foreignChange = { foreignChanges++ },keyProvider=TestDocumentKeys,
            authorizationCurrent = { authorizationAvailable },
            bindingCurrent = { bindingAvailable }, ownerCurrent = { ownerAvailable })
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
        assertNull(EncryptedDocuments(fixture.context,"capability-publication:android-v1:${fixture.scope}",TestDocumentKeys).read("pending"))
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

    @Test fun foreignRevisionInvalidatesPendingBodyWithoutChangingAndroidDecisions() = runBlocking {
        val fixture = Fixture()
        fixture.grants.updatePrimitive("kernel.sms.read",true)
        fixture.remote.failBefore = true
        val publisher = fixture.publisher()
        assertNotNull(runCatching { publisher.sync() }.exceptionOrNull())
        fixture.remote.revision = 7
        fixture.remote.digest = null
        publisher.sync()
        assertEquals(1,fixture.foreignChanges)
        assertTrue("kernel.sms.read" in fixture.grants.state.value!!.granted)
        assertEquals(1L,JsonFields.long(fixture.remote.bodies.last(),"localGrantRevision"))
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
    }

    @Test fun aPeerWithoutDetailedInventoryReceivesTheCompatiblePublicationShape() = runBlocking {
        val fixture = Fixture()
        fixture.remote.version = 1
        fixture.publisher().sync()
        assertNull(JsonFields.field(fixture.remote.bodies.single(),"inventory"))
    }

    @Test fun anUnnegotiatedAuthorizationModelCannotPublishOrReadPairingState() = runBlocking {
        val fixture = Fixture()
        fixture.authorizationAvailable = false
        val failure = runCatching { fixture.publisher().sync() }.exceptionOrNull()
        assertEquals("DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED", failure?.message)
        assertEquals(0, fixture.remote.lookups)
        assertTrue(fixture.remote.bodies.isEmpty())
    }

    @Test fun aLegacyPendingSnapshotCannotBeReplayedUnderAndroidDefaults() = runBlocking {
        val fixture = Fixture()
        val legacy = EncryptedDocuments(fixture.context,"capability-publication:${fixture.scope}",TestDocumentKeys)
        legacy.write("pending",Json.canonical(Json.of(mapOf("bindings" to emptyList<Any>(),
            "localGrantRevision" to 999,"expectedGrantRevision" to 1))).toByteArray())
        fixture.publisher().sync()
        assertEquals(0L,JsonFields.long(fixture.remote.bodies.single(),"localGrantRevision"))
        assertEquals(0,fixture.foreignChanges)
        assertNotNull(legacy.read("pending"))
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

    @Test fun aSuccessfulPostReturnsItsFreshCatalogRatherThanThePrePublicationSnapshot() = runBlocking {
        val fixture = Fixture()
        fixture.remote.catalog = listOf(JsonFields.obj(Json.of(mapOf(
            "pluginId" to "org.example.previous", "capabilityId" to "org.example.previous.query",
            "capabilityVersion" to "1.0.0", "status" to "READY",
        )))!!)
        val publisher = fixture.publisher()
        publisher.sync()
        assertTrue(publisher.state.value.capabilities.isEmpty())
        assertEquals(2, fixture.remote.lookups)
    }

    @Test fun changingLocalAuthorizationWhileAwaitingAckRejectsTheOldSuccess() = runBlocking {
        val fixture = Fixture()
        fixture.remote.afterPublication = { fixture.grants.updatePrimitive("kernel.sms.read", false) }
        val failure = runCatching { fixture.publisher().sync() }.exceptionOrNull()
        assertEquals("LOCAL_AUTHORIZATION_CHANGED", failure?.message)
        assertFalse(fixture.grants.state.value!!.permits("kernel.sms.read"))
    }

    @Test fun losingTheAuthorizationAgreementWhileAwaitingAckRejectsTheOldSuccess() = runBlocking {
        val fixture = Fixture()
        fixture.remote.afterPublication = { fixture.authorizationAvailable = false }
        val failure = runCatching { fixture.publisher().sync() }.exceptionOrNull()
        assertEquals("DEVICE_AUTHORIZATION_MODEL_UNSUPPORTED", failure?.message)
    }

    @Test fun changingThePluginSnapshotWhileAwaitingAckRejectsTheOldSuccess() = runBlocking {
        val fixture = Fixture()
        fixture.remote.afterPublication = { fixture.host.revision.value++ }
        val failure = runCatching { fixture.publisher().sync() }.exceptionOrNull()
        assertEquals("LOCAL_AUTHORIZATION_CHANGED", failure?.message)
    }

    @Test fun readbackOfAnotherRevisionCannotBeReportedAsSynchronized() = runBlocking {
        val fixture = Fixture()
        fixture.remote.beforeLookup = { count -> if (count == 2) fixture.remote.revision++ }
        val publisher = fixture.publisher()
        val failure = runCatching { publisher.sync() }.exceptionOrNull()
        assertEquals("CAPABILITY_PUBLICATION_STALE", failure?.message)
        assertEquals("SYNC_FAILED", publisher.state.value.status)
        assertTrue(publisher.state.value.capabilities.isEmpty())
    }

    @Test fun rebindingWithTheSameLocalRevisionRejectsAnOlderPublication() = runBlocking {
        val fixture = Fixture()
        fixture.remote.afterPublication = { fixture.bindingAvailable = false }
        val publisher = fixture.publisher()
        assertEquals("PAIRING_BINDING_CHANGED", runCatching { publisher.sync() }.exceptionOrNull()?.message)
        assertNotEquals("SYNCED", publisher.state.value.status)
    }

    @Test fun aPreviousConnectionCannotPublishItsDelayedSuccessToTheCurrentOwner() = runBlocking {
        val fixture = Fixture()
        fixture.remote.afterPublication = { fixture.ownerAvailable = false }
        val publisher = fixture.publisher()
        assertEquals("DEVICE_SESSION_CHANGED", runCatching { publisher.sync() }.exceptionOrNull()?.message)
        assertNotEquals("SYNCED", publisher.state.value.status)
    }

    @Test fun aFailedPublicationRetainsItsCodeWhileRetryingAndClearsItAfterFreshReadback() = runBlocking {
        val fixture = Fixture()
        fixture.remote.failBefore = true
        val publisher = fixture.publisher()
        assertNotNull(runCatching { publisher.sync() }.exceptionOrNull())
        assertEquals("SYNC_FAILED", publisher.state.value.status)
        assertEquals("NETWORK_ERROR", publisher.state.value.errorCode)
        fixture.remote.beforeLookup = { count ->
            if (count == 2) {
                assertEquals("SYNC_RETRYING", publisher.state.value.status)
                assertEquals("NETWORK_ERROR", publisher.state.value.errorCode)
            }
        }
        publisher.sync()
        assertEquals("SYNCED", publisher.state.value.status)
        assertNull(publisher.state.value.errorCode)
        assertEquals(fixture.remote.revision, publisher.state.value.grantRevision)
        assertEquals(fixture.remote.digest, publisher.state.value.grantDigest)
    }

    @Test fun aFailedReadbackRecoversByGetWithoutRepeatingAnAcceptedPublication() = runBlocking {
        val fixture = Fixture()
        fixture.remote.beforeLookup = { count -> if (count == 2) throw IOException("readback interrupted") }
        val publisher = fixture.publisher()
        assertNotNull(runCatching { publisher.sync() }.exceptionOrNull())
        assertEquals("SYNC_FAILED", publisher.state.value.status)
        assertEquals("NETWORK_ERROR", publisher.state.value.errorCode)
        assertEquals(1, fixture.remote.bodies.size)
        publisher.sync()
        assertEquals("SYNCED", publisher.state.value.status)
        assertEquals(1, fixture.remote.bodies.size)
    }

    @Test fun aNewerAcknowledgedRevisionRequiresAReadbackBeforeDisplayingReadyAgain() = runBlocking {
        val fixture = Fixture()
        val publisher = fixture.publisher()
        publisher.sync()
        assertTrue(publisher.observeChange(fixture.remote.revision + 1, fixture.remote.digest))
        assertEquals("SYNC_REQUIRED", publisher.state.value.status)
        assertTrue(publisher.state.value.capabilities.isEmpty())
    }

    @Test fun aCancelledSynchronizationCannotPublishSuccessWhenItsTransportReturnsLate() = runBlocking {
        val fixture = Fixture()
        fixture.remote.afterPublication = { currentCoroutineContext().cancel() }
        val publisher = fixture.publisher()
        val operation = launch { publisher.sync() }
        operation.join()
        assertTrue(operation.isCancelled)
        assertNotEquals("SYNCED", publisher.state.value.status)
        fixture.remote.afterPublication = {}
        publisher.sync()
        assertEquals("SYNCED", publisher.state.value.status)
        assertEquals(1, fixture.remote.bodies.size)
    }
}
