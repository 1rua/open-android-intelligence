package com.openandroidintelligence.notification.host

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.core.model.PairedBridgeTransport
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.notification.control.NotificationControlRegistry
import com.openandroidintelligence.notification.control.NotificationPolicyUpdate
import com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome
import com.openandroidintelligence.notifications.AndroidNotificationCollector
import com.openandroidintelligence.notifications.NotificationRuntimeFactoryRegistry
import com.openandroidintelligence.notifications.PairedBridgeBindingSource
import com.openandroidintelligence.notifications.RawNotification
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 装配根契约：
 * - 库内 ContentProvider 在 app 进程启动时向 NotificationControlRegistry 自注册，
 *   不依赖 OpenAndroidIntelligenceApplication.onCreate 已初始化的任何字段；
 * - 无 ContentProvider 场景（宿主不含本模块）下 registry 保持 deny-first fail-closed；
 * - 装配出的运行时工厂把策略权威接到采集器：授权前 onPosted 一律拒绝，
 *   经端口授权后同一采集器放行同一包名，撤销后立即回到拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationHostInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun resetRegistries() {
        NotificationControlRegistry.reset()
        NotificationRuntimeFactoryRegistry.reset()
        NotificationHostInstaller.resetForTest()
    }

    @After
    fun tearDown() {
        NotificationControlRegistry.reset()
        NotificationRuntimeFactoryRegistry.reset()
        NotificationHostInstaller.resetForTest()
    }

    private fun buildComposition(outboxKey: SecretKey? = null): NotificationHostComposition =
        NotificationHostInstaller.build(
            context = context,
            persistenceFile = File(tempFolder.root, "notification-policy.bin"),
            outboxFile = File(tempFolder.root, "notification-outbox.bin"),
            outboxKeySource = { outboxKey },
        )

    @Test
    fun bootstrap_provider_installs_registry_at_process_start() {
        Robolectric.buildContentProvider(NotificationControlBootstrapProvider::class.java)
            .create("test.authority.notification-control-bootstrap")

        val snapshot = NotificationControlRegistry.policyPort().snapshot()
        assertTrue("ContentProvider 装配后 registry 必须报告已装配", snapshot.installed)
        assertFalse(snapshot.granted)

        // 装配后的端口是真实权威：经 registry 即可完成一次授权更新
        val outcome = NotificationControlRegistry.policyPort().update(
            NotificationPolicyUpdate(expectedRevision = snapshot.revision, granted = true),
        )
        assertTrue(outcome is NotificationPolicyUpdateOutcome.Accepted)
        assertTrue(NotificationControlRegistry.policyPort().snapshot().granted)
    }

    @Test
    fun registry_stays_fail_closed_without_the_bootstrap_provider() {
        // 模拟宿主不含 :notification-host：registry 从未被 install
        val snapshot = NotificationControlRegistry.policyPort().snapshot()
        assertFalse(snapshot.installed)
        assertFalse(snapshot.granted)
        assertFalse(NotificationControlRegistry.bindingPort().isListenerBound())
        val outcome = NotificationControlRegistry.policyPort().update(
            NotificationPolicyUpdate(expectedRevision = 0u, granted = true),
        )
        assertTrue(outcome is NotificationPolicyUpdateOutcome.Rejected)
    }

    @Test
    fun install_is_idempotent_per_process() {
        val first = NotificationHostInstaller.install(context)
        val second = NotificationHostInstaller.install(context)

        assertEquals("二次 install 不得替换已装配的组合", first, second)
        assertTrue(NotificationControlRegistry.policyPort() === first.policyPort)
        assertTrue(NotificationControlRegistry.bindingPort() === first.bindingPort)
    }

    @Test
    fun runtime_factory_collector_follows_policy_authority() {
        val composition = buildComposition()
        NotificationRuntimeFactoryRegistry.install(composition.runtimeFactory)
        val runtime = NotificationRuntimeFactoryRegistry.create(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        runtime.start()
        val collector: AndroidNotificationCollector = runtime.currentCollector()
        val raw = RawNotification(
            packageName = "com.example.mail",
            notificationKey = "key-1",
            appLabel = null,
            title = "t",
            body = "b",
            channelId = null,
            postedAtEpochMs = 1L,
        )

        // deny-first：授权前策略门外不保留任何数据
        assertFalse(collector.onPosted(raw))

        val snapshot = composition.policyPort.snapshot()
        val outcome = composition.policyPort.update(
            NotificationPolicyUpdate(
                expectedRevision = snapshot.revision,
                granted = true,
                packageIds = listOf("com.example.mail"),
            ),
        )
        assertTrue(outcome is NotificationPolicyUpdateOutcome.Accepted)

        // 授权经权威 listener 同步到采集器后，同一回调被放行
        assertTrue(collector.onPosted(raw))

        // 撤销授权后立即回到 deny-first
        composition.policyPort.update(
            NotificationPolicyUpdate(
                expectedRevision = composition.policyPort.snapshot().revision,
                granted = false,
            ),
        )
        assertFalse(collector.onPosted(raw))
    }

    @Test
    fun outbox_is_assembled_only_when_a_key_is_available_and_bridge_attaches_only_with_outbox() {
        val keyless = buildComposition(outboxKey = null)
        assertNull("无密钥时不得伪造 outbox", keyless.outbox)
        assertNull(keyless.buildDispatcher())
        assertFalse("没有 outbox 就没有持久化通道，桥附件必须被拒绝", keyless.attachBridge(FakeBridgeTransport, FakeBindingSource))
        assertNull(keyless.buildDispatcher())

        val keyed = buildComposition(outboxKey = testKey())
        assertNotNull("有密钥必须装配真实 outbox", keyed.outbox)
        assertNull("桥未接入前 dispatcher 必须为空（fail-closed，无发送路径）", keyed.buildDispatcher())
        assertTrue(keyed.attachBridge(FakeBridgeTransport, FakeBindingSource))
        assertNotNull("接入桥后 dispatcher 必须真实装配", keyed.buildDispatcher())
    }

    @Test
    fun bootstrap_provider_is_declared_non_exported_in_the_merged_manifest() {
        val providers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            .providers.orEmpty()
        val provider = providers.single {
            it.name == "com.openandroidintelligence.notification.host.NotificationControlBootstrapProvider"
        }
        assertFalse("自注册 Provider 不得对外导出", provider.exported)
    }

    private fun testKey(): SecretKey = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")

    private object FakeBridgeTransport : PairedBridgeTransport {
        override suspend fun open(binding: VerifiedPairingTransportBinding) =
            throw UnsupportedOperationException("test transport never opens")

        override suspend fun close(reason: TransportCloseReason) = Unit
    }

    private object FakeBindingSource : PairedBridgeBindingSource {
        override fun currentBinding(): VerifiedPairingTransportBinding? = null
    }
}
