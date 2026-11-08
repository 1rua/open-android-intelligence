package com.openandroidintelligence.assistant

import com.openandroidintelligence.conversation.voice.RecordedVoice
import com.openandroidintelligence.conversation.voice.VoiceRecorderDevice
import com.openandroidintelligence.conversation.voice.VoiceRecording
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 助理录音状态机的回归。
 *
 * 这一层钉死四件界面测试观察不到、但会直接造成「重复语音消息」或「音频发错对话」的规则：
 * 1. 录音期间状态里带真实时长与会话身份；
 * 2. 取消释放麦克风并删除临时文件，不产生可发送的音频；
 * 3. 同一个录音连点两次发送，只有第一次被接受；
 * 4. 会话切换后录音被丢弃，绝不交给宿主。
 *
 * 计时与采样用虚拟时钟精确推进（而不是真实等待），也不依赖真实麦克风。
 * 这里只用 [advanceTimeBy] + [runCurrent]：电平采样是无限轮询循环，`advanceUntilIdle`
 * 会一直推进虚拟时间、永远不到"空闲"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantVoiceRecorderTest {

    @get:Rule val temp = TemporaryFolder()

    private var now = 1_000_000L
    private var created = 0
    private var rejection: AssistantVoiceRejection? = null
    private val delivered = mutableListOf<RecordedVoice>()
    private val deliveredSessions = mutableListOf<String?>()

    private fun TestScope.recorder(
        device: FakeRecorderDevice = FakeRecorderDevice(),
        create: (() -> VoiceRecording)? = null,
    ): AssistantVoiceRecorder = AssistantVoiceRecorder(
        createRecording = create ?: {
            created++
            VoiceRecording(recordingFile(), device) { now }
        },
        onRecorded = { voice, sessionKey ->
            delivered += voice
            deliveredSessions += sessionKey
        },
        onRejected = { rejection = it },
        elapsedRealtimeMillis = { now },
        pollIntervalMs = POLL_MS,
        dispatcher = StandardTestDispatcher(testScheduler),
        finishDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun recordingFile(): File = temp.newFile("voice_${created}.m4a")

    /**
     * 建一段落在指定文件名上的录音，并把文件句柄交给测试断言「是否被删除」。
     *
     * 与 [recorder] 的默认工厂区别：这里让测试持有同一个 [File]，取消/失败后可以直接检查它。
     */
    private fun recordingOn(
        name: String,
        device: FakeRecorderDevice,
        onCreated: (File) -> Unit,
    ): VoiceRecording {
        created++
        val file = temp.newFile(name)
        onCreated(file)
        return VoiceRecording(file, device) { now }
    }

    @Test
    fun startReportsRecordingWithTheSessionItBelongsTo() = runTest {
        val recorder = recorder()
        assertTrue(recorder.start(sessionKey = "thread_a"))
        runCurrent()

        val state = recorder.state.value as AssistantVoiceState.Recording
        assertEquals("thread_a", state.sessionKey)
        assertEquals(0L, state.elapsedMs)
        assertEquals(0f, state.level)

        recorder.close()
    }

    @Test
    fun elapsedTimeComesFromTheRealClockNotFromFrameCounts() = runTest {
        val recorder = recorder()
        recorder.start(sessionKey = "thread_a")
        runCurrent()

        now += 1_500L
        // 虚拟时钟在目标时刻本身不执行任务，多推 1ms 让 50ms 的计时循环真正跑过一帧。
        advanceTimeBy(POLL_MS + 1)
        runCurrent()

        val state = recorder.state.value as AssistantVoiceState.Recording
        assertEquals(1_500L, state.elapsedMs)
        recorder.close()
    }

    @Test
    fun theLevelFollowsTheDeviceAmplitude() = runTest {
        val recorder = recorder(device = FakeRecorderDevice(amplitude = 16_384))
        recorder.start()
        runCurrent()

        val state = recorder.state.value as AssistantVoiceState.Recording
        assertTrue("电平应来自设备振幅，实际 ${state.level}", state.level > 0.4f)
        assertTrue("电平不得超过 1", state.level <= 1f)
        recorder.close()
    }

    @Test
    fun startingTwiceIsIgnoredSoOneMicrophoneIsNeverSharedByTwoRecordings() = runTest {
        val recorder = recorder()
        assertTrue(recorder.start())
        assertFalse("已经在录音时再次开始不应产生第二段录音", recorder.start())
        assertEquals(1, created)
        recorder.close()
    }

    @Test
    fun cancelReleasesTheMicrophoneAndDeletesTheTemporaryFile() = runTest {
        val device = FakeRecorderDevice()
        var file: File? = null
        val recorder = recorder(device = device) {
            recordingOn("voice_cancel.m4a", device) { file = it }
        }
        assertTrue(recorder.start())
        runCurrent()
        assertNotNull(file)

        assertTrue(recorder.cancel())

        assertEquals(AssistantVoiceState.Idle, recorder.state.value)
        assertEquals("取消必须释放麦克风", 1, device.releases)
        assertEquals("取消不得走 stop", 0, device.stops)
        assertFalse("临时音频必须删除", file!!.exists())
        assertTrue("取消不产生可发送的音频", delivered.isEmpty())
        recorder.close()
    }

    @Test
    fun cancelWithoutARecordingIsRejectedWithoutSideEffects() = runTest {
        val recorder = recorder()

        assertFalse(recorder.cancel())
        assertEquals(AssistantVoiceState.Idle, recorder.state.value)
        // close 也必须是幂等的：窗口销毁时可能被重复调用。
        recorder.close()
        recorder.close()
    }

    @Test
    fun sendingTwiceDeliversOnlyOneVoice() = runTest {
        val recorder = recorder()
        recorder.start(sessionKey = "thread_a")
        runCurrent()

        assertTrue(recorder.send { "thread_a" })
        assertTrue(recorder.state.value is AssistantVoiceState.Sending)
        assertFalse("同一段录音的第二次发送必须被拒绝", recorder.send { "thread_a" })
        assertEquals(AssistantVoiceRejection.ALREADY_SENDING, rejection)
        runCurrent()

        assertEquals("只能交付一条语音", 1, delivered.size)
        assertEquals(listOf<String?>("thread_a"), deliveredSessions)
        assertEquals(AssistantVoiceState.Idle, recorder.state.value)
        assertFalse("交付后再点发送没有可发送内容", recorder.send { "thread_a" })
        assertEquals(AssistantVoiceRejection.NOT_RECORDING, rejection)
        delivered.forEach { it.close() }
        recorder.close()
    }

    @Test
    fun aFinishedVoiceCarriesItsRealDurationAndAudio() = runTest {
        var file: File? = null
        val audio = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)
        val device = FakeRecorderDevice(onStart = { it.writeBytes(audio) })
        val recorder = recorder(device = device) {
            recordingOn("voice_sent.m4a", device) { file = it }
        }
        recorder.start()
        runCurrent()
        now += 2_400L

        assertTrue(recorder.send { null })
        runCurrent()

        val voice = delivered.single()
        assertEquals(2_400L, voice.durationMs)
        assertTrue("交付的录音文件必须仍然存在", file!!.exists())
        val selection = voice.selection()
        assertEquals("audio/mp4", selection.mediaType)
        selection.contentSource.openStream().use { assertTrue(it.readBytes().contentEquals(audio)) }
        voice.close()
        assertFalse("宿主关闭录音后文件删除", file!!.exists())
        recorder.close()
    }

    @Test
    fun switchingSessionDuringRecordingDiscardsTheAudio() = runTest {
        val device = FakeRecorderDevice()
        var file: File? = null
        val recorder = recorder(device = device) {
            recordingOn("voice_switch.m4a", device) { file = it }
        }
        recorder.start(sessionKey = "thread_a")
        runCurrent()

        assertTrue(recorder.onSessionChanged("thread_b"))

        assertEquals(AssistantVoiceState.Idle, recorder.state.value)
        assertEquals(1, device.releases)
        assertFalse("切会话后临时音频必须删除", file!!.exists())
        assertEquals(AssistantVoiceRejection.SESSION_CHANGED, rejection)
        assertTrue("切会话的录音不得交付", delivered.isEmpty())
        recorder.close()
    }

    @Test
    fun onSessionChangedIsANoOpWhenTheSessionIsUnchanged() = runTest {
        val recorder = recorder()
        recorder.start(sessionKey = "thread_a")
        runCurrent()

        assertFalse(recorder.onSessionChanged("thread_a"))
        assertTrue(recorder.state.value is AssistantVoiceState.Recording)
        recorder.close()
    }

    @Test
    fun sendingAfterTheSessionChangedIsRejectedAndTheAudioIsDiscarded() = runTest {
        val device = FakeRecorderDevice()
        var file: File? = null
        val recorder = recorder(device = device) {
            recordingOn("voice_stale.m4a", device) { file = it }
        }
        recorder.start(sessionKey = "thread_a")
        runCurrent()

        var currentSession = "thread_a"
        assertTrue(recorder.send { currentSession })
        // 写文件期间用户切换了会话：结束之后必须再校验一次。
        currentSession = "thread_b"
        runCurrent()

        assertTrue("切会话后不得交付", delivered.isEmpty())
        assertEquals(AssistantVoiceRejection.SESSION_CHANGED, rejection)
        assertFalse(file!!.exists())
        assertEquals(AssistantVoiceState.Idle, recorder.state.value)
        recorder.close()
    }

    @Test
    fun aRecorderThatCannotStartReportsFailureAndStaysIdle() = runTest {
        val recorder = recorder { error("microphone busy") }

        assertFalse(recorder.start())
        assertEquals(AssistantVoiceState.Idle, recorder.state.value)
        assertEquals(AssistantVoiceRejection.RECORDING_FAILED, rejection)
        recorder.close()
    }

    @Test
    fun aRecordingThatFailsToFinishIsCleanedUpAndNeverDelivered() = runTest {
        val device = FakeRecorderDevice(onStop = { error("recording too short") })
        var file: File? = null
        val recorder = recorder(device = device) {
            recordingOn("voice_failed.m4a", device) { file = it }
        }
        recorder.start()
        runCurrent()

        assertTrue(recorder.send { null })
        runCurrent()

        assertTrue(delivered.isEmpty())
        assertEquals(AssistantVoiceRejection.RECORDING_FAILED, rejection)
        assertEquals(1, device.releases)
        assertFalse(file!!.exists())
        assertEquals(AssistantVoiceState.Idle, recorder.state.value)
        recorder.close()
    }

    @Test
    fun closeCancelsAnActiveRecordingSoNoAudioSurvivesWindowDismissal() = runTest {
        val device = FakeRecorderDevice()
        var file: File? = null
        val recorder = recorder(device = device) {
            recordingOn("voice_close.m4a", device) { file = it }
        }
        recorder.start(sessionKey = "thread_a")
        runCurrent()

        recorder.close()
        runCurrent()

        assertEquals(AssistantVoiceState.Idle, recorder.state.value)
        assertEquals(1, device.releases)
        assertFalse(file!!.exists())
        assertTrue("关闭窗口不会留下待发送录音", delivered.isEmpty())
    }

    @Test
    fun closingWhileSendingDoesNotStrandTheStateOrTheTemporaryFile() = runTest {
        val device = FakeRecorderDevice()
        var file: File? = null
        val recorder = recorder(device = device) {
            recordingOn("voice_closing.m4a", device) { file = it }
        }
        recorder.start()
        runCurrent()

        // 用户点发送后立刻收起窗口：交付已经在写文件，close() 不能把它打断在半路。
        assertTrue(recorder.send { null })
        recorder.close()
        runCurrent()

        assertEquals("已经接受的发送必须完成交付", 1, delivered.size)
        assertEquals(AssistantVoiceState.Idle, recorder.state.value)
        assertTrue("交付成功后文件由宿主持有，不应在这里删除", file!!.exists())
        delivered.forEach { it.close() }
        recorder.close()
    }

    private class FakeRecorderDevice(
        private val amplitude: Int = 0,
        private val onStart: (File) -> Unit = { it.writeBytes(byteArrayOf(1, 2, 3, 4)) },
        private val onStop: () -> Unit = {},
    ) : VoiceRecorderDevice {
        var stops = 0
        var releases = 0
        override fun start(file: File) = onStart(file)
        override fun stop() {
            stops++
            onStop()
        }

        override fun release() {
            releases++
        }

        override fun maxAmplitude(): Int = amplitude
    }

    private companion object {
        const val POLL_MS = 50L
    }
}
