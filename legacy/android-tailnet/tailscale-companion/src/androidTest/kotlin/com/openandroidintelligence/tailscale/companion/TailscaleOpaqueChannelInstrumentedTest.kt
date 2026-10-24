package com.openandroidintelligence.tailscale.companion

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.companion.ICompanionTransport
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TailscaleOpaqueChannelInstrumentedTest {

    @Test
    fun serviceRejectsInvalidTokensAndOpensValidChannels() {
        val service = TailscaleTransportService()
        val binder = service.onBind(Intent()) as ICompanionTransport

        // 1. 无效 Token 格式拒绝
        val invalidFd = binder.openEncryptedByteChannel(
            "invalid-token",
            "gw.example.com",
            443,
        )
        assertNull("非法 Token 必须返回 null", invalidFd)

        // 2. 非法 Host/Port 拒绝
        val badPortFd = binder.openEncryptedByteChannel(
            "altok:v1:token-1:nonce-1:gw.example.com:443",
            "",
            -1,
        )
        assertNull("非法端口必须返回 null", badPortFd)

        // 3. 有效 Token 打开通道
        val validFd = binder.openEncryptedByteChannel(
            "altok:v1:token-valid:nonce-valid:gw.example.com:443",
            "gw.example.com",
            443,
        )
        assertNotNull("有效 Token 必须返回文件描述符", validFd)
        assertTrue("文件描述符必须有效", validFd!!.fd > 0)

        validFd.close()
        service.onDestroy()
    }
}

