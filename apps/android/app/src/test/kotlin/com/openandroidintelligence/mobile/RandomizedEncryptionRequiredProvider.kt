package com.openandroidintelligence.mobile

import java.security.AlgorithmParameters
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * JVM 中复现 Android Keystore 的两个真实约束：密钥不可导出，加密 IV 必须由提供者生成。
 * 加解密仍由 SunJCE 执行；测试仅在密钥和初始化边界拒绝 Android 同样拒绝的调用。
 */
internal class RandomizedEncryptionRequiredProvider : Provider(NAME, 1.0, "测试 Android Keystore 加密约束") {
    init {
        put("Cipher.AES/GCM/NoPadding", RandomizedGcmCipher::class.java.name)
    }

    companion object {
        const val NAME = "OaiRandomizedEncryptionRequired"
    }
}

internal class NonExportableAesKey : SecretKey {
    val delegate = SecretKeySpec(ByteArray(32) { 19 }, "AES")
    override fun getAlgorithm(): String = "AES"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

class RandomizedGcmCipher : CipherSpi() {
    private val delegate = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE")

    override fun engineSetMode(mode: String) {
        require(mode.equals("GCM", ignoreCase = true))
    }

    override fun engineSetPadding(padding: String) {
        require(padding.equals("NoPadding", ignoreCase = true))
    }

    override fun engineGetBlockSize(): Int = delegate.blockSize
    override fun engineGetOutputSize(inputLen: Int): Int = delegate.getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = delegate.iv
    override fun engineGetParameters(): AlgorithmParameters? = delegate.parameters

    private fun backing(key: Key): SecretKey =
        (key as? NonExportableAesKey)?.delegate ?: throw InvalidKeyException("测试提供者只接受不可导出密钥")

    override fun engineInit(opmode: Int, key: Key, random: SecureRandom?) {
        delegate.init(opmode, backing(key), random)
    }

    override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameterSpec?, random: SecureRandom?) {
        val backing = backing(key)
        if (opmode == Cipher.ENCRYPT_MODE && params != null) {
            throw InvalidAlgorithmParameterException("加密时禁止调用方指定 IV")
        }
        delegate.init(opmode, backing, params, random)
    }

    override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameters?, random: SecureRandom?) {
        val backing = backing(key)
        if (opmode == Cipher.ENCRYPT_MODE && params != null) {
            throw InvalidAlgorithmParameterException("加密时禁止调用方指定 IV")
        }
        delegate.init(opmode, backing, params, random)
    }

    override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray? =
        delegate.update(input, inputOffset, inputLen)

    override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int =
        delegate.update(input, inputOffset, inputLen, output, outputOffset)

    override fun engineDoFinal(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray =
        delegate.doFinal(input, inputOffset, inputLen)

    override fun engineDoFinal(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int =
        delegate.doFinal(input, inputOffset, inputLen, output, outputOffset)
}
