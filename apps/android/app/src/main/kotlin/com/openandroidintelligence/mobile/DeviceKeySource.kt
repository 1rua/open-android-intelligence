package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.auth.Ed25519DeviceKeyStore
import java.io.File

/**
 * 设备密钥的签名面：登录时上报公钥，签名每个出站请求。
 *
 * 之所以抽成接口，是因为真实实现（[Ed25519DeviceKeyStore]）把私钥封在
 * Android Keystore 的 AES 包装密钥里，只有真机能提供；JVM 单测无法伪造
 * Keystore，只能换掉这一层。生产路径永远走 [KeystoreDeviceKeySource]，
 * 算法与密钥保管方式都不变。
 */
interface DeviceKeySource {
    fun publicKeyBase64Url(profileId: String): String

    fun sign(profileId: String, preimage: ByteArray): ByteArray
}

/** 生产实现：Ed25519 私钥以 Keystore 包装密钥加密后落在 App 私有目录。 */
class KeystoreDeviceKeySource(storageDir: File) : DeviceKeySource {
    private val store = Ed25519DeviceKeyStore(storageDir)

    override fun publicKeyBase64Url(profileId: String): String = store.publicKeyBase64Url(profileId)

    override fun sign(profileId: String, preimage: ByteArray): ByteArray = store.sign(profileId, preimage)
}
