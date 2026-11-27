package com.openandroidintelligence.mobile.onboarding

import android.app.Application
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.AppearancePreferences
import com.openandroidintelligence.mobile.AppearanceSettings
import com.openandroidintelligence.ui.resources.R as UiR
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowRoleManager
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingSystemAccessTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val roleManager: RoleManager get() = app.getSystemService(RoleManager::class.java)
    private val activity get() = compose.activity
    private val service get() = ComponentName(activity, "com.openandroidintelligence.screen.ScreenAccessibilityService")
    private val attempts = mutableListOf<String?>()
    private var previousEnabledServices: String? = null

    @Before
    fun setUp() {
        ShadowRoleManager.reset()
        shadowOf(roleManager).addAvailableRole(RoleManager.ROLE_ASSISTANT)
        ShadowToast.reset()
        previousEnabledServices = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        setEnabledServices("")
        // 缺失的系统页面必须真正抛出 ActivityNotFoundException，才能检查逐级回退。
        shadowOf(app).checkActivities(true)
    }

    @After
    fun tearDown() {
        shadowOf(app).checkActivities(false)
        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            previousEnabledServices,
        )
        ShadowRoleManager.reset()
        ShadowToast.reset()
    }

    @Test
    fun screenCardOpensAccessibilitySettingsWithTheOaiServiceLocation() {
        registerSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        showPermissions()

        cardButton("screen_capture", UiR.string.perm_action_settings).performScrollTo().performClick()

        assertEquals(listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS), attempts)
        val launched = shadowOf(activity).nextStartedActivity
        assertNotNull("点击屏幕采集卡后应打开系统无障碍设置", launched)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, launched.action)
        val serviceKey = service.flattenToString()
        assertEquals(serviceKey, launched.getStringExtra(":settings:fragment_args_key"))
        assertEquals(
            serviceKey,
            launched.getBundleExtra(":settings:show_fragment_args")
                ?.getString(":settings:fragment_args_key"),
        )
    }

    @Test
    fun screenCardRefreshesAfterGrantAndAfterRevocationInSettings() {
        registerSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        showPermissions()

        cardText("screen_capture", UiR.string.perm_status_screen_accessibility_off).assertExists()
        cardButton("screen_capture", UiR.string.perm_action_settings).performScrollTo().performClick()
        pauseActivity()
        // 模拟用户在系统设置打开本应用服务，其他服务的设置同时存在。
        val another = ComponentName("other.package", "other.package.OtherAccessibilityService")
        setEnabledServices("${another.flattenToString()}:${service.flattenToShortString()}")
        resumeActivity()

        cardText("screen_capture", UiR.string.perm_status_granted).performScrollTo().assertIsDisplayed()
        cardText("screen_capture", UiR.string.perm_action_enabled).assertExists()

        pauseActivity()
        setEnabledServices(another.flattenToString())
        resumeActivity()

        cardText("screen_capture", UiR.string.perm_status_screen_accessibility_off)
            .performScrollTo().assertIsDisplayed()
        cardButton("screen_capture", UiR.string.perm_action_settings).performClick()
        assertEquals(
            listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_ACCESSIBILITY_SETTINGS),
            attempts,
        )
    }

    @Test
    fun assistantOpensVoiceInputSettingsWithoutRequestingTheRoleOrShowingAnEarlyNotice() {
        registerSystemSettings(
            Settings.ACTION_VOICE_INPUT_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        showPermissions()

        cardButton("assistant_role", UiR.string.perm_action_settings).performScrollTo().performClick()

        assertEquals(listOf(Settings.ACTION_VOICE_INPUT_SETTINGS), attempts)
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("应启动系统数字助理设置", started)
        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, started.action)
        assertEquals("尚未从设置页返回时不应提示未设置", 0, ShadowToast.shownToastCount())
        cardText("assistant_role", UiR.string.perm_status_role_not_set).assertExists()
    }

    @Test
    fun assistantNotHeldAfterReturningShowsTheNoticeOncePerVisit() {
        registerSystemSettings(Settings.ACTION_VOICE_INPUT_SETTINGS)
        showPermissions()
        cardButton("assistant_role", UiR.string.perm_action_settings).performScrollTo().performClick()

        assertEquals(0, ShadowToast.shownToastCount())
        pauseActivity()
        resumeActivity()
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(
            activity.getString(UiR.string.perm_role_assistant_not_set_hint),
            ShadowToast.getTextOfLatestToast(),
        )
        cardText("assistant_role", UiR.string.perm_status_role_not_set).assertExists()

        pauseActivity()
        resumeActivity()
        assertEquals("没有再次访问系统设置时不得重复提示", 1, ShadowToast.shownToastCount())

        cardButton("assistant_role", UiR.string.perm_action_settings).performScrollTo().performClick()
        pauseActivity()
        resumeActivity()
        assertEquals("再次访问并返回后，可再次提示一次", 2, ShadowToast.shownToastCount())
    }

    @Test
    fun assistantHeldOnReturnShowsOriginalStatusWithoutNoticeAndRefreshesAfterRevocation() {
        registerSystemSettings(Settings.ACTION_VOICE_INPUT_SETTINGS)
        showPermissions()
        cardButton("assistant_role", UiR.string.perm_action_settings).performScrollTo().performClick()

        pauseActivity()
        shadowOf(roleManager).addHeldRole(RoleManager.ROLE_ASSISTANT)
        resumeActivity()

        assertEquals(0, ShadowToast.shownToastCount())
        assertEquals("已设为默认数字助理 · 可用 Assist 手势唤起", activity.getString(UiR.string.perm_status_role_granted))
        cardText("assistant_role", UiR.string.perm_status_role_granted).performScrollTo().assertIsDisplayed()
        cardText("assistant_role", UiR.string.perm_action_role_set).assertExists()

        pauseActivity()
        shadowOf(roleManager).removeHeldRole(RoleManager.ROLE_ASSISTANT)
        resumeActivity()
        cardText("assistant_role", UiR.string.perm_status_role_not_set).performScrollTo().assertIsDisplayed()
        cardButton("assistant_role", UiR.string.perm_action_settings).assertExists()
        assertEquals("并非从本次助理设置返回时，不应补发提示", 0, ShadowToast.shownToastCount())
    }

    @Test
    fun assistantRoleQueryFailureOnReturnKeepsSettingsActionWithoutNotSetNotice() {
        registerSystemSettings(Settings.ACTION_VOICE_INPUT_SETTINGS)
        var denyRoleQuery = false
        showPermissions(roleQueryUnavailable = { denyRoleQuery })
        cardButton("assistant_role", UiR.string.perm_action_settings).performScrollTo().performClick()

        pauseActivity()
        denyRoleQuery = true
        resumeActivity()

        cardText("assistant_role", UiR.string.common_status_unknown).assertExists()
        cardButton("assistant_role", UiR.string.perm_action_settings).assertExists()
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun assistantResumeWithoutAnInterveningPauseDoesNotPretendSettingsReturned() {
        registerSystemSettings(Settings.ACTION_VOICE_INPUT_SETTINGS)
        val firstOwner = ResumedOwner()
        val secondOwner = ResumedOwner()
        var owner by mutableStateOf<LifecycleOwner>(firstOwner)
        showPermissions(lifecycleOwner = { owner })
        cardButton("assistant_role", UiR.string.perm_action_settings).performScrollTo().performClick()

        // 替换为已恢复的 owner 会立即向新观察者补发 ON_RESUME，但没有经过 ON_PAUSE。
        compose.runOnIdle { owner = secondOwner }
        compose.waitForIdle()
        assertEquals("没有暂停就收到 ON_RESUME，不能当作用户从系统设置返回", 0, ShadowToast.shownToastCount())

        compose.runOnIdle { secondOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.runOnIdle { secondOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test
    fun screenSettingsFallsBackToGeneralSettingsWhenTheAccessibilityPageCannotStart() {
        registerSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS)
        showPermissions(rejectedActions = setOf(Settings.ACTION_ACCESSIBILITY_SETTINGS))

        cardButton("screen_capture", UiR.string.perm_action_settings).performScrollTo().performClick()

        assertEquals(
            listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS),
            attempts,
        )
        assertEquals(Settings.ACTION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun assistantAllSettingsFailWithoutShowingNotSetNoticeAfterResume() {
        val actions = listOf(
            Settings.ACTION_VOICE_INPUT_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        registerSystemSettings(*actions.toTypedArray())
        showPermissions(rejectedActions = actions.toSet())

        cardButton("assistant_role", UiR.string.perm_action_settings).performScrollTo().performClick()

        assertEquals(actions, attempts)
        assertEquals(1, ShadowToast.shownToastCount())
        val unavailable = activity.getString(UiR.string.perm_role_assistant_settings_unavailable)
        assertEquals(unavailable, ShadowToast.getTextOfLatestToast())

        pauseActivity()
        resumeActivity()
        assertEquals("未能打开设置页，恢复前台时不能产生未设置提示", 1, ShadowToast.shownToastCount())
        assertEquals(unavailable, ShadowToast.getTextOfLatestToast())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun optionalStatusAndSettingsButtonDoNotOverlapOnNarrowLargeTextLayout() {
        registerSystemSettings(Settings.ACTION_VOICE_INPUT_SETTINGS)
        showPermissions(narrowLargeText = true)

        val cards = mapOf(
            "screen_capture" to UiR.string.perm_status_screen_accessibility_off,
            "assistant_role" to UiR.string.perm_status_role_not_set,
        )
        cards.forEach { (card, statusRes) ->
            val button = cardButton(card, UiR.string.perm_action_settings)
                .performScrollTo().fetchSemanticsNode().boundsInRoot
            val statusNode = cardText(card, statusRes).assertIsDisplayed()
            val status = statusNode.fetchSemanticsNode().boundsInRoot
            val label = cardText(card, UiR.string.perm_action_settings).assertIsDisplayed()

            assertTrue("状态文字应占有可见空间", status.width > 0f && status.height > 0f)
            assertTrue("设置按钮应占有可见空间", button.width > 0f && button.height > 0f)
            assertTrue("大字体下状态文字不能与设置按钮重叠：状态=$status，按钮=$button", !overlap(status, button))
            assertFalse("状态文案应能换行完整显示", textLayout(statusNode).hasVisualOverflow)
            assertFalse("设置按钮应完整显示文案", textLayout(label).hasVisualOverflow)
        }
    }

    @Test
    fun skippingOptionalPermissionsStillContinuesOnboarding() {
        var next: OnboardingStage? = null
        showPermissions(onStageChange = { next = it })

        compose.onNodeWithTag("onboarding-permissions-skip").performClick()

        compose.runOnIdle { assertEquals(OnboardingStage.DEPLOYMENT, next) }
        assertTrue(attempts.isEmpty())
    }

    private fun showPermissions(
        narrowLargeText: Boolean = false,
        lifecycleOwner: (() -> LifecycleOwner)? = null,
        onStageChange: (OnboardingStage) -> Unit = {},
        rejectedActions: Set<String> = emptySet(),
        roleQueryUnavailable: () -> Boolean = { false },
    ) {
        val preferences = AppearancePreferences(activity)
        // 只在 Android 系统边界记录真实发起的操作，所有业务与界面仍使用生产代码。
        val recordingContext = object : ContextWrapper(activity) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.ROLE_SERVICE && roleQueryUnavailable()) return null
                return super.getSystemService(name)
            }

            override fun startActivity(intent: Intent) {
                attempts.add(intent.action)
                if (intent.action in rejectedActions) throw ActivityNotFoundException(intent.action)
                super.startActivity(intent)
            }
        }
        compose.setContent {
            val realOwner = LocalLifecycleOwner.current
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides recordingContext,
                LocalLifecycleOwner provides (lifecycleOwner?.invoke() ?: realOwner),
                LocalDensity provides if (narrowLargeText) Density(density.density, 1.5f) else density,
            ) {
                MaterialTheme {
                    Box(if (narrowLargeText) Modifier.width(320.dp).height(800.dp) else Modifier) {
                        OnboardingFlow(
                            state = OnboardingState(stage = OnboardingStage.PERMISSIONS),
                            appearanceSettings = AppearanceSettings(),
                            appearancePreferences = preferences,
                            onStageChange = onStageChange,
                            onGatewaySelected = {},
                            onFinished = {},
                            onExit = {},
                        )
                    }
                }
            }
        }
    }

    private fun cardButton(card: String, textRes: Int): SemanticsNodeInteraction = compose.onNode(
        hasClickAction() and
            hasAnyDescendant(hasText(activity.getString(textRes))) and
            hasAnyAncestor(hasTestTag("permission-card-$card")),
        useUnmergedTree = true,
    )

    private fun cardText(card: String, textRes: Int): SemanticsNodeInteraction = compose.onNode(
        hasText(activity.getString(textRes)) and hasAnyAncestor(hasTestTag("permission-card-$card")),
        useUnmergedTree = true,
    )

    private fun registerSystemSettings(vararg actions: String) {
        val packageName = "com.android.settings"
        actions.forEachIndexed { index, action ->
            val appInfo = ApplicationInfo().apply {
                this.packageName = packageName
                flags = ApplicationInfo.FLAG_SYSTEM
                enabled = true
            }
            val resolveInfo = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    this.packageName = packageName
                    name = "$packageName.TestSettings$index"
                    applicationInfo = appInfo
                    exported = true
                    enabled = true
                }
            }
            shadowOf(app.packageManager).addResolveInfoForIntent(Intent(action), resolveInfo)
        }
    }

    private fun setEnabledServices(value: String) {
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    }

    private fun pauseActivity() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    }

    private fun resumeActivity() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun overlap(first: Rect, second: Rect): Boolean =
        first.left < second.right && second.left < first.right &&
            first.top < second.bottom && second.top < first.bottom

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private class ResumedOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
