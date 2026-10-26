package com.openandroidintelligence.companion

import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class CompanionFailureInstrumentedTest {

    private val binding = CompanionBinding(
        hostUid = 10001,
        companionUid = 10002,
        pluginId = "org.openandroidintelligence.transport.tailscale",
        accountId = "account-test",
        pairingId = "pairing-test",
        grantRevision = 1L,
    )

    private val destination = Destination(host = "gw.example.com", port = 443)

    @Test
    fun failsClosedWhenCompanionReturnsNull() {
        val issuer = OperationTokenIssuer()
        val token = issuer.issue(binding, "connect", destination, 30_000L)

        val fakeTransport = object : ICompanionTransport.Stub() {
            override fun openEncryptedByteChannel(
                serializedToken: String?,
                host: String?,
                port: Int,
            ): ParcelFileDescriptor? {
                return null
            }
        }

        try {
            EncryptedByteChannel.open(fakeTransport, token)
            fail("Companion 返回 null 时必须抛出异常")
        } catch (e: CompanionChannelException) {
            assertTrue(e.message!!.contains("COMPANION_RETURNED_NULL_FD"))
        }
    }

    @Test
    fun failsClosedWhenCompanionProcessDies() {
        val issuer = OperationTokenIssuer()
        val token = issuer.issue(binding, "connect", destination, 30_000L)

        val dyingTransport = object : ICompanionTransport.Stub() {
            override fun openEncryptedByteChannel(
                serializedToken: String?,
                host: String?,
                port: Int,
            ): ParcelFileDescriptor {
                throw DeadObjectException()
            }
        }

        try {
            EncryptedByteChannel.open(dyingTransport, token)
            fail("Companion 崩溃时必须抛出异常")
        } catch (e: CompanionChannelException) {
            assertTrue(e.message!!.contains("COMPANION_PROCESS_DIED"))
        }
    }

    @Test
    fun socketPairPassesEncryptedBytesOpaquely() {
        val (hostFd, companionFd) = EncryptedByteChannel.createSocketPair()
        val hostChannel = EncryptedByteChannel(hostFd)

        val payload = "ENCRYPTED_TLS_CLIENT_HELLO".toByteArray(Charsets.UTF_8)

        // 宿主向通道写入数据
        val outStream = FileOutputStream(hostChannel.pfd.fileDescriptor)
        outStream.write(payload)
        outStream.flush()

        // Companion 端读出数据
        val inStream = FileInputStream(companionFd.fileDescriptor)
        val readBuf = ByteArray(payload.size)
        val readCount = inStream.read(readBuf)

        assertEquals(payload.size, readCount)
        assertEquals("ENCRYPTED_TLS_CLIENT_HELLO", String(readBuf, Charsets.UTF_8))

        hostChannel.close()
        companionFd.close()
    }

    @Test
    fun androidPackageInfoProviderReadsSelfContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = AndroidPackageInfoProvider(context)
        val info = provider.getPackageInfo(context.packageName)

        assertNotNull("自身上下文必须可被检索", info)
        assertEquals(context.packageName, info!!.packageName)
        assertTrue(info.versionCode >= 0)
    }
}

