package com.openandroidintelligence.mobile.settings.backup

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.SettingsPresentation
import com.openandroidintelligence.mobile.settings.backup.ImportConfirmationDialogForTest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.openandroidintelligence.ui.resources.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackupScreenTest {
    @get:Rule val compose = createComposeRule()

    private val locales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.US, Locale.GERMANY)

    private fun contextFor(locale: Locale): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun missingDelayKeepsTheExistingSummaryInEveryLocale() {
        locales.forEach { locale ->
            assertEquals("消息合并：保持本机现值", MessageProcessingSection(null).summary(contextFor(locale)))
        }
    }

    @Test
    fun zeroDelayKeepsTheImmediateSummaryInEveryLocale() {
        locales.forEach { locale ->
            assertEquals("消息合并：立即发送", MessageProcessingSection(0).summary(contextFor(locale)))
        }
    }

    @Test
    fun secondsKeepTheOriginalDecimalTextAndResourceFormattingInEveryLocale() {
        val cases = listOf(
            1 to "0.001",
            500 to "0.5",
            1000 to "1.0",
            1500 to "1.5",
            12345 to "12.345",
            30000 to "30.0",
        )
        locales.forEach { locale ->
            val context = contextFor(locale)
            cases.forEach { (delayMillis, seconds) ->
                val actual = MessageProcessingSection(delayMillis).summary(context)
                assertEquals("$locale / $delayMillis", "消息合并：$seconds 秒", actual)
                // 对照修复前由 %s 格式化 Double 的实际资源路径，防止改变小数及语言行为。
                val originalSeconds = String.format(
                    locale,
                    context.getString(UiR.string.settings_backup_msg_merge_seconds),
                    delayMillis / 1000.0,
                )
                assertEquals(context.getString(UiR.string.settings_backup_msg_merge_val, originalSeconds), actual)
            }
        }
    }

    private fun show(
        state: SettingsBackupUiState,
        onExport: () -> Unit = {},
        onImport: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                SettingsBackupContent(
                    state = state,
                    presentation = SettingsPresentation.SIDEBAR,
                    onBack = {},
                    onExport = onExport,
                    onImport = onImport,
                    onDismissNotice = onDismiss,
                )
            }
        }
    }

    @Test
    fun structuredFormatFailureUsesTheResourceMessageInTheVisibleNotice() {
        show(SettingsBackupUiState(
            isPreparing = false,
            notice = BackupNotice(
                id = 1,
                message = "兼容调用方保留的原始提示",
                isError = true,
                formatFailure = SettingsBackupFormatException(
                    "兼容异常消息保持原样",
                    BackupFormatErrorCode.SCHEMA_VERSION_NEWER,
                    listOf("99"),
                ),
                failureOperation = BackupFailureOperation.INSPECT,
            ),
        ))
        compose.onNodeWithText("无法导入：备份文件来自更新版本的 App（schemaVersion=99），请先升级 App。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("兼容调用方保留的原始提示").assertDoesNotExist()
    }

    @Test
    fun bothActionsAreReachableAndDescribeWhatIsAndIsNotRestored() {
        var exports = 0
        var imports = 0
        show(
            SettingsBackupUiState(
                isPreparing = false,
                exportSummary = listOf("外观：主题深色", "通知与提醒：Agent 任务通知开"),
            ),
            onExport = { exports++ },
            onImport = { imports++ },
        )
        compose.onNodeWithText("备份与恢复").assertIsDisplayed()
        compose.onNodeWithText("当前可导出内容").performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("2 类设置：外观：主题深色；通知与提醒：Agent 任务通知开").assertIsDisplayed()
        compose.onNodeWithText(
            "不会导入：账号密码、登录凭据、主密钥、会话历史、附件、" +
                "系统权限与设备能力的实际授权状态。这些仍需在手机上手确认。",
        ).performScrollTo().assertIsDisplayed()
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("导出设置").performClick()
        compose.onNodeWithText("导入设置").performClick()
        compose.runOnIdle {
            assertEquals(1, exports)
            assertEquals(1, imports)
        }
    }

    @Test
    fun workInProgressDisablesBothActionsInsteadOfSilentlyIgnoringTaps() {
        var exports = 0
        var imports = 0
        show(SettingsBackupUiState(busy = true, isPreparing = false), onExport = { exports++ }, onImport = { imports++ })
        // 禁用态由「没有点击动作 + 半透明」表达：断言的是点了确实不会有任何写入。
        compose.onNodeWithText("导出设置").assertHasNoClickAction()
        compose.onNodeWithText("导入设置").assertHasNoClickAction()
        compose.runOnIdle {
            assertEquals(0, exports)
            assertEquals(0, imports)
        }
    }

    @Test
    fun unavailableEnvironmentSaysWhyInsteadOfShowingDeadButtons() {
        show(SettingsBackupUiState(isPreparing = false, unavailableReason = "当前环境无法读取设置。"))
        compose.onNodeWithText("当前环境无法读取设置。").assertIsDisplayed()
        compose.onNodeWithText("导出设置").assertDoesNotExist()
        compose.onNodeWithText("导入设置").assertDoesNotExist()
    }

    @Test
    fun confirmationListsEverySectionAndKeepsTheSafetyWarningReachable() {
        // 十类摘要 + 安全说明在短视口下必须可滚动：最后的提示也要能滚到并读到。
        val summaries = (1..10).map { "第 $it 类设置：示例内容" }
        compose.setContent {
            MaterialTheme {
                ImportConfirmationDialogForTest(
                    pending = PendingImport(
                        text = "{}",
                        summaries = summaries,
                        ignoredFields = listOf("settings.appearance.futureField"),
                        exportedAt = "2026-11-08T10:00:00Z",
                        appVersionName = "0.1.0-dev.1",
                        localVersionName = "0.1.0-dev.2",
                    ),
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        summaries.forEach { compose.onNodeWithText("• $it").performScrollTo().assertExists() }
        compose.onNodeWithText(
            "以下内容与当前一致的项目不会被重复写入；" +
                "账号凭据、系统权限与设备能力总开关不会被导入修改。导入失败会自动恢复本机原设置。",
        ).performScrollTo().assertExists()
        compose.onNodeWithText("文件中有 1 个本版本不认识的字段，将被忽略。").performScrollTo().assertExists()
    }

    @Test
    fun failureNoticeIsShownInErrorToneAndDismissable() {
        var dismissed = 0
        show(
            SettingsBackupUiState(
                isPreparing = false,
                notice = BackupNotice(1, "导入失败：备份文件不是有效的 JSON。", isError = true),
            ),
            onDismiss = { dismissed++ },
        )
        compose.onNodeWithText("导入失败：备份文件不是有效的 JSON。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("知道了").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
}
