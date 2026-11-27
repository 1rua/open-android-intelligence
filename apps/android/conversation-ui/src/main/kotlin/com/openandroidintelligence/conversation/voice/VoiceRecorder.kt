package com.openandroidintelligence.conversation.voice

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 录音界面可观察到的三种状态。
 *
 * 只有 [Recording] 携带可渲染的数据；[Sending] 是「录音已结束、正在写文件并交给宿主」的
 * 过渡态。它的存在意义是让重复点击没有可乘之机，而不是给界面一个新的视觉形态。
 */
sealed interface VoiceRecorderState {

    /** 没有进行中的录音；麦克风已释放，临时文件已删除。 */
    data object Idle : VoiceRecorderState

    /**
     * 正在录音。
     *
     * @param sessionKey 录音开始时所在的会话身份；发送时用它判断用户是否已经切走。
     * @param elapsedMs 已经录制的真实时长（毫秒），由计时协程按真实时钟推进。
     * @param level 当前音量（0..1），来自 [VoiceLevelMeter]。
     */
    data class Recording(
        val sessionKey: String?,
        val elapsedMs: Long,
        val level: Float,
    ) : VoiceRecorderState

    /** 录音已结束，正在写文件并交给宿主发送；此状态下任何再次发送都会被拒绝。 */
    data class Sending(val sessionKey: String?) : VoiceRecorderState
}

/** 一段录音没有交付给宿主的原因；宿主据此给出提示，而不是静默吞掉。 */
enum class VoiceRejection {

    /** 当前没有可发送的录音（已经发过、已取消，或从未开始）。 */
    NOT_RECORDING,

    /** 上一段录音还在结束/发送中；重复点击被拒绝。 */
    ALREADY_SENDING,

    /** 录音所属的会话已经切换；这段音频被丢弃，绝不发到别的对话。 */
    SESSION_CHANGED,

    /** 录音器准备、录制或结束失败（权限、设备占用、录音过短等）。 */
    RECORDING_FAILED,
}

/**
 * 录音被拒绝时给用户的中文结论。
 *
 * 每个分支都是对真实原因的直接说明；不做自动重试，也不把拒绝渲染成"已发送"。
 */
fun voiceRejectionMessage(rejection: VoiceRejection): String = when (rejection) {
    VoiceRejection.NOT_RECORDING -> "当前没有可发送的录音"
    VoiceRejection.ALREADY_SENDING -> "语音正在发送，请稍候"
    VoiceRejection.SESSION_CHANGED -> "会话已切换，录音未发送"
    VoiceRejection.RECORDING_FAILED -> "录音失败，请重试"
}

/**
 * 界面可渲染的「正在录音」：经过的真实时长与实时音量（0..1）。
 *
 * 只承载渲染所需数据，不暴露会话身份：会话校验由 [VoiceRecorder] 自己保证。
 */
@Immutable
data class ActiveVoiceRecording(
    val elapsedMs: Long,
    val level: Float = 0f,
)

/**
 * 录音状态 → 输入栏的录音态。
 *
 * 只有 [VoiceRecorderState.Recording] 携带可渲染的真实时长与音量；[VoiceRecorderState.Sending]
 * 与 Idle 都回到输入态 —— 发送中的过渡态如果继续显示"正在录音"，用户会以为还能再录一次。
 */
fun VoiceRecorderState.activeRecording(): ActiveVoiceRecording? =
    (this as? VoiceRecorderState.Recording)?.let {
        ActiveVoiceRecording(elapsedMs = it.elapsedMs, level = it.level)
    }

/**
 * 语音录音的状态机与设备生命周期；主会话与默认数字助手共用同一份实现。
 *
 * 职责边界：
 * - 只管理「这一条录音」——开始、计时、电平、取消与结束；
 * - 录音结束后生成 [RecordedVoice] 并通过 [onRecorded] 交给宿主发送。本类不连接桥接、
 *   不自己发消息，也不持有会话的权威身份，只记住录音开始时所在的
 *   [VoiceRecorderState.Recording.sessionKey]；
 * - 取消、会话切换、录制失败都会立刻释放麦克风并删除临时文件，不在缓存目录留下可复用的音频。
 *
 * 三条必须守住的规则（由本类而不是界面保证）：
 * 1. **防重复提交**：发送时同步把状态推进到 [VoiceRecorderState.Sending] 并交出录音引用，
 *    第二次点击只能拿到 `false`，不会产生第二条语音消息；
 * 2. **切会话校验**：[send] 在结束录音前后各比对一次当前会话，任何一次不一致都会丢弃音频；
 *    宿主也可以主动调用 [onSessionChanged]；
 * 3. **失败必须清理**：设备报错（例如录音过短）时释放录音器并删除半成品，状态回到 Idle。
 *
 * 线程模型：[start]、[send]、[cancel]、[onSessionChanged]、[close] 都是线程安全的同步入口，
 * 可以在主线程直接调用；计时、电平采样与结束录音（阻塞写文件）在 [dispatcher] /
 * [finishDispatcher] 上执行。[onRecorded] 在 [finishDispatcher] 上回调，宿主应把真正的
 * 发送动作投递回自己的作用域；同步入口里的拒绝（未在录音、重复发送、切会话）在调用方
 * 线程直接回调 [onRejected]，录音收尾阶段的失败（录音过短、写文件异常）在
 * [finishDispatcher] 上回调。
 *
 * 生命周期：[start] 之后必须调用 [close]（幂等）；[close] 会取消采样、释放麦克风、删除
 * 尚未交付的临时文件，并停掉内部协程作用域。已经交给宿主的 [RecordedVoice] 由宿主负责关闭。
 */
