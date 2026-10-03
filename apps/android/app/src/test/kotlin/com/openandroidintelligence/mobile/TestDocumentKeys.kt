package com.openandroidintelligence.mobile

import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import javax.crypto.spec.SecretKeySpec

/** Robolectric lacks Android Keystore; the runtime still uses real AES-GCM files. */
internal object TestDocumentKeys:AesGcmKeyProvider {
    override fun getOrCreate()=SecretKeySpec(ByteArray(32){11},"AES")
    override fun delete(){}
}
