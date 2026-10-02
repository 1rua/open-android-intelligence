package com.openandroidintelligence.kernel

import com.openandroidintelligence.plugin.pkg.PluginIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The kernel is the last component between a plugin and the user's data, so
 * these tests assert the boundaries rather than the happy path: every term of
 * the capability intersection, storage partitioning, network mediation, audit
 * content and the native escape hatch.
 */
class KernelIsolationTest {

    private val smsIdentity = PluginIdentity("org.openandroidintelligence.sms", "A".repeat(43), "1.0.0")
    private val otherIdentity = PluginIdentity("org.example.other", "B".repeat(43), "1.0.0")

    private val budget = ResourceBudget(
        maxInvocationMillis = 1_000L,
        maxMemoryBytes = 1_048_576L,
        maxOutputBytes = 65_536L,
        maxConcurrentInvocations = 1,
        maxDailyNetworkBytes = 1_024L,
    )

    private fun session(
        primitives: Set<String> = setOf("kernel.sms.read"),
        correlationId: String = "corr-1",
    ) = SessionConstraints(primitives = primitives, background = false, correlationId = correlationId)

    private fun kernel(
        hostPrimitives: Set<String> = setOf("kernel.sms.read", "kernel.notifications.read"),
        phonePrimitives: Set<String> = setOf("kernel.sms.read", "kernel.notifications.read"),
        grant: PairingGrant? = PairingGrant(
            pairingId = "pairing-a",
            granted = setOf("kernel.sms.read"),
            revision = 1L,
        ),
        runtime: PluginRuntime = EchoRuntime(),
        provider: PluginIdentity = smsIdentity,
        capability: String = "kernel.sms.read",
    ): PluginKernel {
        val audit = AndroidAuditStore()
        val trustMode = DeveloperTrustMode()
        val selector = CapabilityProviderSelector(phoneDefaults = mapOf(capability to provider))
        return PluginKernel(
            hostEnvelope = HostEnvelope(hostPrimitives),
            phoneLimits = PhoneLimits(phonePrimitives),
            runtimes = mapOf(PluginKernel.RUNTIME_PROTECTED_WASM to runtime),
            audit = audit,
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = selector,
            grants = { if (it == "pairing-a") grant else null },
        )
    }

    private fun registered(
        kernel: PluginKernel,
        identity: PluginIdentity = smsIdentity,
        primitives: Set<String> = setOf("kernel.sms.read"),
        state: PluginState = PluginState.INSTALLED_DISABLED,
    ) {
        kernel.register(
            PluginRegistration(
                identity = identity,
                runtimeType = PluginKernel.RUNTIME_PROTECTED_WASM,
                declaredPrimitives = primitives,
                budget = budget,
                state = PluginStateMachine(state),
            ),
        )
    }

    private class EchoRuntime : PluginRuntime {
        override fun invoke(
            identity: PluginIdentity,
            budget: ResourceBudget,
            input: ByteArray,
        ): ByteArray = input
    }

    // --- capability intersection -------------------------------------------------

    @Test
    fun grantsOnlyTheIntersectionOfEveryTerm() {
        val kernel = kernel()
        registered(kernel)
        kernel.enable("org.openandroidintelligence.sms")

        val result = kernel.invoke(
            identity = smsIdentity,
            accountId = "account-a",
            pairingId = "pairing-a",
            capability = "kernel.sms.read",
            input = "ping".toByteArray(),
            session = session(),
        )
        assertArrayEquals("ping".toByteArray(), result.output)
    }

    @Test
    fun deniesWhenTheHostEnvelopeLacksThePrimitive() {
        val kernel = kernel(hostPrimitives = setOf("kernel.notifications.read"))
        registered(kernel)
        kernel.enable("org.openandroidintelligence.sms")

        assertDenied(kernel, "kernel.sms.read")
    }

    @Test
    fun deniesWhenThePhoneRestrictsThePrimitive() {
        val kernel = kernel(phonePrimitives = setOf("kernel.notifications.read"))
        registered(kernel)
        kernel.enable("org.openandroidintelligence.sms")

        assertDenied(kernel, "kernel.sms.read")
    }

