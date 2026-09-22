package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.auth.GatewayCredentialStore
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存版刷新凭据保管：替掉 Android Keystore，语义与生产实现一致
 * （按 profileId 隔离、内容原样往返）。
 */
internal class InMemoryCredentialStore : GatewayCredentialStore {
    private val refresh = ConcurrentHashMap<String, ByteArray>()
    private val deviceKeys = ConcurrentHashMap<String, ByteArray>()

    override fun saveRefresh(profileId: String, credential: ByteArray) {
        require(credential.isNotEmpty()) { "refresh credential must not be empty" }
        refresh[profileId] = credential.copyOf()
    }

    override fun loadRefresh(profileId: String): ByteArray? = refresh[profileId]?.copyOf()

    override fun clearRefresh(profileId: String) {
        refresh.remove(profileId)
    }

    override fun clearDeviceKey(profileId: String) {
        deviceKeys.remove(profileId)
    }

    fun hasRefresh(profileId: String): Boolean = refresh.containsKey(profileId)
}

/**
 * 内存版设备密钥：算法仍是真实的 Ed25519（JDK 自带），只是不需要 Android Keystore。
 */
internal class InMemoryDeviceKeySource : DeviceKeySource {
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    override fun publicKeyBase64Url(profileId: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.public.encoded)

    override fun sign(profileId: String, preimage: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(preimage)
            sign()
        }
}
