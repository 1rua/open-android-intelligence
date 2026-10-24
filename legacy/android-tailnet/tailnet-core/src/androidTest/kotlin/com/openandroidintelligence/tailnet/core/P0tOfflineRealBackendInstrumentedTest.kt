package com.openandroidintelligence.tailnet.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.core.model.BridgeIdentity
import com.openandroidintelligence.core.model.EnrollmentTicket
import com.openandroidintelligence.core.model.PolicyAttestation
import com.openandroidintelligence.core.model.TransportPath
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * 真实 backend OFFLINE 矩阵行证据：用真实 Tailnet 一次性 auth key 冷启动 enroll，
 * 对“tailnet 上不存在的固定 Bridge peer”绑定调用 native Path()，backend 必须真报
 * OFFLINE，且过程中不创建任何系统 VPN 网络 agent（fail closed）。
 *
 * 一次性 key：本用例只在提供 `p0tOfflineBundle`（provision-enroll-bundle.sh 生成并
 * 推送的真实束）时执行并消耗一次真正的一次性 auth key。未提供该束时以 Assume 跳过
 * （在 JUnit 中记为 skipped，绝不计成 PASS，也不消耗 key）。
 */
@RunWith(AndroidJUnit4::class)
class P0tOfflineRealBackendInstrumentedTest {

    @Test(timeout = 300_000)
    fun enrolledNodeReportsBackendOfflineForAbsentBridgePeer() {
        val bundleArg = InstrumentationRegistry.getArguments().getString(OFFLINE_BUNDLE_ARG)
        assumeTrue(
            "p0tOfflineBundle not provided; run provision-enroll-bundle.sh for the dedicated OFFLINE evidence run",
            bundleArg != null,
        )
        val bundleFile = File(bundleArg!!)
        assumeTrue("p0tOfflineBundle file missing at $bundleArg", bundleFile.isFile)
        val bundle = bundleFile.readBytes()

        val before = P0tVpnAudit.vpnNetworkCount()
        val store = NoBackupTailnetStateStore(InMemoryEncryptedNoBackupState())
        val core = AndroidTsnetBinding(object : NativeEnrollmentSource {
            override fun bootstrapBytes(): ByteArray = bundle.copyOf()
        })
        try {
            val t0 = System.currentTimeMillis()
            val startFailure = runCatching { runSuspend { core.start(OFFLINE_DEVICE_ID, store) } }.exceptionOrNull()
            val elapsedMs = System.currentTimeMillis() - t0
            if (startFailure != null) {
                throw IllegalStateException(
                    "native start failed after ${elapsedMs}ms: ${startFailure.message}",
                    startFailure,
                )
            }
            android.util.Log.i("P0tOffline", "native start enrolled OK in ${elapsedMs}ms")

            val binding = trustedBridgeBinding()
            var path: TransportPath? = null
            runSuspendReturning { path = core.path(binding) }

            assertEquals(
                "absent pinned Bridge peer must be reported OFFLINE by the real backend",
                TransportPath.OFFLINE,
                path,
            )
            assertEquals(
                "OFFLINE enrollment must not create a system VPN network agent",
                before,
                P0tVpnAudit.vpnNetworkCount(),
            )
        } finally {
            runSuspendSafely { core.stop() }
        }
    }

    /** 与 provision-enroll-bundle.sh 一致的绑定（deviceId/identity/digests/generation/policy）。 */
    private fun trustedBridgeBinding(): VerifiedPairingTransportBinding {
        val nowEpochSeconds = System.currentTimeMillis() / 1000L
        val ticket = EnrollmentTicket(
            id = "p0t-offline-ticket",
            deviceId = OFFLINE_DEVICE_ID,
            bridgeIdentity = APP_KEY_FP,
            pairingGeneration = 7u,
            minimumPolicyRevision = 2u,
            expiresAtEpochSeconds = nowEpochSeconds + 600L,
            used = false,
            digest = TICKET_DIGEST,
        )
        return VerifiedPairingTransportBindingFactory.mint(
            ticket = ticket,
            bridge = BridgeIdentity(APP_KEY_FP),
            policy = PolicyAttestation(2u, POLICY_DIGEST),
            expectedPairingGeneration = 7u,
            nowEpochSeconds = nowEpochSeconds,
        )
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun prepareGoRuntimeEnvBeforeNativeLoad() {
            // 旧式追因结论：设备上 tsnet 启动要建 $HOME/.config，而 Android 未设
            // HOME（落到 / ，根目录只读）→ 瞬间 CONTROL_UNREACHABLE。必须在任何
            // native/Go 运行时 init 之前把 HOME 指向本应用可写目录（filesDir）。
            val filesDir = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext.filesDir.absolutePath
            android.system.Os.setenv("HOME", filesDir, true)
            assertEquals(
                "HOME must be writable for tsnet varRoot",
                filesDir,
                android.system.Os.getenv("HOME"),
            )
        }

        const val OFFLINE_BUNDLE_ARG = "p0tOfflineBundle"
        const val OFFLINE_DEVICE_ID = "p0t-offline-device-1"

        private fun b64url32(label: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(label.toByteArray())
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        val APP_KEY_FP = b64url32("app-key-fp")
        val TICKET_DIGEST = b64url32("ticket-digest")
        val POLICY_DIGEST = b64url32("policy-digest")
    }
}

private fun runSuspendReturning(block: suspend () -> Unit) {
    var throwable: Throwable? = null
    var resumed = false
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {
            throwable = result.exceptionOrNull()
            resumed = true
        }
    })
    if (!resumed) error("suspend block did not complete synchronously")
    throwable?.let { throw it }
}

private fun runSuspendSafely(block: suspend () -> Unit) {
    try {
        runSuspendReturning(block)
    } catch (_: Throwable) {
        // cleanup-only; the primary assertion already recorded the outcome.
    }
}