    @Test
    fun deniesWhenThePluginDidNotDeclareThePrimitive() {
        val kernel = kernel()
        registered(kernel, primitives = emptySet())
        kernel.enable("org.openandroidintelligence.sms")

        assertDenied(kernel, "kernel.sms.read")
    }

    @Test
    fun deniesWhenThePluginIsInstalledButNotEnabled() {
        val kernel = kernel()
        registered(kernel, state = PluginState.INSTALLED_DISABLED)

        assertDenied(kernel, "kernel.sms.read")
    }

    @Test
    fun deniesWhenThePairingHasNoGrant() {
        val kernel = kernel(grant = null)
        registered(kernel)
        kernel.enable("org.openandroidintelligence.sms")

        assertDenied(kernel, "kernel.sms.read")
    }

    @Test
    fun deniesWhenTheSessionDoesNotPermitThePrimitive() {
        val kernel = kernel()
        registered(kernel)
        kernel.enable("org.openandroidintelligence.sms")

        assertDenied(kernel, "kernel.sms.read", session = session(primitives = emptySet()))
    }

    @Test
    fun deniesAnUnauthorisedProvider() {
        val kernel = kernel()
        // The provider for the capability is the official plugin, but a
        // different plugin that also declares it tries to serve it.
        registered(kernel, identity = smsIdentity)
        registered(kernel, identity = otherIdentity)
        kernel.enable("org.openandroidintelligence.sms")
        kernel.enable("org.example.other")

        try {
            kernel.invoke(
                identity = otherIdentity,
                accountId = "account-a",
                pairingId = "pairing-a",
                capability = "kernel.sms.read",
                input = ByteArray(0),
                session = session(),
            )
            fail("expected the unauthorised provider to be rejected")
        } catch (cause: ProviderRejected) {
            assertTrue(cause.message!!.contains("NOT_PROVIDER"))
        }
    }

    private fun assertDenied(
        kernel: PluginKernel,
        capability: String,
        session: SessionConstraints = session(),
    ) {
        try {
            kernel.invoke(
                identity = smsIdentity,
                accountId = "account-a",
                pairingId = "pairing-a",
                capability = capability,
                input = ByteArray(0),
                session = session,
            )
            fail("expected $capability to be denied")
        } catch (cause: CapabilityDenied) {
            assertEquals(capability, cause.primitive)
        }
    }

    // --- provider switching ------------------------------------------------------

    @Test
    fun switchingProviderAllocatesANewGrantRevisionAndInheritsNothing() {
        val selector = CapabilityProviderSelector(
            phoneDefaults = mapOf("org.openandroidintelligence.sms.query@1.0.0" to smsIdentity),
        )
        val first = selector.select("org.openandroidintelligence.sms.query@1.0.0", "pairing-a")
        assertEquals(0L, first.grantRevision)

        val switched = selector.setOverride(
            "org.openandroidintelligence.sms.query@1.0.0", "pairing-a", otherIdentity,
        )
        assertTrue(switched.grantRevision > first.grantRevision)
        assertFalse(switched.inheritedPermissions)
        assertEquals(otherIdentity, selector.select("org.openandroidintelligence.sms.query@1.0.0", "pairing-a").identity)
    }

    @Test
    fun selectingTheSameProviderIsNotAReauthorisation() {
        val selector = CapabilityProviderSelector(
            phoneDefaults = mapOf("org.openandroidintelligence.sms.query@1.0.0" to smsIdentity),
        )
        val first = selector.setOverride("org.openandroidintelligence.sms.query@1.0.0", "pairing-a", smsIdentity)
        val second = selector.setOverride("org.openandroidintelligence.sms.query@1.0.0", "pairing-a", smsIdentity)
        assertEquals(first.grantRevision, second.grantRevision)
    }

    @Test
    fun overridesAreIsolatedPerPairing() {
        val selector = CapabilityProviderSelector(
            phoneDefaults = mapOf("org.openandroidintelligence.sms.query@1.0.0" to smsIdentity),
        )
        selector.setOverride("org.openandroidintelligence.sms.query@1.0.0", "pairing-a", otherIdentity)
        assertEquals(
            smsIdentity,
            selector.select("org.openandroidintelligence.sms.query@1.0.0", "pairing-b").identity,
        )
    }

