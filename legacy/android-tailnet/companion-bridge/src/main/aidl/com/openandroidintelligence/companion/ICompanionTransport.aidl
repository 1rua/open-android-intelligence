package com.openandroidintelligence.companion;

import android.os.ParcelFileDescriptor;

/**
 * Android 宿主与 Companion 之间的进程间通信契约。
 * Companion 仅作为不透明的加密字节通道，不接收任何明文或 Gateway 凭据。
 */
interface ICompanionTransport {
    /**
     * 打开单用途加密字节通道。
     *
     * @param serializedToken 由平台内核签发的短期单用途操作令牌
     * @param host 目标主机名
     * @param port 目标端口
     * @return 供宿主与远端建立 TLS 会话的 ParcelFileDescriptor (SocketPair 或 Pipe 端点)
     */
    ParcelFileDescriptor openEncryptedByteChannel(
        String serializedToken,
        String host,
        int port
    );
}

