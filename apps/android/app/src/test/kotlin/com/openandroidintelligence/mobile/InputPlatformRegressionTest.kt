package com.openandroidintelligence.mobile

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InputPlatformRegressionTest {
    @Test fun mainWindowResizesInsteadOfPanningTheFocusedComposer() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
    }

    @Test fun microphonePermissionIsDeclaredForLocalAudioRecording() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.RECORD_AUDIO))
    }

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
        assertThrows(java.io.IOException::class.java) { recording.finish() }
        assertEquals(1, device.releases)
        assertFalse(file.exists())
    }

    private fun recordingFile(): File = File.createTempFile("voice_test_", ".m4a",
        ApplicationProvider.getApplicationContext<Application>().cacheDir)

    private class FakeRecorder(
        val onStart: (File) -> Unit = {},
        val onStop: () -> Unit = {},
    ) : VoiceRecorderDevice {
        var stops = 0
        var releases = 0
        override fun start(file: File) = onStart(file)
        override fun stop() { stops++; onStop() }
        override fun release() { releases++ }
    }
}
