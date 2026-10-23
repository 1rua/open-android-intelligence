package com.openandroidintelligence.mobile.updates

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppUpdateScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun previewExplainsBothDevelopmentAndNumberedTestsAndChannelCanBeChanged() {
        var selected: AppUpdateChannel? = null
        show(AppUpdateUiState(AppUpdateChannel.PREVIEW), onSelect = { selected = it })

        compose.onNodeWithText("开发预览测试版").assertExists()
        compose.onNodeWithText("跟踪开发预览测试版和带版本号的测试版（如 Beta、RC），也会提示后续可升级的正式版。")
            .assertExists()
        compose.onNodeWithText("正式版").performClick()
        assertEquals(AppUpdateChannel.STABLE, selected)
    }

    @Test
    fun developmentPreviewLabelIsUsedForInstalledAndAvailableDevVersions() {
        show(
            AppUpdateUiState(
                AppUpdateChannel.PREVIEW,
                result = AppUpdateResult.Available(
                    AppUpdateRelease(
                        versionName = "2.1.1-dev.42",
                        versionCode = 2042,
                        commit = "b".repeat(40),
                        channel = "preview",
                        releaseName = "开发预览运行 42",
                        summary = "",
                        releaseUrl = APP_RELEASES_URL,
                        apkUrl = "$APP_RELEASES_URL/download/v2.1.1-dev.42/app-full-debug.apk",
                        signingMatches = true,
                    ),
                ),
            ),
            installed = installedBuild().copy(versionName = "2.1.1-dev.41", channel = "preview"),
        )

        compose.onNodeWithText("开发预览测试版").assertExists()
        compose.onNodeWithText("开发预览测试版 · 开发预览运行 42").assertExists()
        compose.onNodeWithText("版本号测试版").assertDoesNotExist()
    }

    @Test
    fun betaReleaseKeepsTheNumberedTestLabel() {
        show(
            AppUpdateUiState(
                AppUpdateChannel.PREVIEW,
                result = AppUpdateResult.Available(
                    AppUpdateRelease(
                        versionName = "2.2.0-beta.1",
                        versionCode = 2201,
                        commit = "c".repeat(40),
                        channel = "preview",
                        releaseName = "2.2.0 首次测试",
                        summary = "",
                        releaseUrl = APP_RELEASES_URL,
                        apkUrl = "$APP_RELEASES_URL/download/v2.2.0-beta.1/app-full-release.apk",
                        signingMatches = true,
                    ),
                ),
            ),
        )

        compose.onNodeWithText("版本号测试版 · 2.2.0 首次测试").assertExists()
    }

    @Test
    fun checkingDisablesManualButtonAndDoesNotPretendTheVersionIsCurrent() {
        show(AppUpdateUiState(AppUpdateChannel.PREVIEW, isChecking = true))
        compose.onNodeWithText("正在检查更新…").assertIsNotEnabled()
        compose.onNodeWithText("暂无可升级版本").assertDoesNotExist()
    }

    @Test
    fun emptyStableChannelReportsNotPublishedAndLinksToRealReleasePage() {
        var opened: String? = null
        show(
            AppUpdateUiState(AppUpdateChannel.STABLE, result = AppUpdateResult.NotPublished),
            onOpen = { opened = it },
        )
        compose.onNodeWithText("尚未发布正式版").performScrollTo().assertExists()
        compose.onNodeWithText("暂无可升级版本").assertDoesNotExist()
        compose.onNodeWithText("查看所有发布").performScrollTo().performClick()
        assertEquals(APP_RELEASES_URL, opened)
    }

    @Test
    fun networkFailureIsVisibleAndRetryButtonCallsTheCheck() {
        var checks = 0
        show(
            AppUpdateUiState(AppUpdateChannel.PREVIEW, result = AppUpdateResult.Unavailable(AppUpdateFailure.NETWORK)),
            onCheck = { checks++ },
        )
        compose.onNodeWithText("检查更新失败").performScrollTo().assertExists()
        compose.onNodeWithText("网络连接失败，请检查网络后重试。").assertExists()
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        assertEquals(1, checks)
    }

    @Test
    fun missingMetadataIsNotShownAsUpToDate() {
        show(
            AppUpdateUiState(
                AppUpdateChannel.PREVIEW,
                result = AppUpdateResult.Unsupported(AppUpdateUnsupportedReason.METADATA_MISSING),
            ),
        )
        compose.onNodeWithText("发布版本尚未提供可验证的更新信息，请查看发布页。")
            .performScrollTo().assertExists()
        compose.onNodeWithText("暂无可升级版本").assertDoesNotExist()
    }

    @Test
    fun aNewBuildLinksToItsReleaseAndExplainsTheSigningDifference() {
        val url = "https://github.com/1rua/open-android-intelligence/releases/tag/v2.2.0-beta.1"
        var opened: String? = null
        show(
            AppUpdateUiState(
                AppUpdateChannel.PREVIEW,
                result = AppUpdateResult.Available(
                    AppUpdateRelease(
                        versionName = "2.2.0-beta.1",
                        versionCode = 2001,
                        commit = "b".repeat(40),
                        channel = "preview",
                        releaseName = "2.2.0 首次测试",
                        summary = "修复对话显示问题",
                        releaseUrl = url,
                        apkUrl = "https://github.com/1rua/open-android-intelligence/releases/download/v2.2.0-beta.1/app-full-release.apk",
                        signingMatches = false,
                    ),
                ),
            ),
            onOpen = { opened = it },
        )
        compose.onNodeWithText("发现新版本").performScrollTo().assertExists()
        compose.onNodeWithText("此版本的签名与当前安装不同或无法核对，不能确认可直接覆盖安装。请先查看发行说明中的迁移安排。")
            .performScrollTo().assertExists()
        compose.onNodeWithText("前往发布页").performScrollTo().performClick()
        assertEquals(url, opened)
    }

    private fun show(
        state: AppUpdateUiState,
        installed: InstalledAppBuild = installedBuild(),
        onSelect: (AppUpdateChannel) -> Unit = {},
        onCheck: () -> Unit = {},
        onOpen: (String) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                AppUpdateScreen(
                    installed = installed,
                    state = state,
                    onBack = {},
                    onSelectChannel = onSelect,
                    onCheck = onCheck,
                    onOpenRelease = onOpen,
                )
            }
        }
    }
}
