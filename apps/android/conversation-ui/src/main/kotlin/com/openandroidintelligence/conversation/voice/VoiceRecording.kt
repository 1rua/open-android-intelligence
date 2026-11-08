package com.openandroidintelligence.conversation.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import java.io.File
import java.io.IOException

/**
 * 已完成录音的私有缓存文件。
 *
 * 附件暂存结束前由调用方负责关闭；关闭时删除缓存文件，避免残留临时音频。
 */
class RecordedVoice(
    private val file: File,
    /** 录音时长（毫秒），从 `MediaRecorder.start()` 成功返回算到停止为止。 */
    val durationMs: Long,
) : AutoCloseable {
    fun selection() = LocalAttachmentSelection(
        filename = file.name,
        mediaType = "audio/mp4",
        contentSource = AttachmentContentSource { file.inputStream() },
        recoverAfterRestart = false,
    )

    override fun close() {
        file.delete()
    }
}

/**
 * 录音设备边界：取消录音和录制失败可以在没有麦克风的测试里验证。
 */
interface VoiceRecorderDevice {
    fun start(file: File)
    fun stop()
    fun release()

    /** 当前最大振幅（0..32767）；设备不支持时返回 0。 */
    fun maxAmplitude(): Int = 0
}

/**
 * 一次本地语音录制。编码固定为 MPEG_4 + AAC 44.1kHz 96kbps，
 * 临时文件落在应用缓存目录的 `voice-recordings/` 下。
 *
 * 既可由 [create] 创建真实 [MediaRecorder] 录制，也可注入 [VoiceRecorderDevice] 做测试。
 */
class VoiceRecording(
    private val file: File,
    private val recorder: VoiceRecorderDevice,
    private val elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() },
) : AutoCloseable, VoiceAmplitudeSource {
    private var active = false
    private var released = false
    private var startedAtMillis = 0L

    fun start() {
        check(!released && !active)
        try {
            recorder.start(file)
            startedAtMillis = elapsedRealtimeMillis()
            active = true
        } catch (cause: Exception) {
            close()
            throw cause
        }
    }

    fun finish(): RecordedVoice {
        check(active && !released)
        try {
            recorder.stop()
            if (file.length() == 0L) throw IOException("EMPTY_VOICE_RECORDING")
            val durationMs = (elapsedRealtimeMillis() - startedAtMillis).coerceAtLeast(0L)
            active = false
            release()
            return RecordedVoice(file, durationMs)
        } catch (cause: Exception) {
            close()
            throw cause
        }
    }

    /** 供 [VoiceLevelMeter] 采样；只在录制进行中读取真实设备振幅。 */
    override fun maxAmplitude(): Int =
        if (active && !released) runCatching { recorder.maxAmplitude() }.getOrDefault(0) else 0

    private fun release() {
        if (!released) {
            released = true
            runCatching { recorder.release() }
        }
    }

    override fun close() {
        active = false
        release()
        file.delete()
    }

    companion object {
        fun create(context: Context): VoiceRecording {
            val directory = File(context.cacheDir, "voice-recordings").apply { mkdirs() }
            val file = File.createTempFile("voice_", ".m4a", directory)
            return try {
                VoiceRecording(file, object : VoiceRecorderDevice {
                    @Suppress("DEPRECATION")
                    private val recorder = MediaRecorder()

                    override fun start(file: File) {
                        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        recorder.setAudioSamplingRate(44_100)
                        recorder.setAudioEncodingBitRate(96_000)
                        recorder.setOutputFile(file.absolutePath)
                        recorder.prepare()
                        recorder.start()
                    }

                    override fun stop() = recorder.stop()
                    override fun release() = recorder.release()
                    override fun maxAmplitude(): Int = recorder.maxAmplitude
                })
            } catch (cause: Exception) {
                file.delete()
                throw cause
            }
        }
    }
}
