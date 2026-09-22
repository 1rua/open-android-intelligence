package com.openandroidintelligence.notification.host

import android.content.Context
import com.openandroidintelligence.core.model.NotificationOutbox
import com.openandroidintelligence.core.model.PairedBridgeTransport
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import com.openandroidintelligence.encrypted.store.FileEncryptedOutboxPersistence
import com.openandroidintelligence.encrypted.store.NotificationOutboxStore
import com.openandroidintelligence.notification.control.NotificationBindingPort
import com.openandroidintelligence.notification.control.NotificationControlRegistry
import com.openandroidintelligence.notification.control.NotificationPolicyPort
import com.openandroidintelligence.notifications.AndroidNotificationCollector
import com.openandroidintelligence.notifications.NotificationBridgeDispatcher
import com.openandroidintelligence.notifications.NotificationRecordEgressGate
import com.openandroidintelligence.notifications.NotificationRuntime
import com.openandroidintelligence.notifications.NotificationRuntimeFactory
import com.openandroidintelligence.notifications.NotificationRuntimeFactoryRegistry
import com.openandroidintelligence.notifications.PairedBridgeBindingSource
import com.openandroidintelligence.policy.FileNotificationPolicyPersistence
import com.openandroidintelligence.policy.PersistentNotificationPolicyAuthority
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 一次完整的宿主装配：策略权威（文件持久化）+ 两个端口 + 可选的
 * 加密 outbox 与桥发送器。装配不依赖 OpenAndroidIntelligenceApplication
 * 的任何字段——ContentProvider 在 Application.onCreate 之前运行，
 * 审计 sink / 配对授权这类上游设施只能延迟注入或保持可选。
 */
class NotificationHostComposition internal constructor(
    val context: Context,
    val authority: PersistentNotificationPolicyAuthority,
    val persistenceFile: File,
    /**
     * 加密 outbox；仅当 outboxKeySource 提供了可用密钥时装配。
     * 没有密钥时保持 null：自动发送没有持久化通道，运行时 fail-closed
     * （记录只留在采集器内存，无 egress 路径），绝不伪造一个空壳 outbox。
     */
    val outbox: NotificationOutbox?,
) {
    private val lock = Any()
    private var bridgeAttachments: BridgeAttachments? = null

    val policyPort: NotificationPolicyPort = PersistentNotificationPolicyPort(authority)
    val bindingPort: NotificationBindingPort = HostNotificationBindingPort(context)

    /**
     * 延迟接入桥发送通道（配对完成后的延迟注入点）。
     * 没有持久化 outbox 时返回 false：发送没有重放与确认基座，
     * 宁可不装配也不提供一条丢失即错的路径。
     */
    fun attachBridge(transport: PairedBridgeTransport, bindingSource: PairedBridgeBindingSource): Boolean {
        if (outbox == null) return false
        synchronized(lock) { bridgeAttachments = BridgeAttachments(transport, bindingSource) }
        return true
    }

    /** 桥附件就绪时构造真实的发送器；否则返回 null（无发送路径）。 */
    fun buildDispatcher(): NotificationBridgeDispatcher? {
        val box = outbox ?: return null
        val attachments = synchronized(lock) { bridgeAttachments } ?: return null
        return NotificationBridgeDispatcher(
            outbox = box,
            transport = attachments.transport,
            bindingSource = attachments.bindingSource,
            egressGate = NotificationRecordEgressGate { record -> authority.allows(record) },
        )
    }

    /**
     * 供 NotificationRuntimeFactoryRegistry 装配的工厂：采集器的授权来自
     * 策略权威本身（PersistentNotificationPolicyAuthority 实现
     * NotificationAuthorization），出口闸与投递 admission 共用同一权威。
     */
    val runtimeFactory: NotificationRuntimeFactory = NotificationRuntimeFactory { scope ->
        NotificationRuntime(
            initialCollector = AndroidNotificationCollector(authorization = authority),
            outbox = outbox,
            scope = scope,
            egressGate = NotificationRecordEgressGate { record -> authority.allows(record) },
            dispatcher = buildDispatcher(),
            policyAuthority = authority,
        )
    }

    private data class BridgeAttachments(
        val transport: PairedBridgeTransport,
        val bindingSource: PairedBridgeBindingSource,
    )
}

/**
 * 进程装配入口。库内 ContentProvider（[NotificationControlBootstrapProvider]）
 * 在 app 进程启动时调用 [install]；install 幂等，第一次装配胜出，
 * 同时向 [NotificationControlRegistry] 与 [NotificationRuntimeFactoryRegistry] 注册。
 */
object NotificationHostInstaller {

    @Volatile
    private var installed: NotificationHostComposition? = null

    fun install(context: Context): NotificationHostComposition {
        installed?.let { return it }
        return synchronized(this) {
            installed?.let { return it }
            val appContext = context.applicationContext
            val composition = build(
                context = appContext,
                persistenceFile = File(appContext.noBackupFilesDir, DEFAULT_POLICY_FILE),
                outboxFile = File(appContext.noBackupFilesDir, DEFAULT_OUTBOX_FILE),
                outboxKeySource = AndroidKeyStoreOutboxKeySource(),
            )
            NotificationControlRegistry.install(composition.policyPort, composition.bindingPort)
            NotificationRuntimeFactoryRegistry.install(composition.runtimeFactory)
            installed = composition
            composition
        }
    }

    /**
     * 显式构建（测试与自定义接线）。不经进程缓存；调用方自行决定是否注册。
     */
    fun build(
        context: Context,
        persistenceFile: File,
        outboxFile: File,
        outboxKeySource: () -> SecretKey? = { null },
    ): NotificationHostComposition {
        val appContext = context.applicationContext
        val authority = PersistentNotificationPolicyAuthority(FileNotificationPolicyPersistence(persistenceFile))
        val outbox = outboxKeySource()?.let { key ->
            NotificationOutboxStore(
                persistence = FileEncryptedOutboxPersistence(outboxFile),
                encryptionKey = key,
            )
        }
        return NotificationHostComposition(
            context = appContext,
            authority = authority,
            persistenceFile = persistenceFile,
            outbox = outbox,
        )
    }

    /** 测试隔离用：清空进程装配缓存。 */
    fun resetForTest() {
        synchronized(this) { installed = null }
    }

    private const val DEFAULT_POLICY_FILE = "notification-policy-authority.bin"
    private const val DEFAULT_OUTBOX_FILE = "notification-outbox.bin"
}

/**
 * outbox 密钥来源：AndroidKeyStore 内生成/加载 256 位 AES 密钥，
 * 密钥材料永不出安全硬件。任何初始化失败都返回 null——没有密钥就没有
 * outbox，自动发送退化为不可用（fail-closed），绝不落一个明文密钥文件。
 */
class AndroidKeyStoreOutboxKeySource(
    private val alias: String = "openandroidintelligence.notification.outbox",
) : () -> SecretKey? {

    override fun invoke(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateAndStore(keyStore)
    } catch (failure: Throwable) {
        null
    }

    private fun generateAndStore(keyStore: KeyStore): SecretKey {
        check(!keyStore.containsAlias(alias)) { "outbox key exists but is not an AES secret key" }
        val generator = KeyGenerator.getInstance(KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM_AES = "AES"
        private const val KEY_SIZE_BITS = 256
    }
}