    // --- private storage ---------------------------------------------------------

    @Test
    fun storageIsPartitionedByAccountAndRejectsACrossAccountHandle() {
        val store = PluginPrivateStore(
            installId = "install-1",
            backend = InMemoryPrivateStoreBackend(),
            maxBytesPerPartition = 1_024L,
        )
        val handleA = store.open(smsIdentity, "account-a")
        store.write(handleA, "account-a", "cursor", "42".toByteArray())

        assertArrayEquals("42".toByteArray(), store.read(handleA, "account-a", "cursor"))
        assertNull(store.read(store.open(smsIdentity, "account-b"), "account-b", "cursor"))

        try {
            store.read(handleA, "account-b", "cursor")
            fail("expected a cross-account handle to be rejected")
        } catch (cause: StorageDenied) {
            assertTrue(cause.message!!.contains("ACCOUNT_MISMATCH"))
        }
    }

    @Test
    fun storageRejectsAHandleFromAnotherInstallation() {
        val backend = InMemoryPrivateStoreBackend()
        val store = PluginPrivateStore("install-1", backend, 1_024L)
        val foreign = PluginPrivateStore("install-2", backend, 1_024L)
        val foreignHandle = foreign.open(smsIdentity, "account-a")
        foreign.write(foreignHandle, "account-a", "k", "v".toByteArray())

        try {
            store.read(foreignHandle, "account-a", "k")
            fail("expected a foreign installation handle to be rejected")
        } catch (cause: StorageDenied) {
            assertTrue(cause.message!!.contains("INSTALL_MISMATCH"))
        }
    }

    @Test
    fun storageEnforcesItsQuota() {
        val store = PluginPrivateStore("install-1", InMemoryPrivateStoreBackend(), maxBytesPerPartition = 8L)
        val handle = store.open(smsIdentity, "account-a")
        store.write(handle, "account-a", "k", ByteArray(8))
        try {
            store.write(handle, "account-a", "k2", ByteArray(1))
            fail("expected the quota to be enforced")
        } catch (cause: StorageDenied) {
            assertTrue(cause.message!!.contains("QUOTA"))
        }
    }

    // --- mediated network --------------------------------------------------------

    private fun proxy(
        transport: MediatedTransport,
        hosts: Set<String> = setOf("gw.example"),
        dailyBudgetBytes: Long = 1_024L,
    ): MediatedNetworkProxy = MediatedNetworkProxy(
        allowlist = NetworkAllowlist(hosts = hosts, methods = setOf("GET", "POST")),
        transport = transport,
        dailyBudgetBytes = dailyBudgetBytes,
        clock = { 0L },
    )

    private fun request(
        host: String = "gw.example",
        scheme: String = "https",
        port: Int = 443,
        method: String = "GET",
        path: String = "/v2/messages",
        body: ByteArray? = null,
    ) = MediatedRequest(scheme, host, port, method, path, emptyMap(), body)