class VoiceRecorder(
    private val createRecording: () -> VoiceRecording,
    private val onRecorded: (voice: RecordedVoice, sessionKey: String?) -> Unit,
    private val onRejected: (VoiceRejection) -> Unit = {},
    private val elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val pollIntervalMs: Long = VoiceLevelMeter.DEFAULT_POLL_INTERVAL_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val finishDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive" }
    }

    /** 保护下面全部可变字段：入口来自主线程，采样与交付在后台线程回调。 */
    private val lock = Any()

    private val _state = MutableStateFlow<VoiceRecorderState>(VoiceRecorderState.Idle)

    /** 当前录音状态；界面据此渲染「录音中：波形 + 计时」或回到输入态。 */
    val state: StateFlow<VoiceRecorderState> = _state.asStateFlow()

    /**
     * 本类自有的协程作用域：录音期间承载计时与电平采样，结束后承载写文件与交付。
     *
     * 不借用宿主的界面作用域，否则窗口一收起就会把正在写文件的录音一起取消，
     * 临时文件随之无人清理。作用域只在 [close] 停止；停止后再次 [start] 会重建。
     */
    private var scope: CoroutineScope = newScope()

    private var capture: VoiceRecording? = null
    private var startedAtMillis: Long = 0L
    private var meter: VoiceLevelMeter? = null
    private var tickerJob: Job? = null
    private var levelJob: Job? = null

    /** [close] 在交付中途被调用：交付收尾时再由它停掉作用域，不在写文件的中途打断。 */
    private var stopScopeWhenDelivered = false

    /**
     * 开始一段录音。
     *
     * @param sessionKey 当前会话身份；发送时用于判断用户是否已经切走。
     * @return 是否真的开始了录音。已经在录音或正在发送时返回 `false`，不产生任何副作用。
     */
    fun start(sessionKey: String? = null): Boolean {
        var failed = false
        val created = synchronized(lock) {
            if (_state.value != VoiceRecorderState.Idle) return false
            val recording = try {
                createRecording()
            } catch (_: Exception) {
                failed = true
                null
            } ?: return@synchronized null
            try {
                recording.start()
            } catch (_: Exception) {
                runCatching { recording.close() }
                failed = true
                return@synchronized null
            }
            // 设备已在锁内启动：录音计时从这里开始，界面只读这份状态，不自行计时。
            if (!scope.isActive) scope = newScope()
            capture = recording
            startedAtMillis = elapsedRealtimeMillis()
            meter = VoiceLevelMeter(recording, pollIntervalMs, dispatcher)
            _state.value = VoiceRecorderState.Recording(
                sessionKey = sessionKey,
                elapsedMs = 0L,
                level = 0f,
            )
            recording
        }
        if (created == null) {
            if (failed) onRejected(VoiceRejection.RECORDING_FAILED)
            return false
        }

        // 采样与计时在锁外启动：它们会立即回调状态更新，持锁启动会与回调重入。
        // 期间若已被取消或发送，就不再启动采样，并如实返回"没有在录音"。
        val levelMeter = synchronized(lock) {
            if (capture === created) meter else null
        } ?: return false
        val currentScope = synchronized(lock) { scope }
        levelMeter.start(currentScope)
        synchronized(lock) {
            // 启动采样期间可能已经取消或发送：只有仍持有同一个录音器时才保存作业句柄。
            // 否则采样协程会挂在已释放的录音器上持续轮询，这里补一次关闭。
            if (capture !== created || meter !== levelMeter) {
                levelMeter.close()
                return false
            }
            tickerJob = currentScope.launch(dispatcher) {
                while (isActive) {
                    delay(pollIntervalMs)
                    publishElapsed()
                }
            }
            levelJob = currentScope.launch(dispatcher) {
                levelMeter.level.collect { publishLevel(it) }
            }
        }
        return true
    }

    /**
     * 取消录音：停止采样、释放麦克风并删除临时音频。
     *
     * @return 是否真的取消了一段录音。没有进行中的录音（或正在发送）时返回 `false`。
     */
    fun cancel(): Boolean {
        val recording = synchronized(lock) {
            if (_state.value !is VoiceRecorderState.Recording) return false
            val current = capture ?: return false
            capture = null
            stopSamplingLocked()
            _state.value = VoiceRecorderState.Idle
            current
        }
        // close() 同时负责释放设备与删除文件；重复调用是幂等的。
        runCatching { recording.close() }
        return true
    }

    /**
     * 结束录音并交给宿主发送。
     *
     * 调用会立即把状态推进到 [VoiceRecorderState.Sending] 并交出录音引用，
     * 真正的 `finish()`（阻塞写文件）在 [finishDispatcher] 上执行。
     *
     * @param currentSessionKey 读取当前会话身份的入口；结束录音前后各读一次，
     *   任何一次与录音开始时的会话不一致都会丢弃这段音频。第一次在调用方线程读取，
     *   第二次在 [finishDispatcher] 上读取，因此它必须是线程安全且不阻塞的纯读取。
     * @return 是否接受了这次发送请求。没有录音、上一次仍在发送、或录音器已经释放时返回 `false`。
     */
    fun send(currentSessionKey: () -> String?): Boolean {
        var recording: VoiceRecording? = null
        var sessionKey: String? = null
        var deliveryScope: CoroutineScope? = null
        var rejection: VoiceRejection? = null
        // 先读一次当前会话，再进锁判断：宿主传入的读取器可能触碰自己的状态，
        // 不能在持锁时回调外部代码。读取失败时按「已经切走」处理，宁可丢弃也不发错对话。
        val currentSession = runCatching { currentSessionKey() }.getOrNull()

        synchronized(lock) {
            when (_state.value) {
                is VoiceRecorderState.Sending -> {
                    rejection = VoiceRejection.ALREADY_SENDING
                    return@synchronized
                }

                VoiceRecorderState.Idle -> {
                    rejection = VoiceRejection.NOT_RECORDING
                    return@synchronized
                }

                is VoiceRecorderState.Recording -> Unit
            }
            val current = capture
            if (current == null) {
                rejection = VoiceRejection.NOT_RECORDING
                return@synchronized
            }
            val startedIn = (_state.value as VoiceRecorderState.Recording).sessionKey
            if (currentSession != startedIn) {
                // 会话已经切换：这段录音不属于任何当前对话，直接丢弃。
                capture = null
                stopSamplingLocked()
                _state.value = VoiceRecorderState.Idle
                runCatching { current.close() }
                rejection = VoiceRejection.SESSION_CHANGED
                return@synchronized
            }
            // 同步消费录音引用：即使 finish() 还没完成就再次调用，也拿不到第二份音频。
            capture = null
            stopSamplingLocked()
            _state.value = VoiceRecorderState.Sending(startedIn)
            recording = current
            sessionKey = startedIn
            deliveryScope = scope
        }

        rejection?.let {
            onRejected(it)
            return false
        }
        val toDeliver = recording ?: return false
        val deliveredSession = sessionKey
        // 宿主可能恰好在这次调用之前关闭了录音器：此时作用域已停止，任务不会被调度，
        // 必须在这里自己清理，否则状态会永远停在 Sending、临时文件也无人删除。
        val scopeToUse = deliveryScope
        if (scopeToUse == null || !scopeToUse.isActive) {
            runCatching { toDeliver.close() }
            resetToIdle()
            onRejected(VoiceRejection.RECORDING_FAILED)
            return false
        }
        scopeToUse.launch(finishDispatcher) {
            deliver(toDeliver, deliveredSession, currentSessionKey)
        }
        return true
    }

    /**
     * 会话已经切换时主动丢弃录音。
     *
     * 宿主在观察到当前会话变化后调用；录音仍属于原会话时立即取消。
     *
     * @return 是否真的丢弃了一段录音。
     */
    fun onSessionChanged(currentSessionKey: String?): Boolean {
        val recording = synchronized(lock) {
            val active = _state.value as? VoiceRecorderState.Recording ?: return false
            if (active.sessionKey == currentSessionKey) return false
            val current = capture ?: return false
            capture = null
            stopSamplingLocked()
            _state.value = VoiceRecorderState.Idle
            current
        }
        runCatching { recording.close() }
        onRejected(VoiceRejection.SESSION_CHANGED)
        return true
    }

    /**
     * 界面销毁或会话结束：取消录音、停掉采样并释放麦克风；幂等。
     *
     * 正在交付（[VoiceRecorderState.Sending]）时不做中断：结束录音 + 写文件是一个短临界区，
     * 它自己会清理文件并回到 Idle；此时强行取消会让状态永远停在 Sending、临时文件无人删除。
     * 已经交给宿主的 [RecordedVoice] 由宿主负责关闭。
     */
    fun close() {
        val stopping = synchronized(lock) {
            if (_state.value is VoiceRecorderState.Sending) {
                // 交付正在进行：只登记"结束后停掉作用域"，不打断写文件；
                // 交接完成后 deliver() 会完成这一步。
                stopScopeWhenDelivered = true
                return
            }
            stopScopeWhenDelivered = false
            val current = capture
            capture = null
            stopSamplingLocked()
            _state.value = VoiceRecorderState.Idle
            val previousScope = scope
            scope = newScope()
            previousScope to current
        }
        runCatching { stopping.second?.close() }
        stopping.first.cancel()
    }

    private fun deliver(
        recording: VoiceRecording,
        sessionKey: String?,
        currentSessionKey: () -> String?,
    ) {
        // 交付成功前发生任何异常/取消都清理半成品：文件不会因为一次意外留在缓存目录。
        var delivered = false
        try {
            val finished = recording.finish()
            try {
                // 写文件期间用户仍可能切换会话：结束之后再确认一次，绝不把语音发到别的对话。
                val stillOn = runCatching { currentSessionKey() }.getOrNull()
                if (stillOn != sessionKey) {
                    finished.close()
                    onRejected(VoiceRejection.SESSION_CHANGED)
                    return
                }
                // 交给宿主后文件所有权随之转移：这里不再关闭，否则会删掉待发送的音频。
                onRecorded(finished, sessionKey)
                delivered = true
            } catch (cause: CancellationException) {
                finished.close()
                throw cause
            } catch (_: Exception) {
                finished.close()
                onRejected(VoiceRejection.RECORDING_FAILED)
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            // finish() 内部已释放录音器并在失败时删除文件；close() 幂等兜底。
            runCatching { recording.close() }
            onRejected(VoiceRejection.RECORDING_FAILED)
        } finally {
            if (!delivered) runCatching { recording.close() }
            // 此时状态仍是 Sending，start() 进不来；先停作用域再回到 Idle，
            // 就不会把"交付结束后立刻开始的新录音"一起取消掉。
            finishDeferredClose()
            resetToIdle()
        }
    }

    /**
     * [close] 在交付期间被调用时，等交付彻底收尾再停掉作用域。
     *
     * 交付本身是一个短临界区（结束录音 + 写文件），中途打断会把状态永远留在 Sending、
     * 临时文件也无人删除；因此这里只登记，等 [deliver] 收尾后统一停掉作用域。
     */
    private fun finishDeferredClose() {
        val stopping = synchronized(lock) {
            if (!stopScopeWhenDelivered) return
            stopScopeWhenDelivered = false
            val previousScope = scope
            scope = newScope()
            previousScope
        }
        stopping.cancel()
    }

    private fun resetToIdle() {
        synchronized(lock) {
            _state.value = VoiceRecorderState.Idle
        }
    }

    /** 停掉计时与电平采样；调用方必须持有 [lock]。 */
    private fun stopSamplingLocked() {
        tickerJob?.cancel()
        tickerJob = null
        levelJob?.cancel()
        levelJob = null
        meter?.close()
        meter = null
    }

    private fun publishElapsed() {
        synchronized(lock) {
            val active = _state.value as? VoiceRecorderState.Recording ?: return
            if (capture == null) return
            val elapsed = (elapsedRealtimeMillis() - startedAtMillis).coerceAtLeast(0L)
            if (elapsed == active.elapsedMs) return
            _state.value = active.copy(elapsedMs = elapsed)
        }
    }

    private fun publishLevel(level: Float) {
        synchronized(lock) {
            val active = _state.value as? VoiceRecorderState.Recording ?: return
            val clamped = level.coerceIn(0f, 1f)
            if (clamped == active.level) return
            _state.value = active.copy(level = clamped)
        }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    companion object {
        /**
         * 生产环境的录音器：使用 `MediaRecorder` 写入应用缓存目录。
         *
         * [context] 只用于定位缓存目录，不持有 Activity。
         */
        fun create(
            context: Context,
            onRecorded: (voice: RecordedVoice, sessionKey: String?) -> Unit,
            onRejected: (VoiceRejection) -> Unit = {},
        ): VoiceRecorder = VoiceRecorder(
            createRecording = { VoiceRecording.create(context.applicationContext) },
            onRecorded = onRecorded,
            onRejected = onRejected,
        )
    }
}
