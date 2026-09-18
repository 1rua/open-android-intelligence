package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.state.Loadable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandAutocompleteTest {

    @get:Rule
    val compose = createComposeRule()

    // ==========================================
    // 1. Prefix Matching Tests
    // ==========================================

    @Test
    fun prefixMatchingExactAndPartial() {
        val slashAll = filterCommands(BuiltInCommands, "/")
        assertEquals(BuiltInCommands.size, slashAll.size)

        val modMatch = filterCommands(BuiltInCommands, "/mod")
        assertEquals(1, modMatch.size)
        assertEquals("/models", modMatch.first().command)

        val stMatch = filterCommands(BuiltInCommands, "/st")
        assertEquals(1, stMatch.size)
        assertEquals("/status", stMatch.first().command)
    }

    @Test
    fun prefixMatchingCaseInsensitive() {
        val upperMatch = filterCommands(BuiltInCommands, "/MODELS")
        assertEquals(1, upperMatch.size)
        assertEquals("/models", upperMatch.first().command)

        val mixedMatch = filterCommands(BuiltInCommands, "/StAtUs")
        assertEquals(1, mixedMatch.size)
        assertEquals("/status", mixedMatch.first().command)

        val helpUpper = filterCommands(BuiltInCommands, "/HELP")
        assertEquals(1, helpUpper.size)
        assertEquals("/help", helpUpper.first().command)
    }

    @Test
    fun prefixMatchingExtractsSubstringBeforeFirstSpace() {
        val withArgs = filterCommands(BuiltInCommands, "/models gemini-pro")
        assertEquals(1, withArgs.size)
        assertEquals("/models", withArgs.first().command)

        val reviewWithArgs = filterCommands(BuiltInCommands, "/review HEAD~1")
        assertEquals(1, reviewWithArgs.size)
        assertEquals("/review", reviewWithArgs.first().command)
    }

    @Test
    fun prefixMatchingReturnsEmptyWhenNoSlash() {
        assertTrue(filterCommands(BuiltInCommands, "").isEmpty())
        assertTrue(filterCommands(BuiltInCommands, "models").isEmpty())
        assertTrue(filterCommands(BuiltInCommands, "status").isEmpty())
    }

    @Test
    fun prefixMatchingInComposeUiFiltersCommands() {
        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(
                    catalogState = Loadable.Empty,
                    query = "/st",
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText("/status").assertIsDisplayed()
        compose.onNodeWithText("/models").assertDoesNotExist()
    }

    // ==========================================
    // 2. Argument Hints Tests
    // ==========================================

    @Test
    fun argumentHintsPresentForCommandsWithParameters() {
        val modelsCmd = BuiltInCommands.first { it.command == "/models" }
        assertEquals("[provider]", modelsCmd.argumentHint)

        val reviewCmd = BuiltInCommands.first { it.command == "/review" }
        assertEquals("[diff]", reviewCmd.argumentHint)

        val gatewayCmd = BuiltInCommands.first { it.command == "/gateway" }
        assertEquals("<url>", gatewayCmd.argumentHint)

        val helpCmd = BuiltInCommands.first { it.command == "/help" }
        assertEquals("[command]", helpCmd.argumentHint)
    }

    @Test
    fun argumentHintsAbsentForCommandsWithoutParameters() {
        val statusCmd = BuiltInCommands.first { it.command == "/status" }
        assertNull(statusCmd.argumentHint)

        val clearCmd = BuiltInCommands.first { it.command == "/clear" }
        assertNull(clearCmd.argumentHint)

        val newCmd = BuiltInCommands.first { it.command == "/new" }
        assertNull(newCmd.argumentHint)
    }

    @Test
    fun argumentHintsRenderInComposeUi() {
        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(
                    catalogState = Loadable.Empty,
                    query = "/models",
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText("/models").assertIsDisplayed()
        compose.onNodeWithText("[provider]").assertIsDisplayed()
        compose.onNodeWithText("切换或查看活动大模型与参数").assertIsDisplayed()
    }

    // ==========================================
    // 3. Catalog Merging Tests
    // ==========================================

    @Test
    fun catalogMergingUsesBuiltInsWhenCatalogIsEmptyOrOffline() {
        val fromEmpty = resolveCommands(Loadable.Empty)
        assertEquals(BuiltInCommands, fromEmpty)

        val fromIdle = resolveCommands(Loadable.Idle)
        assertEquals(BuiltInCommands, fromIdle)

        val fromLoading = resolveCommands(Loadable.Loading)
        assertEquals(BuiltInCommands, fromLoading)

        val fromFailed = resolveCommands(Loadable.Failed("OFFLINE_GATEWAY"))
        assertEquals(BuiltInCommands, fromFailed)

        val fromReadyEmpty = resolveCommands(
            Loadable.Ready(AgentCommandCatalog(CatalogVersion("1"), emptyList())),
        )
        assertEquals(BuiltInCommands, fromReadyEmpty)
    }

    @Test
    fun catalogMergingCombinesGatewayCommandsWithBuiltIns() {
        val gatewayCatalog = AgentCommandCatalog(
            version = CatalogVersion("v2"),
            commands = listOf(
                AgentCommand("/deploy", "部署当前代码至测试环境", "[env]"),
                AgentCommand("/rollback", "回滚指定版本", "<version>"),
            ),
        )

        val merged = resolveCommands(Loadable.Ready(gatewayCatalog))
        // Gateway commands appear first
        assertEquals("/deploy", merged[0].command)
        assertEquals("/rollback", merged[1].command)
        // All 7 built-ins are retained
        assertEquals(9, merged.size)
        assertTrue(merged.any { it.command == "/models" })
        assertTrue(merged.any { it.command == "/status" })
    }

    @Test
    fun catalogMergingGatewayOverridesBuiltInCommandWithoutDuplication() {
        val customStatus = AgentCommand("/status", "网关自定义集群健康检查状态")
        val gatewayCatalog = AgentCommandCatalog(
            version = CatalogVersion("v2"),
            commands = listOf(customStatus),
        )

        val merged = resolveCommands(Loadable.Ready(gatewayCatalog))
        // Overridden /status appears first with gateway description
        assertEquals(7, merged.size)
        assertEquals("/status", merged[0].command)
        assertEquals("网关自定义集群健康检查状态", merged[0].description)
        // Ensure no duplicated /status
        assertEquals(1, merged.count { it.command.equals("/status", ignoreCase = true) })
    }

    @Test
    fun catalogMergingNormalizesCommandsWithoutLeadingSlash() {
        val commandWithoutSlash = AgentCommand("ping", "测试连通性")
        val merged = mergeCommandCatalogs(listOf(commandWithoutSlash))

        assertTrue(merged.any { it.command == "/ping" })
    }

    // ==========================================
    // 4. Empty State Tests
    // ==========================================

    @Test
    fun emptyStateShowsFriendlyNoticeWhenNoCommandMatches() {
        val matched = filterCommands(BuiltInCommands, "/nonexistent_command")
        assertTrue(matched.isEmpty())

        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(
                    catalogState = Loadable.Empty,
                    query = "/nonexistent_command",
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText("未找到匹配命令，回车可原样发送").assertIsDisplayed()
    }

    // ==========================================
    // 5. Selection & Interaction Tests
    // ==========================================

    @Test
    fun clickingItemInvokesOnSelect() {
        var selectedCommand: String? = null

        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(
                    catalogState = Loadable.Empty,
                    query = "/st",
                    onSelect = { selectedCommand = it },
                )
            }
        }

        compose.onNodeWithText("/status").performClick()
        assertEquals("/status", selectedCommand)
    }

    @Test
    fun commandMenuBackwardCompatibleCallRendersProperly() {
        var selectedCommand: String? = null

        compose.setContent {
            MaterialTheme {
                CommandMenu(
                    catalogState = Loadable.Empty,
                    query = "/models",
                    onSelect = { selectedCommand = it },
                )
            }
        }

        compose.onNodeWithText("/models").assertIsDisplayed()
        compose.onNodeWithText("[provider]").assertIsDisplayed()
        compose.onNodeWithText("/models").performClick()
        assertEquals("/models", selectedCommand)
    }

    @Test
    fun selectingCommandDismissesPopupEvenThoughQueryStartsWithSlash() {
        var query = "/st"
        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(
                    catalogState = Loadable.Empty,
                    query = query,
                    onSelect = { query = "/status " },
                )
            }
        }

        compose.onNodeWithText("/status").assertIsDisplayed()
        compose.onNodeWithText("/status").performClick()
        // After selection, popup is dismissed/hidden
        compose.onNodeWithText("/status").assertDoesNotExist()
    }

    @Test
    fun nonSlashQueryDoesNotShowPopup() {
        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(
                    catalogState = Loadable.Empty,
                    query = "hello",
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText("/status").assertDoesNotExist()
        compose.onNodeWithText("/models").assertDoesNotExist()
        compose.onNodeWithText("未找到匹配命令，回车可原样发送").assertDoesNotExist()
    }
}
