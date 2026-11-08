package com.openandroidintelligence.conversation.voice

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceRecordingTest {
    private fun recordingFile(): File = File.createTempFile("voice_test_", ".m4a").apply { deleteOnExit() }

    @Test fun finishedVoicePreservesOriginalAudioAndReopensItForAttachmentStaging() {
        val file = recordingFile()
        val audio = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 109, 112, 52, 50)
        val device = FakeRecorder(onStart = { it.writeBytes(audio) })
        val recording = VoiceRecording(file, device)
        recording.start()
        val voice = recording.finish()
        val selection = voice.selection()
        assertEquals("audio/mp4", selection.mediaType)
        assertTrue(selection.filename.endsWith(".m4a"))
        assertFalse(selection.recoverAfterRestart)
        repeat(2) {
            selection.contentSource.openStream().use { assertArrayEquals(audio, it.readBytes()) }
        }
        assertEquals(1, device.stops)
        assertEquals(1, device.releases)
        voice.close()
        assertFalse(file.exists())
    }

    @Test fun recordingReportsElapsedDurationAndNeverNegative() {
        val file = recordingFile()
        var now = 1_000L
        val recording = VoiceRecording(file, FakeRecorder(onStart = { it.writeText("audio") })) { now }
        recording.start()
        now = 4_250L
        assertEquals(3_250L, recording.finish().durationMs)

        // 设备时钟回拨一类的异常也不能产出负时长。
        val backwards = recordingFile()
        var clock = 10_000L
        val negative = VoiceRecording(backwards, FakeRecorder(onStart = { it.writeText("audio") })) { clock }
        negative.start()
        clock = 9_000L
        assertEquals(0L, negative.finish().durationMs)
    }

    @Test fun cancelReleasesMicrophoneAndDeletesAudioWithoutFinishingOrSending() {
        val file = recordingFile()
        val device = FakeRecorder(onStart = { it.writeText("audio") })
        val recording = VoiceRecording(file, device)
        recording.start()
        recording.close()
        recording.close()
        assertEquals(0, device.stops)
        assertEquals(1, device.releases)
        assertFalse(file.exists())
        assertThrows(IllegalStateException::class.java) { recording.finish() }
    }

    @Test fun failedPrepareOrTooShortStopReleasesRecorderAndDeletesPartialAudio() {
        for (failAtStart in listOf(true, false)) {
            val file = recordingFile()
            val device = FakeRecorder(
                onStart = { it.writeText("partial"); if (failAtStart) error("prepare failed") },
                onStop = { error("recording too short") },
            )
            val recording = VoiceRecording(file, device)
            assertThrows(IllegalStateException::class.java) {
                recording.start()
                recording.finish()
            }
            assertEquals(1, device.releases)
            assertFalse(file.exists())
        }
    }

    @Test fun anEmptyRecordingCannotBecomeAnAttachment() {
        val file = recordingFile()
        val device = FakeRecorder()
        val recording = VoiceRecording(file, device)
        recording.start()
        assertThrows(IOException::class.java) { recording.finish() }
        assertEquals(1, device.releases)
        assertFalse(file.exists())
    }

    @Test fun amplitudeIsReadOnlyWhileActivelyRecording() {
        val file = recordingFile()
        val device = FakeRecorder(onStart = { it.writeText("audio") }) { 12_000 }
        val recording = VoiceRecording(file, device) { 0L }
        assertEquals(0, recording.maxAmplitude())
        recording.start()
        assertEquals(12_000, recording.maxAmplitude())
        recording.close()
        assertEquals(0, recording.maxAmplitude())
    }

    private class FakeRecorder(
        val onStart: (File) -> Unit = {},
        val onStop: () -> Unit = {},
        val amplitude: () -> Int = { 0 },
    ) : VoiceRecorderDevice {
        var stops = 0
        var releases = 0
        override fun start(file: File) = onStart(file)
        override fun stop() { stops++; onStop() }
        override fun release() { releases++ }
        override fun maxAmplitude(): Int = amplitude()
    }
}
