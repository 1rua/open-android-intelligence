package com.openandroidintelligence.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.workbench.ComposerAttachmentOverlay
import com.openandroidintelligence.conversation.workbench.ComposerAttachmentSheetTags
import com.openandroidintelligence.conversation.workbench.ComposerAttachmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 助手附件来源卡片：**在同一窗口内渲染**，不新建 Dialog。
 *
 * 为什么这条必须锁住：系统助理会话的宿主是 `VoiceInteractionSession`（窗口类型
 * 2031+），而 `ModalBottomSheet` 内部用 `Dialog` 建 `TYPE_APPLICATION` 窗口，系统会抛
 * `IllegalArgumentException: Window type mismatch` 直接崩溃（模拟器已复现）。
 * 卡片因此改用图层叠加，内容与主 App 共用同一份 [ComposerAttachmentOverlay]。
 *
 * 这些用例同时覆盖「三个来源都可达」与「选择后先关闭卡片」两条产品要求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AssistantAttachmentOverlayTest {
    @get:Rule val compose = createComposeRule()

    private fun render(visible: Boolean = true): Pair<MutableList<ComposerAttachmentSource>, () -> Int> {
        val picked = mutableListOf<ComposerAttachmentSource>()
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                // 固定尺寸宿主：浮层的定位与命中都相对于宿主窗口，无界测量会让卡片
                // 落在 0 高度区域里，点击根本不会落到条目上。
                Box(Modifier.size(360.dp, 640.dp)) {
                    ComposerAttachmentOverlay(
                        visible = visible,
                        onDismiss = { dismissals++ },
                        onPick = { picked += it },
                    )
                }
            }
        }
        return picked to { dismissals }
    }

    @Test
    fun theSheetIsAbsentUntilTheHostOpensIt() {
        render(visible = false)

        compose.onNodeWithTag(ComposerAttachmentSheetTags.SHEET).assertDoesNotExist()
    }

    /**
     * 三个入口与主 App 完全一致，并且都在同一窗口内可见。
     *
     * 用 `assertIsDisplayed` 而不是 `assertExists`：卡片若被压在窗口之外（尺寸/层级没
     * 算对），用户实际点不到，只看「存在」会漏掉这类问题。
     */
    @Test
    fun everyAttachmentSourceIsReachableInTheSheet() {
        render()

        compose.onNodeWithTag(ComposerAttachmentSheetTags.SHEET).assertIsDisplayed()
        compose.onNodeWithText("添加到当前对话").assertIsDisplayed()
        listOf(
            ComposerAttachmentSheetTags.CAMERA,
            ComposerAttachmentSheetTags.GALLERY,
            ComposerAttachmentSheetTags.DOCUMENT,
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    /** 选完必须先关卡片再启动系统页面：卡片留在最上层会盖住选择器。 */
    @Test
    fun pickingASourceClosesTheSheetAndReportsTheChoice() {
        val (picked, dismissals) = render()

        compose.onNodeWithTag(ComposerAttachmentSheetTags.DOCUMENT)
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(ComposerAttachmentSource.DOCUMENT), picked)
        assertEquals("选择之后必须同时请求关闭卡片", 1, dismissals())
    }

    @Test
    fun eachSourceMapsToItsOwnKind() {
        val (picked, _) = render()

        // 用无障碍点击（语义动作）而不是物理坐标点击：卡片有进场动画，Robolectric
        // 的物理命中与会动画帧耦合、连续三次点击时结果不稳定。语义动作走的正是
        // TalkBack 使用的同一条路径，断言的是「这个条目真的可点」。
        listOf(
            ComposerAttachmentSheetTags.CAMERA,
            ComposerAttachmentSheetTags.GALLERY,
            ComposerAttachmentSheetTags.DOCUMENT,
        ).forEach { tag ->
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }

        assertEquals(
            listOf(
                ComposerAttachmentSource.CAMERA,
                ComposerAttachmentSource.GALLERY,
                ComposerAttachmentSource.DOCUMENT,
            ),
            picked,
        )
    }

    /**
     * 卡片必须是会话窗口的一个图层，而不是独立 Dialog 窗口。
     *
     * `ModalBottomSheet` 在 Robolectric 下会创建自己的 Dialog 宿主；本组件走 overlay，
     * 因此 Compose 树里应当**没有** dialog 语义节点。这条断言把「不要再换回
     * ModalBottomSheet」钉在测试里 —— 换回去会在 VoiceInteractionSession 里直接崩溃。
     */
    @Test
    fun theSheetDoesNotCreateADialogWindow() {
        render(visible = true)

        compose.onNode(androidx.compose.ui.test.isDialog()).assertDoesNotExist()
    }
}
