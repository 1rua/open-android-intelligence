package com.openandroidintelligence.gateway.auth

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAKey
import net.i2p.crypto.eddsa.EdDSASecurityProvider

/** 固定使用随 App 打包的 Ed25519 实现，避免依赖 API 33 才提供的系统算法名。 */
internal object Ed25519CryptoProvider {
    private val provider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { EdDSASecurityProvider() }

    fun keyFactory(): KeyFactory = KeyFactory.getInstance(EdDSAKey.KEY_ALGORITHM, provider)

    fun keyPairGenerator(): KeyPairGenerator = KeyPairGenerator.getInstance(EdDSAKey.KEY_ALGORITHM, provider)

    fun signature(): Signature = Signature.getInstance(EdDSAEngine.SIGNATURE_ALGORITHM, provider)
}
