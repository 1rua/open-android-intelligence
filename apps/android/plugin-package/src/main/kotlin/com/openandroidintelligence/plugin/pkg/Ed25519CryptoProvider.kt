package com.openandroidintelligence.plugin.pkg

import java.security.KeyFactory
import java.security.Signature
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAKey
import net.i2p.crypto.eddsa.EdDSASecurityProvider

/** 插件包验签使用随 App 打包的 Ed25519 实现，不依赖 API 33 才提供的系统算法名。 */
internal object Ed25519CryptoProvider {
    private val provider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { EdDSASecurityProvider() }

    fun keyFactory(): KeyFactory = KeyFactory.getInstance(EdDSAKey.KEY_ALGORITHM, provider)

    fun signature(): Signature = Signature.getInstance(EdDSAEngine.SIGNATURE_ALGORITHM, provider)
}
