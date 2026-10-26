package com.openandroidintelligence.screen

import android.accessibilityservice.AccessibilityService
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openandroidintelligence.control.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAccessibilityWindowInfo
import org.robolectric.shadows.ShadowPackageManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidAccessibilityRuntimeTest {
    private lateinit var service: ScreenAccessibilityService
    private lateinit var shadowService: ShadowAccessibilityService
    private lateinit var runtime: AndroidAccessibilityRuntime

    @Before fun setup() {
        service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        shadowService = Shadow.extract(service)
        shadowService.setCanDispatchGestures(true)
        ScreenAccessibilityConnection.connect(service)
        runtime = AndroidAccessibilityRuntime(RuntimeEnvironment.getApplication())
    }

    @After fun disconnect() { ScreenAccessibilityConnection.disconnect(service) }

    @Suppress("DEPRECATION")
    @Test fun realAndroidNodeActionsReceiveCorrectActionIdsAndTextBundle() {
        val actions = mutableListOf<Pair<Int, String?>>()
        val root = AccessibilityNodeInfo.obtain().apply {
            text = "Edit"
            isVisibleToUser = true
            isEnabled = true
            isEditable = true
            isClickable = true
            isScrollable = true
            setBoundsInScreen(Rect(0, 0, 100, 100))
        }
        val rootShadow = Shadow.extract<ShadowAccessibilityNodeInfo>(root)
        rootShadow.setRefreshReturnValue(true)
        rootShadow.setOnPerformActionListener { action, arguments ->
            actions += action to arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString()
            true
        }
        fun installWindow() {
            val window = AccessibilityWindowInfo.obtain()
            Shadow.extract<ShadowAccessibilityWindowInfo>(window).apply {
                setId(9); setType(1); setActive(true); setBoundsInScreen(Rect(0, 0, 1080, 1920)); setRoot(root)
            }
            // Android returns newly owned windows on every getWindows call. The shadow
            // stores the supplied list, so refresh the fixture after production recycles it.
            shadowService.setWindows(listOf(window))
        }
        installWindow()
        val windows = runtime.windows()
        val snapshot = try {
            ScreenTreeReader().capture(windows, ScreenDisplay(1080, 1920, 0), runtime.generation(), "org.example", 9)
        } finally { windows.forEach { it.root.close() } }
        val expected = snapshot.nodes.single()
        installWindow()
        assertTrue(runtime.nodeAction(expected, NativeNodeAction.CLICK, null, runtime.generation()))
        installWindow()
        assertTrue(runtime.nodeAction(expected, NativeNodeAction.SET_TEXT, "输入值", runtime.generation()))
        installWindow()
        assertTrue(runtime.nodeAction(expected, NativeNodeAction.SCROLL_DOWN, null, runtime.generation()))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK to null,
            AccessibilityNodeInfo.ACTION_SET_TEXT to "输入值",
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id to null), actions)
        root.recycle()
    }

    @Test fun backAndHomeUsePlatformGlobalActionConstants() {
        assertTrue(runtime.global("back", runtime.generation()))
        assertTrue(runtime.global("home", runtime.generation()))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK, AccessibilityService.GLOBAL_ACTION_HOME), shadowService.globalActionsPerformed)
    }

    @Suppress("DEPRECATION")
    @Config(shadows = [RefreshRevokingNodeShadow::class])
    @Test fun authorizationRevokedDuringFreshNodeResolutionPreventsPerformAction() {
        val authorization = ScreenLocalAuthorization()
        val binding = ScreenAuthorizationBinding("account", "pairing", ControlRevision(1uL, 1uL, 1uL, 1uL))
        authorization.start(binding, setOf(ScreenPermission.CONTROL), ScreenTargetIdentity("org.example", "9", 1uL), 10_000)
        val root = AccessibilityNodeInfo.obtain().apply {
            text = "Button"; isVisibleToUser = true; isEnabled = true; isClickable = true
            setBoundsInScreen(Rect(0, 0, 100, 100))
        }
        val actions = mutableListOf<Int>()
        Shadow.extract<ShadowAccessibilityNodeInfo>(root).apply {
            setRefreshReturnValue(true)
            setOnPerformActionListener { action, _ -> actions += action; true }
        }
        val expected = ScreenTreeReader().describe(AndroidTreeNode(root), "w9:0", 9, null, 0)
        val window = AccessibilityWindowInfo.obtain()
        Shadow.extract<ShadowAccessibilityWindowInfo>(window).apply {
            setId(9); setType(1); setActive(true); setRoot(root)
            setBoundsInScreen(Rect(0, 0, 1080, 1920))
        }
        shadowService.setWindows(listOf(window))
        var resolved = false
        RefreshRevokingNodeShadow.afterRefresh = { resolved = true; authorization.revoke() }
        try {
            val failure = runCatching {
                runtime.nodeAction(expected, NativeNodeAction.CLICK, null, runtime.generation()) {
                    assertTrue(resolved)
                    authorization.requirePermission(binding, ScreenPermission.CONTROL)
                }
            }.exceptionOrNull()
            assertTrue(resolved)
            assertTrue(failure is ScreenAuthorizationException)
            assertEquals("SCREEN_SESSION_REQUIRED", (failure as ScreenAuthorizationException).code)
            assertTrue(actions.isEmpty())
        } finally { RefreshRevokingNodeShadow.afterRefresh = null; authorization.close(); root.recycle() }
    }

    @Test fun launchLookupRevocationPreventsStartActivity() {
        val app = RuntimeEnvironment.getApplication()
        val packageName = "org.example.launch"
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val resolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName; name = "$packageName.MainActivity"; enabled = true; exported = true
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName; enabled = true }
            }
        }
        val packageManager = app.packageManager
        Shadow.extract<ShadowPackageManager>(packageManager).addResolveInfoForIntent(intent, resolve)
        assertNotNull(packageManager.getLaunchIntentForPackage(packageName))
        val authorization = ScreenLocalAuthorization()
        val binding = ScreenAuthorizationBinding("account", "pairing", ControlRevision(1uL, 1uL, 1uL, 1uL))
        authorization.start(binding, setOf(ScreenPermission.CONTROL), ScreenTargetIdentity("org.example", "9", 1uL), 10_000)
        var lookupStarted = false
        val revokingContext = object : ContextWrapper(app) {
            override fun getPackageManager(): PackageManager {
                lookupStarted = true
                authorization.revoke()
                return packageManager
            }
        }
        try {
            val launching = AndroidAccessibilityRuntime(revokingContext)
            val failure = runCatching {
                launching.launch(packageName, launching.generation()) {
                    assertTrue(lookupStarted)
                    authorization.requirePermission(binding, ScreenPermission.CONTROL)
                }
            }.exceptionOrNull()
            assertTrue(lookupStarted)
            assertTrue(failure is ScreenAuthorizationException)
            assertEquals("SCREEN_SESSION_REQUIRED", (failure as ScreenAuthorizationException).code)
            assertNull(shadowService.nextStartedActivity)
        } finally { authorization.close() }
    }

    @Test fun globalActionRechecksWindowGenerationAfterAuthorizationCallback() {
        val failure = runCatching {
            runtime.global("back", runtime.generation()) { ScreenAccessibilityConnection.changed() }
        }.exceptionOrNull()
        assertTrue(failure is ScreenOperationException)
        assertEquals("SCREEN_STALE_REFERENCE", (failure as ScreenOperationException).code)
        assertTrue(shadowService.globalActionsPerformed.isEmpty())
    }

    @Test fun swipeWaitsForSystemCallbackAndDragUsesTheSameContinuedPointer() = runTest {
        var checks = 0
        val swipe = async { runtime.gesture(listOf(10 to 20, 50 to 80), 400, runtime.generation(), { checks++ }) }
        runCurrent()
        assertFalse(swipe.isCompleted)
        val first = shadowService.gesturesDispatched.single()
        assertEquals(400, first.description().getStroke(0).duration)
        first.callback().onCompleted(first.description())
        runCurrent()
        assertTrue(swipe.await())
        val drag = async { runtime.gesture(listOf(20 to 30, 60 to 70), 800, runtime.generation(), { checks++ }, drag = true) }
        runCurrent()
        val hold = shadowService.gesturesDispatched[1]
        assertTrue(hold.description().getStroke(0).willContinue())
        hold.callback().onCompleted(hold.description())
        runCurrent()
        val move = shadowService.gesturesDispatched[2]
        assertFalse(move.description().getStroke(0).willContinue())
        move.callback().onCompleted(move.description())
        runCurrent()
        assertTrue(drag.await())
        assertEquals(3, checks)
    }

    @Test fun missingGestureCallbackProducesUnknownOutcomeWithoutRetry() = runTest {
        val request = async { runCatching { runtime.gesture(listOf(10 to 20, 50 to 80), 100, runtime.generation(), {}) } }
        runCurrent()
        advanceTimeBy(1601)
        runCurrent()
        assertEquals("SCREEN_ACTION_OUTCOME_UNKNOWN", (request.await().exceptionOrNull() as ScreenOperationException).code)
        assertEquals(1, shadowService.gesturesDispatched.size)
    }

    @Test fun revocationAfterDragHoldPreservesUnknownOutcomeAndDoesNotMove() = runTest {
        var checks = 0
        val request = async { runCatching {
            runtime.gesture(listOf(10 to 20, 50 to 80), 800, runtime.generation(), {
                if (++checks > 1) throw ScreenAuthorizationException("SCREEN_SESSION_EXPIRED")
            }, drag = true)
        } }
        runCurrent()
        val hold = shadowService.gesturesDispatched.single()
        hold.callback().onCompleted(hold.description())
        runCurrent()
        assertEquals("SCREEN_ACTION_OUTCOME_UNKNOWN", (request.await().exceptionOrNull() as ScreenOperationException).code)
        assertEquals(1, shadowService.gesturesDispatched.size)
    }

    @Test fun disconnectRevokesAvailabilityAndNotifiesLocalAuthority() {
        var revocations = 0
        val subscription = ScreenAccessibilityConnection.addDisconnectListener { revocations++ }
        try {
            ScreenAccessibilityConnection.disconnect(service)
            assertEquals("ACCESSIBILITY_SERVICE_UNAVAILABLE", runtime.availability())
            assertEquals(1, revocations)
        } finally { subscription.close() }
    }

    @Suppress("DEPRECATION")
    @Test fun interruptionRevokesLocalLeaseButKeepsSystemBoundServiceForNewConsent() {
        val authorization = ScreenLocalAuthorization()
        val binding = ScreenAuthorizationBinding("account", "pairing", ControlRevision(1uL, 1uL, 1uL, 1uL))
        val subscription = ScreenAccessibilityConnection.addDisconnectListener { authorization.revoke() }
        try {
            val previous = authorization.start(binding, setOf(ScreenPermission.READ), ttlMillis = 10_000)
            val generation = runtime.generation()
            service.onInterrupt()
            assertTrue(runtime.generation() > generation)
            assertEquals("SCREEN_SESSION_REQUIRED", authorization.status(binding).denialCode)
            assertSame(service, ScreenAccessibilityConnection.service)
            assertNull(runtime.availability())

            // No second onServiceConnected callback or Android permission toggle:
            // only new explicit local consent can establish the next screen lease.
            val renewed = authorization.start(binding, setOf(ScreenPermission.READ), ttlMillis = 10_000)
            assertNotEquals(previous.sessionId, renewed.sessionId)
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            try { service.onAccessibilityEvent(event) } finally { event.recycle() }
            assertEquals(renewed.sessionId, authorization.requirePermission(binding, ScreenPermission.READ).sessionId)
            assertNull(runtime.availability())
        } finally { subscription.close(); authorization.close() }
    }
}

/** Real runtime regression: revoke between the fresh node's refresh and native action. */
@Implements(AccessibilityNodeInfo::class)
class RefreshRevokingNodeShadow : ShadowAccessibilityNodeInfo() {
    companion object { var afterRefresh: (() -> Unit)? = null }
    @Implementation
    protected override fun refresh(): Boolean = super.refresh().also { afterRefresh?.invoke() }
}
