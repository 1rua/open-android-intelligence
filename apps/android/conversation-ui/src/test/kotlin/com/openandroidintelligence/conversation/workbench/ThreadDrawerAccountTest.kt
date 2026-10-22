package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.conversation.state.Loadable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadDrawerAccountTest {
    @get:Rule val compose = createComposeRule()

    @Test fun drawerUsesTheAccountNameAndRefreshesRealConnectionStatusAndAvatarAction() {
        val health = mutableStateOf(StreamHealth.LIVE)
        val online = mutableStateOf(true)
        val agent = mutableStateOf("Hermes")
        var picks = 0
        compose.setContent {
            MaterialTheme {
                ThreadDrawer("https://gateway.example", Loadable.Empty, null, {}, {}, {}, {}, {}, onLogout = {},
                    accountName = "alice", agentName = agent.value, streamHealth = health.value,
                    online = online.value, onChangeAvatar = { picks++ })
            }
        }
        compose.onNodeWithText("alice").assertIsDisplayed()
        compose.onNodeWithText("A").assertIsDisplayed()
        compose.onNodeWithText("用户").assertDoesNotExist()
        compose.onNodeWithContentDescription("Hermes 在线").assertIsDisplayed()
        compose.onNodeWithContentDescription("更换账号头像").performClick()
        compose.runOnIdle { assertEquals(1, picks); health.value = StreamHealth.RECONNECTING }
        compose.onNodeWithContentDescription("Hermes 重连中…").assertIsDisplayed()
        compose.runOnIdle { online.value = false }
        compose.onNodeWithContentDescription("Hermes 离线").assertIsDisplayed()
        compose.runOnIdle { online.value = true; health.value = StreamHealth.LIVE; agent.value = "DSH" }
        compose.onNodeWithContentDescription("DSH 在线").assertIsDisplayed()
    }

    @Test fun initialsRespectUnicodeAndUnknownConnectionsNeverClaimOnline() {
        assertEquals("张", accountInitial("张三"))
        assertEquals("😀", accountInitial("😀user"))
        assertEquals("A", accountInitial(" alice"))
        assertEquals("?", accountInitial(""))
        for (health in StreamHealth.entries.filter { it != StreamHealth.LIVE }) {
            assertFalse(accountConnectionLabel("DSH", true, health).endsWith("在线"))
        }
        assertEquals("DSH 在线", accountConnectionLabel("DSH", true, StreamHealth.LIVE))
        assertEquals("DSH 离线", accountConnectionLabel("DSH", false, StreamHealth.LIVE))
    }
}
