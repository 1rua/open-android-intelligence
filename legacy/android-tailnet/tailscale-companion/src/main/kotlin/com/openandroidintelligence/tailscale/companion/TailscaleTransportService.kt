package com.openandroidintelligence.tailscale.companion

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.openandroidintelligence.companion.EncryptedByteChannel
import com.openandroidintelligence.companion.ICompanionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Tailscale Companion 传输服务。
 *
 * 核心安全架构：
 * 1. Companion 作为独立的 Android Service / Process 运行；
 * 2. Companion 仅持有 Tailscale 本地连接能力，不持有任何 Gateway 凭据、私钥或 access token；
 * 3. 宿主通过 AIDL 传递单用途令牌并获取 ParcelFileDescriptor；
 * 4. Companion 仅作为不透明的字节泵（Opaque TLS Byte Pump），TLS 握手与加解密始终在主 App 内完成；
 * 5. 服务停止或崩溃时所有通道立即关闭（Fail-Closed）。
 */
class TailscaleTransportService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeChannels = ConcurrentHashMap<String, Closeable>()

    private val binder = object : ICompanionTransport.Stub() {
        override fun openEncryptedByteChannel(
            serializedToken: String?,
            host: String?,
            port: Int,
        ): ParcelFileDescriptor? {
            // No authenticated issuer exchange or tsnet pump is composed yet.
            // Keep the transport unavailable, including direct Binder calls.
            return null
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        for (channel in activeChannels.values) {
            try {
                channel.close()
            } catch (_: Exception) {}
        }
        activeChannels.clear()
        serviceScope.cancel()
        super.onDestroy()
    }
}
