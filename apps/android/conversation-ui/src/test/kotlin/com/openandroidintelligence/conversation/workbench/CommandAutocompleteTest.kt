package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.state.Loadable
import org.junit.Assert.assertEquals
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

    private val remoteCommands = listOf(
        AgentCommand("/deploy", "Deploy to the selected environment", "[environment]"),
        AgentCommand("/status", "Report the Agent status"),
    )

    private fun ready(commands: List<AgentCommand> = remoteCommands) = Loadable.Ready(
        AgentCommandCatalog(CatalogVersion("remote-v1"), commands),
    )

    @Test
    fun prefixMatchingUsesOnlyRemoteCommandsAndIsCaseInsensitive() {
        assertEquals(listOf("/deploy", "/status"), filterCommands(remoteCommands, "/").map { it.command })
        assertEquals(listOf("/deploy"), filterCommands(remoteCommands, "/DEP").map { it.command })
        assertEquals(listOf("/status"), filterCommands(remoteCommands, "/status argument").map { it.command })
        assertTrue(filterCommands(remoteCommands, "deploy").isEmpty())
        assertTrue(filterCommands(remoteCommands, "/models").isEmpty())
    }

    @Test
    fun readyCatalogRendersRemoteCommandDetailsAndSelection() {
        var selectedCommand: String? = null
        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(
                    catalogState = ready(),
                    query = "/dep",
                    onSelect = { selectedCommand = it },
                )
            }
        }

        compose.onNodeWithText("/deploy").assertIsDisplayed()
        compose.onNodeWithText("[environment]").assertIsDisplayed()
        compose.onNodeWithText("Deploy to the selected environment").assertIsDisplayed()
        compose.onNodeWithText("/models").assertDoesNotExist()
        compose.onNodeWithText("/deploy").performClick()
        assertEquals("/deploy", selectedCommand)
    }

    @Test
    fun idleLoadingAndEmptyStatesDoNotInventCommands() {
        val catalogState = mutableStateOf<Loadable<AgentCommandCatalog>>(Loadable.Idle)
        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(catalogState = catalogState.value, query = "/", onSelect = {})
            }
        }
        listOf(
            Loadable.Idle to "尚未加载 Agent 命令目录",
            Loadable.Loading to "正在加载 Agent 命令目录…",
            Loadable.Empty to "Agent 尚未提供可用命令，回车可原样发送",
        ).forEach { (state, expectedText) ->
            compose.runOnIdle { catalogState.value = state }
            compose.onNodeWithText(expectedText).assertIsDisplayed()
            compose.onNodeWithText("/models").assertDoesNotExist()
        }
    }

    @Test
    fun failedCatalogShowsFailureAndRetryButtonCallsCallback() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(
                    catalogState = Loadable.Failed("OFFLINE_GATEWAY"),
                    query = "/unknown",
                    onSelect = {},
                    onRetry = { retries += 1 },
                )
            }
        }

        compose.onNodeWithText("无法加载 Agent 命令目录（OFFLINE_GATEWAY）").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed().performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("/models").assertDoesNotExist()
    }

    @Test
    fun emptyReadyCatalogAndUnmatchedCommandExplainThatRawInputCanBeSent() {
        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(catalogState = ready(emptyList()), query = "/custom", onSelect = {})
            }
        }

        compose.onNodeWithText("Agent 目录中没有匹配命令，回车可原样发送").assertIsDisplayed()
    }

    @Test
    fun commandMenuWiresItsRetryHandlerToPopup() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                CommandMenu(
                    catalogState = Loadable.Failed("OFFLINE_GATEWAY"),
                    query = "/",
                    onSelect = {},
                    onRetry = { retries += 1 },
                )
            }
        }

        compose.onNodeWithText("重试").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun nonSlashQueryDoesNotShowPopup() {
        compose.setContent {
            MaterialTheme {
                CommandAutocompletePopup(catalogState = ready(), query = "hello", onSelect = {})
            }
        }

        compose.onNodeWithText("/deploy").assertDoesNotExist()
        compose.onNodeWithText("尚未加载 Agent 命令目录").assertDoesNotExist()
    }
}
