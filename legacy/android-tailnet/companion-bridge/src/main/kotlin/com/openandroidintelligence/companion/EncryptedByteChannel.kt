package com.openandroidintelligence.companion

import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.Closeable
import java.io.IOException

/** 当加密字节通道建立失败或通信中断时抛出。 */
class CompanionChannelException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * 封装 Android 宿主与 Companion 之间的单用途不透明加密字节通道。
 *
 * 架构契约：
 * 1. Companion 仅作为密文泵，不持有任何 TLS 私钥、账号密码或 Gateway access token；
 * 2. 宿主通过此通道在其上运行标准 TLS、HTTP/SSE 客户端与签名；
 * 3. 任何远程异常（如 Companion 进程崩溃 DeadObjectException）均立即转换为
 *    异常并关闭本地文件描述符（Fail-Closed）。
 */
class EncryptedByteChannel(
    val pfd: ParcelFileDescriptor,
) : Closeable {

    override fun close() {
        try {
            pfd.close()
        } catch (_: IOException) {}
    }

    companion object {
        /**
         * 通过 Companion AIDL 接口请求打开单用途加密字节通道。
         *
         * @param transport 绑定的 Companion AIDL 代理
         * @param token 平台内核签发的单用途操作令牌
         * @return 建立好的加密字节通道封装
         * @throws CompanionChannelException 当远端崩溃、超时或返回空描述符时
         */
        fun open(
            transport: ICompanionTransport,
            token: SingleUseOperationToken,
        ): EncryptedByteChannel {
            val pfd: ParcelFileDescriptor = try {
                transport.openEncryptedByteChannel(
                    token.serializedToken,
                    token.destination.host,
                    token.destination.port,
                ) ?: throw CompanionChannelException("COMPANION_RETURNED_NULL_FD")
            } catch (e: DeadObjectException) {
                throw CompanionChannelException("COMPANION_PROCESS_DIED", e)
            } catch (e: RemoteException) {
                throw CompanionChannelException("COMPANION_IPC_FAILURE:${e.message}", e)
            }

            return EncryptedByteChannel(pfd)
        }

        /** 创建一对相互连接的 Socket 文件描述符（供本地测试与 Companion 模拟器使用）。 */
        fun createSocketPair(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
            val pair = ParcelFileDescriptor.createSocketPair()
            return Pair(pair[0], pair[1])
        }

        /** 创建一对单向管道文件描述符。 */
        fun createPipe(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
            val pipe = ParcelFileDescriptor.createPipe()
            return Pair(pipe[0], pipe[1])
        }
    }
}

