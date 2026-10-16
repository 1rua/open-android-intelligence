package com.openandroidintelligence.mobile

import android.content.Context
import android.media.MediaRecorder
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import java.io.File
import java.io.IOException

/** A completed recording owns its private cache file until attachment staging finishes. */
internal class RecordedVoice(private val file: File) : AutoCloseable {
    fun selection() = LocalAttachmentSelection(
        filename = file.name,
        mediaType = "audio/mp4",
        contentSource = AttachmentContentSource { file.inputStream() },
        recoverAfterRestart = false,
    )

    override fun close() { file.delete() }
}

/** Small boundary lets cancellation and recorder failures be verified without a microphone. */
internal interface VoiceRecorderDevice {
    fun start(file: File)
    fun stop()
    fun release()
}

internal class VoiceRecording(
    private val file: File,
    private val recorder: VoiceRecorderDevice,
) : AutoCloseable {
    private var active = false
    private var released = false

    fun start() {
        check(!released && !active)
        try {
            recorder.start(file)
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
            active = false
            release()
            return RecordedVoice(file)
        } catch (cause: Exception) {
            close()
            throw cause
        }
    }

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
                })
            } catch (cause: Exception) {
                file.delete()
                throw cause
            }
        }
    }
}