    @Test
    fun proxyRejectsAPlaintextSchemeAnUnlistedHostAndAnUnlistedMethod() {
        val transport = RecordingTransport()
        val proxy = proxy(transport)

        assertNetworkDenied("SCHEME") { proxy.exchange(request(scheme = "http")) }
        assertNetworkDenied("HOST_NOT_ALLOWED") { proxy.exchange(request(host = "evil.example")) }
        assertNetworkDenied("METHOD_NOT_ALLOWED") { proxy.exchange(request(method = "DELETE")) }
        assertNetworkDenied("PORT") { proxy.exchange(request(port = 8443)) }
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun proxyRejectsARedirectThatLeavesTheAllowlist() {
        val transport = RecordingTransport(
            responses = listOf(
                MediatedResponse(
                    status = 302,
                    headers = mapOf("Location" to "https://evil.example/v2/messages"),
                    body = ByteArray(0),
                ),
            ),
        )
        assertNetworkDenied("HOST_NOT_ALLOWED") { proxy(transport).exchange(request()) }
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun proxyFollowsARedirectThatStaysInsideTheAllowlist() {
        val transport = RecordingTransport(
            responses = listOf(
                MediatedResponse(
                    status = 302,
                    headers = mapOf("Location" to "https://gw.example/v2/next"),
                    body = ByteArray(0),
                ),
                MediatedResponse(status = 200, headers = emptyMap(), body = "ok".toByteArray()),
            ),
        )
        val response = proxy(transport).exchange(request())
        assertEquals(200, response.status)
        assertEquals("/v2/next", transport.calls.last().pathAndQuery)
    }

    @Test
    fun proxyRejectsARedirectThatChangesSchemeOrPort() {
        val transport = RecordingTransport(
            responses = listOf(
                MediatedResponse(
                    status = 301,
                    headers = mapOf("Location" to "http://gw.example/v2/messages"),
                    body = ByteArray(0),
                ),
            ),
        )
        // A plaintext Location is not parseable as an https target, so it is
        // refused rather than downgraded.
        assertNetworkDenied("REDIRECT_UNPARSEABLE") { proxy(transport).exchange(request()) }
    }

    @Test
    fun proxyEnforcesTheDailyByteBudget() {
        val transport = RecordingTransport(
            responses = listOf(MediatedResponse(200, emptyMap(), ByteArray(600))),
        )
        val proxy = proxy(transport, dailyBudgetBytes = 500L)
        assertNetworkDenied("DAILY_BUDGET") { proxy.exchange(request()) }
    }

    private fun assertNetworkDenied(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected NETWORK_DENIED:$code")
        } catch (cause: NetworkDenied) {
            assertTrue("expected $code, got ${cause.message}", cause.message!!.contains(code))
        }
    }

    private class RecordingTransport(
        private val responses: List<MediatedResponse> = listOf(
            MediatedResponse(200, emptyMap(), ByteArray(0)),
        ),
    ) : MediatedTransport {
        val calls = mutableListOf<MediatedRequest>()
        private var index = 0

        override fun exchange(request: MediatedRequest): MediatedResponse {
            calls += request
            val response = responses.getOrNull(index) ?: responses.last()
            index++
            return response
        }
    }

    // --- audit -------------------------------------------------------------------

    @Test
    fun auditRecordsOnlySubjectActionOutcomeAndCorrelationId() {
        val sink = InMemoryAuditSink()
        val store = AndroidAuditStore(sink)
        store.record(
            pluginId = "org.openandroidintelligence.sms",
            accountId = "account-a",
            pairingId = "pairing-a",
            action = "invoke",
            outcome = AuditOutcome.ALLOWED,
            correlationId = "corr-1",
        )

        val event = sink.events().single()
        val rendered = store.render(event)
        assertTrue(rendered.contains("plugin=org.openandroidintelligence.sms"))
        assertTrue(rendered.contains("action=invoke"))
        assertTrue(rendered.contains("outcome=ALLOWED"))
        assertTrue(rendered.contains("correlation=corr-1"))
        assertFalse(rendered.contains("hello"))
    }

    @Test
    fun auditRedactsAnActionThatIsNotAnIdentifier() {
        val sink = InMemoryAuditSink()
        val store = AndroidAuditStore(sink)
        val event = store.record(
            pluginId = "org.openandroidintelligence.sms",
            accountId = "account-a",
            pairingId = "pairing-a",
            action = "invoke with body: hello from the user",
            outcome = AuditOutcome.ALLOWED,
            correlationId = "corr-1",
        )
        assertEquals("redacted", event.action)
        assertFalse(store.render(event).contains("hello"))
    }

    @Test
    fun auditTimestampsAlwaysCarryMilliseconds() {
        val store = AndroidAuditStore(InMemoryAuditSink())
        val event = store.record(
            pluginId = "org.openandroidintelligence.sms",
            accountId = "a",
            pairingId = "p",
            action = "invoke",
            outcome = AuditOutcome.ALLOWED,
            correlationId = "c",
        )
        // A whole second still has to print `.000`, or the Gateway cannot
        // reproduce the signed preimage.
        assertTrue(event.timestampUtc.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")))
    }

    @Test
    fun kernelDenialsAreAudited() {
        val sink = InMemoryAuditSink()
        val audit = AndroidAuditStore(sink)
        val trustMode = DeveloperTrustMode()
        val kernel = PluginKernel(
            hostEnvelope = HostEnvelope(emptySet()),
            phoneLimits = PhoneLimits(emptySet()),
            runtimes = emptyMap(),
            audit = audit,
            trustMode = trustMode,
            nativeLoader = NativePluginLoader(trustMode),
            providerSelector = CapabilityProviderSelector(
                phoneDefaults = mapOf("kernel.sms.read" to smsIdentity),
            ),
            grants = { null },
        )
        kernel.register(
            PluginRegistration(
                identity = smsIdentity,
                runtimeType = PluginKernel.RUNTIME_PROTECTED_WASM,
                declaredPrimitives = setOf("kernel.sms.read"),
                budget = budget,
                state = PluginStateMachine(PluginState.ENABLED),
            ),
        )
        try {
            kernel.invoke(
                smsIdentity, "account-a", "pairing-a", "kernel.sms.read",
                ByteArray(0), session(),
            )
            fail("expected denial")
        } catch (cause: CapabilityDenied) {
            // expected
        }
        val event = sink.events().single()
        assertEquals(AuditOutcome.DENIED, event.outcome)
        assertEquals("corr-1", event.correlationId)
    }

    // --- developer trust mode and native plugins ---------------------------------

    @Test
    fun nativePluginsCannotLoadOutsideDeveloperTrustMode() {
        val trustMode = DeveloperTrustMode()
        val loader = NativePluginLoader(trustMode)
        val packageInfo = NativePluginPackage(
            pluginId = "org.example.native",
            authorKeyFingerprint = "C".repeat(43),
            entrypointClass = "org.example.native.Entry",
            abis = setOf("arm64-v8a"),
        )
        val factory: (NativePluginPackage) -> NativePlugin = { StubNativePlugin(it.pluginId) }

        try {
            loader.load(packageInfo, trustEnabled = false, factory)
            fail("expected native load to be refused")
        } catch (cause: NativePluginRejected) {
            assertTrue(cause.message!!.contains("TRUST_MODE_DISABLED"))
        }

        assertTrue(
            trustMode.enable(
                DeveloperTrustMode.Acknowledgement(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT),
            ),
        )
        loader.load(packageInfo, trustEnabled = true, factory)
        assertTrue(loader.isLoaded("org.example.native"))

        trustMode.disable()
        assertFalse(loader.isLoaded("org.example.native"))
    }

    @Test
    fun developerTrustModeRequiresTheAcknowledgementTheHostHadToShow() {
        val trustMode = DeveloperTrustMode()
        assertFalse(trustMode.enable(DeveloperTrustMode.Acknowledgement("ok")))
        assertFalse(trustMode.isEnabled())
    }

    @Test
    fun nativeEntrypointMayNotNameAHostClass() {
        val trustMode = DeveloperTrustMode()
        trustMode.enable(
            DeveloperTrustMode.Acknowledgement(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT),
        )
        val loader = NativePluginLoader(trustMode)
        val factory: (NativePluginPackage) -> NativePlugin = { StubNativePlugin(it.pluginId) }

        try {
            loader.load(
                NativePluginPackage(
                    pluginId = "org.example.native",
                    authorKeyFingerprint = "C".repeat(43),
                    entrypointClass = "com.openandroidintelligence.kernel.PluginKernel",
                    abis = emptySet(),
                ),
                trustEnabled = true,
                factory,
            )
            fail("expected a host class entrypoint to be refused")
        } catch (cause: NativePluginRejected) {
            assertTrue(cause.message!!.contains("HOST_CLASS_ENTRYPOINT"))
        }
    }

    private class StubNativePlugin(override val pluginId: String) : NativePlugin {
        override fun invoke(input: ByteArray): ByteArray = input
        override fun stop() = Unit
    }

    // --- installation state machine ----------------------------------------------

    @Test
    fun onlyTheEnabledStateMayExecute() {
        val machine = PluginStateMachine(PluginState.DISCOVERED)
        assertFalse(machine.isExecutable())
        machine.transition(PluginState.DOWNLOADING)
        machine.transition(PluginState.VERIFYING)
        machine.transition(PluginState.INSTALLED_DISABLED)
        assertFalse(machine.isExecutable())
        machine.transition(PluginState.ENABLED)
        assertTrue(machine.isExecutable())
    }

    @Test
    fun illegalTransitionsAreRejected() {
        val machine = PluginStateMachine(PluginState.DISCOVERED)
        try {
            machine.transition(PluginState.ENABLED)
            fail("expected the transition to be rejected")
        } catch (cause: StateTransitionRejected) {
            assertEquals("DISCOVERED", cause.from)
            assertEquals("ENABLED", cause.to)
        }
        assertEquals(PluginState.DISCOVERED, machine.state)
    }

    @Test
    fun aRejectedPackageCanOnlyBeUninstalled() {
        val machine = PluginStateMachine(PluginState.VERIFYING)
        machine.transition(PluginState.REJECTED)
        try {
            machine.transition(PluginState.INSTALLED_DISABLED)
            fail("expected the transition to be rejected")
        } catch (cause: StateTransitionRejected) {
            assertEquals("REJECTED", cause.from)
        }
        machine.transition(PluginState.UNINSTALLED)
    }
    @Test fun refusesSubstitutedAuthorOrVersionBeforeRuntimeExecution() {
        val kernel = kernel()
        registered(kernel)
        kernel.enable(smsIdentity.pluginId)
        for (identity in listOf(smsIdentity.copy(authorKeyFingerprint = "other"), smsIdentity.copy(version = "9.0.0"))) {
            assertTrue(runCatching { kernel.invoke(identity, "account-a", "pairing-a", "kernel.sms.read", byteArrayOf(1), session()) }.exceptionOrNull() is CapabilityDenied)
            assertTrue(runCatching { registered(kernel, identity) }.exceptionOrNull() is ProviderRejected)
        }
    }

    @Test fun storeUsesAuthorIdentityAndFencesEveryOperationAfterAccountDeletion() {
        val memory = InMemoryPrivateStoreBackend()
        var deletes = 0
        val backend = object : PrivateStoreBackend by memory {
            override fun deleteAccountPartitions(scopePrefix: String) { deletes++; memory.deleteAccountPartitions(scopePrefix) }
        }
        val store = PluginPrivateStore("install|1", backend, 1024)
        val old = store.open(smsIdentity, "acct|a")
        store.write(old, "acct|a", "secret", byteArrayOf(7))
        assertNull(store.read(store.open(smsIdentity.copy(authorKeyFingerprint = "other"), "acct|a"), "acct|a", "secret"))
        assertArrayEquals(byteArrayOf(7), store.read(store.open(smsIdentity.copy(version = "2.0.0"), "acct|a"), "acct|a", "secret"))
        store.eraseAccount("acct|a")
        assertEquals(1, deletes)
        for (operation in listOf<() -> Any?>({ store.read(old, "acct|a", "secret") }, { store.write(old, "acct|a", "secret", byteArrayOf(8)) }, { store.delete(old, "acct|a", "secret") }, { store.keys(old, "acct|a") })) {
            assertTrue(runCatching(operation).exceptionOrNull() is StorageDenied)
        }
        assertNull(store.read(store.open(smsIdentity, "acct|a"), "acct|a", "secret"))
    }

    @Test fun failedBackendDeletionFencesOldAndNewHandlesUntilSuccessfulRetry() {
        val memory = InMemoryPrivateStoreBackend()
        var rejectDelete = true
        val backend = object : PrivateStoreBackend by memory {
            override fun deleteAccountPartitions(scopePrefix: String) {
                if (rejectDelete) error("DISK_FAILURE")
                memory.deleteAccountPartitions(scopePrefix)
            }
        }
        val store = PluginPrivateStore("install", backend, 1024)
        val old = store.open(smsIdentity, "account")
        store.write(old, "account", "secret", byteArrayOf(9))
        assertTrue(runCatching { store.eraseAccount("account") }.isFailure)
        assertTrue(runCatching { store.read(old, "account", "secret") }.exceptionOrNull() is StorageDenied)
        assertTrue(runCatching { store.open(smsIdentity, "account") }.exceptionOrNull() is StorageDenied)
        rejectDelete = false
        store.eraseAccount("account")
        assertNull(store.read(store.open(smsIdentity, "account"), "account", "secret"))
        assertTrue(runCatching { store.read(old, "account", "secret") }.exceptionOrNull() is StorageDenied)
    }

}
