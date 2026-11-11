package com.openandroidintelligence.mobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.LocalStreamingHapticFeedbackEnabled
import com.openandroidintelligence.conversation.workbench.MessageTimeline
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在真实系统帧时钟下运行共享时间线，不使用会接管帧时钟的 ComposeTestRule。
 * 本地分块只更新接收事实，不访问账号、网络、数据库或生产服务。
 */
@RunWith(AndroidJUnit4::class)
class StreamingReplyInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val main = Handler(Looper.getMainLooper())
    private var scenario: ActivityScenario<StreamingReplyTestActivity>? = null

    @Before
    fun requireIsolatedInstallation() {
        // Application 的进程前台通知会初始化连接运行时，因此必须使用独立安装身份。
        // 普通仪表测试批次明确跳过此验收，避免打开用户已有账号的应用进程。
        assumeTrue("流式验收要求独立 applicationId 后缀 .streamingacceptance",
            instrumentation.targetContext.packageName.endsWith(".streamingacceptance"))
    }

    @After
    fun releaseFixture() {
        main.removeCallbacksAndMessages(null)
        scenario?.close()
        scenario = null
        StreamingReplyTestActivity.installContent = null
    }

    @Test
    fun threeRoundsMeasureShortLongAndBurstStreamsWithRealWindowFrames() {
        val samples = listOf(
            StreamSample("150字", shortReply(), listOf(1, 11, 37, 101), listOf(0, 48, 96, 144)),
            StreamSample("长Markdown", longReply(), listOf(3, 450, 1200, 3000, Int.MAX_VALUE), listOf(0, 40, 88, 136, 184)),
            StreamSample("网络突发分块", shortReply(), listOf(1, 2, 3, 5, 8, 13, 21, Int.MAX_VALUE), listOf(0, 8, 16, 24, 180, 188, 196, 204)),
        )
        repeat(3) { round ->
            samples.forEach { sample ->
                val direct = measureSample(sample, round + 1, smooth = false)
                val smooth = measureSample(sample, round + 1, smooth = true)
                emitEvidence(mapOf(
                    "scenario" to sample.name,
                    "round" to round + 1,
                    "baseline" to "direct_render_control",
                    "comparisonScope" to "same_commit_direct_render",
                    "smoothTtftMinusDirectMs" to smooth.ttftMs - direct.ttftMs,
                    "smoothDrainMinusDirectMs" to smooth.drainMs - direct.drainMs,
                    "sourceParseCount" to "not_measured",
                ))
            }
        }
    }

    @Test
    fun aggregatedExecutionFinalAnswerUsesSharedStreamingBody() {
        val fixture = openFixture(execution = true)
        val text = "聚合最终回答首段。" + shortReply() + "聚合最终回答结束"
        update(fixture, text, streaming = true)
        awaitText("聚合最终回答结束")
        await("执行步骤没有聚合进共享容器") {
            nodes().any { it.description.contains("执行过程") && it.description.contains("2 步") }
        }
        SystemClock.sleep(220)
        assertCursorAtBodyEnd("聚合最终回答结束")
        assertEquals("最终回答动画不能改写事实正文", text, fixture.entry.text)
        update(fixture, text, streaming = false)
        await("聚合回答完成后不能留下光标") { !hasCursorStripe() }
    }

    @Test
    fun liveHapticPreferenceAndReducedMotionUseSimulatedHapticSink() {
        val fixture = openFixture(hapticProbe = true)
        val first = "触觉关闭期间新增正文。".repeat(12) + "第一阶段结束"
        update(fixture, first, streaming = true)
        awaitText("第一阶段结束")
        assertEquals("默认关闭时不应请求触觉", 0, fixture.hapticCalls.get())

        onMain { fixture.hapticEnabled = true }
        val enabled = first + "\n\n实时打开触觉后的新正文。".repeat(10) + "第二阶段结束"
        update(fixture, enabled, streaming = true)
        awaitText("第二阶段结束")
        await("开启偏好后新显示正文没有请求触觉") { fixture.hapticCalls.get() > 0 }

        onMain { fixture.hapticEnabled = false; fixture.reduceMotion = true }
        SystemClock.sleep(220)
        val disabledCount = fixture.hapticCalls.get()
        val reduced = enabled + "\n\n减少动态仍保留实时正文和静态排版光标。".repeat(10) + "第三阶段结束"
        update(fixture, reduced, streaming = true)
        awaitText("第三阶段结束")
        SystemClock.sleep(220)
        assertEquals("实时关闭偏好后不能继续请求触觉", disabledCount, fixture.hapticCalls.get())
        assertCursorAtBodyEnd("第三阶段结束")
        onMain { fixture.hapticEnabled = true }
        SystemClock.sleep(220)
        assertEquals("重新打开偏好不能补震旧文字", disabledCount, fixture.hapticCalls.get())
        emitEvidence(mapOf("scenario" to "实时偏好与减少动态", "hapticCalls" to disabledCount,
            "hapticSink" to "simulated_view_callback", "physicalHaptic" to "not_measured"))
        update(fixture, reduced, streaming = false)
        await("减少动态终态后不能保留光标") { !hasCursorStripe() }
    }

    @Test
    fun cursorFollowsActualWrappedTextAfterScrollingAndRotation() {
        val fixture = openFixture()
        val text = ("真实排版光标应贴在文字末尾，滚动时跟随正文，旋转后重新测量。".repeat(28)) + "排版验收终点"
        update(fixture, text, streaming = true)
        awaitText("排版验收终点")
        await("流式正文没有滚动到可见尾部") { fixture.scroll?.let { it.value == it.maxValue && it.maxValue > 0 } == true }
        SystemClock.sleep(220)
        assertCursorAtBodyEnd("排版验收终点")

        onMain { fixture.followTail = false }
        swipe(downward = true)
        await("真实手势没有向上浏览正文") { fixture.scroll?.let { it.value < it.maxValue - 100 } == true }
        assertFalse("正文被移出窗口时不能留下悬浮光标", hasCursorStripe())
        onMain { fixture.followTail = true }
        await("回到尾部后正文位置没有恢复") { fixture.scroll?.let { it.value == it.maxValue } == true }
        awaitText("排版验收终点")
        assertCursorAtBodyEnd("排版验收终点")

        requireNotNull(scenario).onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await("Activity 没有完成横屏重建") { fixture.activity?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE }
        awaitText("排版验收终点")
        assertFalse("旋转恢复已有正文不能重播旧光标", hasCursorStripe())
        update(fixture, "$text\n\n旋转后的实时追加终点", streaming = true)
        awaitText("旋转后的实时追加终点")
        SystemClock.sleep(220)
        assertCursorAtBodyEnd("旋转后的实时追加终点")
        update(fixture, fixture.entry.text, streaming = false)
        await("终态排空后光标没有消失") { !hasCursorStripe() }
    }

    @Test
    fun stoppedAndRecreatedHostRestoresFactsWithoutReplayingOldTyping() {
        val fixture = openFixture()
        update(fixture, shortReply(), streaming = true)
        awaitText("本地短回复结束")
        requireNotNull(scenario).moveToState(Lifecycle.State.CREATED)
        val hiddenText = longReply() + "\n\n后台接收快照终点"
        update(fixture, hiddenText, streaming = true)
        SystemClock.sleep(320)
        requireNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
        awaitText("后台接收快照终点")
        assertFalse("恢复页面不能补播后台旧内容的光标", hasCursorStripe())
        assertEquals("显示动画不能改写接收全文", hiddenText, fixture.entry.text)

        requireNotNull(scenario).recreate()
        awaitText("后台接收快照终点")
        assertFalse("Activity 重建不能重播历史流式内容", hasCursorStripe())
        update(fixture, "$hiddenText\n\n前台新增内容终点", streaming = true)
        awaitText("前台新增内容终点")
        SystemClock.sleep(220)
        assertCursorAtBodyEnd("前台新增内容终点")
        update(fixture, fixture.entry.text, streaming = false)
        await("结束后不能保留活动光标") { !hasCursorStripe() }
    }

    private fun measureSample(sample: StreamSample, round: Int, smooth: Boolean): ObservedTiming {
        val fixture = openFixture(smooth = smooth)
        // 首段被观察前固定在顶部，避免长文自动跟随尾部后把首段移出系统无障碍树。
        onMain { fixture.followTail = false }
        val marker = "首段对照〔${sample.name}·$round〕"
        val prefixMarker = "$marker\n\n"
        // 以等长前段替换保留 150 字样本的实际总字数，同时留下唯一的正文观察标记。
        val text = prefixMarker + sample.text.drop(prefixMarker.length)
        val memoryBefore = MemorySnapshot.capture()
        val frames = WindowFrames()
        requireNotNull(scenario).onActivity { frames.attach(it.window) }
        val started = AtomicLong()
        val terminal = AtomicLong()
        var end = 0
        sample.sizes.forEachIndexed { index, size ->
            end = (end.toLong() + size).coerceAtMost(text.length.toLong()).toInt()
            val prefix = text.substring(0, end)
            main.postDelayed({
                if (started.get() == 0L) started.set(SystemClock.elapsedRealtimeNanos())
                fixture.receive(prefix, streaming = true)
            }, sample.delays[index])
        }
        assertEquals("分块必须覆盖完整消息", text.length, end)
        main.postDelayed({
            terminal.set(SystemClock.elapsedRealtimeNanos())
            fixture.receive(text, streaming = false)
        }, sample.delays.last() + 24)

        // 前段标记只存在于当前夹具正文；匹配其真实已显示前缀，不会把元数据当作首字。
        await("当前正文首个可见字素没有出现") {
            nodes().any { it.text.isNotEmpty() && (marker.startsWith(it.text) || it.text.startsWith(marker)) }
        }
        val firstObserved = SystemClock.elapsedRealtimeNanos()
        onMain { fixture.followTail = true }
        awaitText(sample.endMarker)
        val completeObserved = SystemClock.elapsedRealtimeNanos()
        await("没有收到本地终态") { terminal.get() > 0L }
        requireNotNull(scenario).onActivity { frames.detach(it.window) }
        val memoryAfter = MemorySnapshot.capture()
        await("完成后仍有流式光标") { !hasCursorStripe() }
        assertEquals("动画不能丢失或改写接收事实", text, fixture.entry.text)
        assertFalse("终态必须提交给时间线", fixture.entry.isStreaming)

        val timing = ObservedTiming(millis(firstObserved - started.get()),
            millis((completeObserved - terminal.get()).coerceAtLeast(0)))
        val evidence = frames.describe() + memoryBefore.describe("before") + memoryAfter.describe("after") + mapOf(
            "scenario" to sample.name,
            "round" to round,
            "characters" to text.length,
            "payloadCharacters" to sample.text.length,
            "networkChunks" to sample.sizes.size,
            "renderMode" to if (smooth) "smooth" else "direct",
            "baseline" to "direct_render_control",
            "ttftObservedMs" to timing.ttftMs,
            "drainObservedMs" to timing.drainMs,
            "firstPrefixMarker" to marker,
            "javaHeapDeltaBytes" to memoryAfter.javaHeapBytes - memoryBefore.javaHeapBytes,
            "nativeHeapDeltaBytes" to memoryAfter.nativeHeapBytes - memoryBefore.nativeHeapBytes,
            "observation" to "accessibility_upper_bound",
            "frameClock" to "system",
            "scrollPolicy" to "hold_first_prefix_then_follow_tail",
            "device" to android.os.Build.MODEL,
            "sdk" to android.os.Build.VERSION.SDK_INT,
            "sourceParseCount" to "not_measured",
        )
        emitEvidence(evidence)
        requireNotNull(scenario).close()
        scenario = null
        return timing
    }

    private fun emitEvidence(evidence: Map<String, Any>) {
        val message = org.json.JSONObject(evidence).toString()
        Log.i("OaiStreamingAcceptance", message)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "流式验收 $message\n") })
    }

    private fun openFixture(smooth: Boolean = true, execution: Boolean = false, hapticProbe: Boolean = false): Fixture {
        scenario?.close()
        val fixture = Fixture(smooth, execution, hapticProbe)
        StreamingReplyTestActivity.installContent = { activity ->
            fixture.activity = activity
            activity.setContent {
                MaterialTheme(colorScheme = lightColorScheme(
                    primary = Color(CursorColor),
                    onSurface = Color.Black,
                    surface = Color.White,
                    surfaceContainerHigh = Color.White,
                )) {
                    val scroll = rememberScrollState()
                    val actualView = LocalView.current
                    val feedbackView = remember(actualView) { HapticProbeView(actualView, fixture.hapticCalls).apply {
                        setViewTreeLifecycleOwner(activity)
                    } }
                    fixture.scroll = scroll
                    LaunchedEffect(scroll.maxValue, fixture.followTail) {
                        if (fixture.followTail) scroll.scrollTo(scroll.maxValue)
                    }
                    Box(Modifier.fillMaxSize().background(Color.White).padding(16.dp)
                        .semantics { contentDescription = fixture.description }
                        .verticalScroll(scroll)) {
                        CompositionLocalProvider(
                            LocalView provides if (fixture.hapticProbe) feedbackView else actualView,
                            LocalStreamingHapticFeedbackEnabled provides fixture.hapticEnabled,
                            LocalMotionPolicy provides MotionPolicy(reduceMotion = fixture.reduceMotion),
                        ) {
                            MessageTimeline(fixture.prefixEntries + fixture.entry)
                        }
                    }
                }
            }
        }
        scenario = ActivityScenario.launch(StreamingReplyTestActivity::class.java)
        await("测试宿主没有进入真实窗口") { nodes().any { it.description == fixture.description } }
        return fixture
    }

    private fun update(fixture: Fixture, text: String, streaming: Boolean) {
        onMain { fixture.receive(text, streaming) }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun awaitText(text: String) = await("共享正文未显示：${text.takeLast(24)}") {
        nodes().any { it.text.contains(text) }
    }

    private fun await(message: String, timeout: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError(message)
            SystemClock.sleep(16)
        }
    }

    /** 观察实际系统无障碍树；这里只保留字符串和坐标，不持有系统节点。 */
    private fun nodes(): List<VisibleNode> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<VisibleNode>()
        fun visit(node: AccessibilityNodeInfo) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (node.isVisibleToUser) {
                result += VisibleNode(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), rect)
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
        }
        visit(root)
        return result
    }

    /** 独特测试主色仅用于识别真实绘制的光标；状态点高度不足以通过竖线筛选。 */
    private fun cursorStripe(bitmap: Bitmap): Rect? {
        var best: Rect? = null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (x in 0 until bitmap.width) {
            var top = -1
            var bottom = -1
            var count = 0
            for (y in 0 until bitmap.height) {
                val color = pixels[y * bitmap.width + x]
                if (abs(AndroidColor.red(color) - 16) <= 6 && abs(AndroidColor.green(color) - 208) <= 6 &&
                    abs(AndroidColor.blue(color) - 144) <= 6) {
                    if (top == -1) top = y
                    bottom = y
                    count++
                }
            }
            if (count >= 18 && bottom - top <= count + 4) {
                val old = best
                if (old == null) best = Rect(x, top, x + 1, bottom + 1)
                else if (x <= old.right + 1 && abs(old.top - top) <= 3) old.union(x, top, x + 1, bottom + 1)
            }
        }
        return best
    }

    private fun hasCursorStripe(): Boolean {
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return false
        return try { cursorStripe(screenshot) != null } finally { screenshot.recycle() }
    }

    private fun assertCursorAtBodyEnd(marker: String) {
        val body = nodes().lastOrNull { it.text.contains(marker) }
        assertNotNull("正文末段必须具有真实无障碍坐标", body)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val cursor = cursorStripe(screenshot)
            assertNotNull("实际绘制帧中没有检测到流式竖线光标", cursor)
            val bounds = requireNotNull(body).bounds
            val stripe = requireNotNull(cursor)
            assertTrue("光标必须位于正文横向范围内：$stripe / $bounds", stripe.left >= bounds.left - 8 && stripe.right <= bounds.right + 8)
            assertTrue("光标必须贴合最后一行而不是独立下一行：$stripe / $bounds", abs(stripe.bottom - bounds.bottom) <= stripe.height())
            assertTrue("光标不能拉成横贯气泡的拖尾", stripe.width() <= 10)
        } finally {
            screenshot.recycle()
        }
    }

    private fun swipe(downward: Boolean) {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val width = screenshot.width
        val height = screenshot.height
        screenshot.recycle()
        val x = width * 0.5f
        val start = height * if (downward) 0.3f else 0.75f
        val end = height * if (downward) 0.75f else 0.3f
        val down = SystemClock.uptimeMillis()
        repeat(21) { index ->
            val fraction = index / 20f
            val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, start + (end - start) * fraction, 0)
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
            SystemClock.sleep(16)
        }
    }

    private class Fixture(val smooth: Boolean, execution: Boolean, val hapticProbe: Boolean) {
        val description = "$FixtureDescription:${FixtureSequence.incrementAndGet()}"
        val hapticCalls = AtomicInteger()
        var hapticEnabled by mutableStateOf(false)
        var reduceMotion by mutableStateOf(false)
        private var revision = 0L
        val prefixEntries = if (!execution) emptyList() else listOf(
            TimelineEntry("fixture-user", "user", "执行本地验收", true, 1, false, null),
            TimelineEntry("fixture-thought", "assistant", "<think>本地执行过程夹具</think>", false, 2, false, null),
            TimelineEntry("fixture-tool", "assistant",
                "<tool_call name=\"local_fixture\"><command>fixture</command><result status=\"success\">本地结果</result></tool_call>",
                false, 3, false, null),
        )
        var entry by mutableStateOf(TimelineEntry(
            key = "instrumented-stream",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 1_000,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = true,
            presentation = if (smooth) StreamPresentationMetadata(StreamPresentationOrigin.LIVE, 0) else null,
        ))
        var followTail by mutableStateOf(true)
        @Volatile var scroll: ScrollState? = null
        @Volatile var activity: StreamingReplyTestActivity? = null

        fun receive(text: String, streaming: Boolean) {
            revision++
            entry = entry.copy(text = text, isStreaming = streaming,
                presentation = if (smooth) StreamPresentationMetadata(StreamPresentationOrigin.LIVE, revision) else null)
        }
    }

    /** 只记录共享实现发起的触觉请求，不调用真实硬件。窗口与生命周期仍来自真实宿主。 */
    private class HapticProbeView(private val actual: View, private val calls: AtomicInteger) : View(actual.context) {
        init { isHapticFeedbackEnabled = true }
        override fun isShown(): Boolean = actual.isShown
        override fun hasWindowFocus(): Boolean = actual.hasWindowFocus()
        override fun getWindowVisibility(): Int = actual.windowVisibility
        override fun getViewTreeObserver(): ViewTreeObserver = actual.viewTreeObserver
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            calls.incrementAndGet()
            return true
        }
    }

    private data class MemorySnapshot(val javaHeapBytes: Long, val nativeHeapBytes: Long, val totalPssKb: Int) {
        fun describe(prefix: String): Map<String, Any> = mapOf(
            "${prefix}JavaHeapBytes" to javaHeapBytes,
            "${prefix}NativeHeapBytes" to nativeHeapBytes,
            "${prefix}TotalPssKb" to totalPssKb,
        )
        companion object {
            fun capture(): MemorySnapshot {
                val runtime = Runtime.getRuntime()
                val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                return MemorySnapshot(runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize(), memory.totalPss)
            }
        }
    }

    private class WindowFrames {
        private val durations = Collections.synchronizedList(mutableListOf<Long>())
        private var droppedReports = 0
        private var refreshRate = 60f
        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            durations += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            droppedReports += dropped
        }

        fun attach(window: Window) {
            refreshRate = window.decorView.display?.refreshRate ?: 60f
            window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        }

        fun detach(window: Window) = window.removeOnFrameMetricsAvailableListener(listener)

        fun describe(): Map<String, Any> {
            val measured = synchronized(durations) { durations.sorted() }
            assertTrue("必须采集到真实窗口帧", measured.isNotEmpty())
            val budget = 1_000_000_000.0 / refreshRate
            fun percentile(p: Double) = millis(measured[(ceil(measured.size * p).toInt() - 1).coerceIn(measured.indices)])
            return mapOf(
                "frames" to measured.size,
                "frameP50Ms" to percentile(0.50),
                "frameP95Ms" to percentile(0.95),
                "frameP99Ms" to percentile(0.99),
                "overBudgetFrames" to measured.count { it > budget },
                "refreshRateHz" to refreshRate,
                "droppedMetricReports" to droppedReports,
            )
        }
    }

    private data class StreamSample(val name: String, val text: String, val sizes: List<Int>, val delays: List<Long>) {
        val endMarker: String get() = if (text.endsWith("长文结束标记")) "长文结束标记" else "本地短回复结束"
    }
    private data class ObservedTiming(val ttftMs: Double, val drainMs: Double)
    private data class VisibleNode(val text: String, val description: String, val bounds: Rect)

    companion object {
        private const val FixtureDescription = "StreamingReplyInstrumentedFixture"
        private val FixtureSequence = AtomicInteger()
        private const val CursorColor = 0xFF10D090
        private fun millis(nanos: Long): Double = (nanos / 10_000.0).roundToInt() / 100.0
        private fun shortReply(): String {
            val marker = "本地短回复结束"
            return ("这是模拟器本地流式验收，逐步显示中文、标点与真实换行排版。".repeat(8)).take(150 - marker.length) + marker
        }
        private fun longReply(): String = buildString {
            append("# 流式长文验收\n\n")
            repeat(18) { index ->
                append("第${index + 1}段：")
                append("逐字呈现必须保留接收事实，连续分块不能丢字，完整正文只用于最终排版。".repeat(8))
                append("\n\n")
            }
            append("```kotlin\nval answer = 42\n```\n\n")
            append("- 中文列表\n- **粗体内容**与`代码片段`\n\n长文结束标记")
        }
    }
}
