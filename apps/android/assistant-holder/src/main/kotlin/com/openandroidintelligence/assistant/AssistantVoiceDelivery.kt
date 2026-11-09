package com.openandroidintelligence.assistant

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.openandroidintelligence.conversation.voice.RecordedVoice

/**
 * 把一段已经结束的录音交给宿主发送。
 *
 * 调用时机：[com.openandroidintelligence.conversation.voice.VoiceRecorder] 的 `onRecorded` 回调（结束录音与写文件都在后台
 * 线程上完成），因此这里的文件读取不在主线程。交付完成后**无论成功与否**都会关闭
 * [voice]：临时音频只有一次生命周期，不能让它在缓存目录里留下第二份。
 *
 * 字节传递与 `AssistantBridgeClient.attachScreen()` 相同：先在本地读出确切长度，
 * 再切回主线程用 `ParcelFileDescriptor.createPipe()` 交给主进程，后台线程把字节写进管道。
 * 主进程若在 [deliveryTimeoutMillis] 内没有读完，看门狗会关闭写端让对面失败关闭，
 * 而不是无限等待。
 *
 * @param onSent 主进程**真正接收**了这条语音后回调（主线程）。界面据此推进
 *   `VoiceSent` 展开会话；失败路径不调用它，界面因此保持紧凑形态。
 *
 * 失败语义：
 * - 读不出字节（文件已被删除、读取异常）→ 一次性提示，不发送半截数据；
 * - 管道创建失败 → 同上；
 * - [AssistantBridgeClient.sendVoice] 返回 false（桥接不可用/已断开）→ 不额外提示：
 *   桥接层自己已经把"未连接"写进状态通道的 notice，界面会如实显示。
 */
internal fun sendRecordedVoice(
    client: AssistantBridgeClient,
    voice: RecordedVoice,
    onSent: () -> Unit = {},
    handler: Handler = Handler(Looper.getMainLooper()),
    deliveryTimeoutMillis: Long = VOICE_DELIVERY_TIMEOUT_MILLIS,
) {
    try {
        val selection = voice.selection()
        val durationMs = voice.durationMs
        val mediaType = selection.mediaType
        val bytes = try {
            selection.contentSource.openStream().use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
        // 与圈选附件一致：Binder 命令、提示与状态读写都在主线程发起，管道写入留在后台线程。
        handler.post {
            if (bytes == null || bytes.isEmpty()) {
                client.showAdvisory("录音无法读取，请重新录制")
                return@post
            }
            val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse {
                client.showAdvisory("录音无法读取，请重新录制")
                return@post
            }
            val delivered = client.sendVoice(
                content = pipe[0],
                size = bytes.size,
                durationMs = durationMs,
                mediaType = mediaType,
            )
            if (!delivered) {
                pipe.forEach { runCatching { it.close() } }
                return@post
            }
            // 命令已被主进程接收：界面据此展开会话。命令接收 ≠ 上传成功，
            // 但界面需要的是"消息已进入时间线"，后续状态帧会给出真实结果。
            onSent()
            // 主进程若超时仍未读完，关闭写端让那边失败关闭；音频不留在半截状态。
            val timeout = Runnable { runCatching { pipe[1].close() } }
            handler.postDelayed(timeout, deliveryTimeoutMillis)
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
                } catch (_: Exception) {
                    // 读端提前关闭是允许的：主进程自己会给出失败提示。
                } finally {
                    handler.removeCallbacks(timeout)
                    runCatching { pipe[0].close() }
                }
            }, "assistant-voice-delivery").start()
        }
    } finally {
        voice.close()
    }
}

/** 与屏幕附件一致：主进程 10 秒内读不完就放弃这次交付。 */
private const val VOICE_DELIVERY_TIMEOUT_MILLIS = 10_000L
