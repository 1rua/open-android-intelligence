package com.openandroidintelligence.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级本地缩略图存储的回归。
 *
 * 这些规则决定「屏幕内容会不会在助理进程里越积越多」以及「取不到预览时界面会不会显示假图」：
 * 上限、淘汰、关闭后拒绝写入都必须在这一层钉死，界面测试无法观察到内存占用。
 */
class AssistantLocalPreviewsTest {

    @Test
    fun aStoredThumbnailIsReturnedByItsLocalKey() {
        val store = AssistantLocalPreviews()
        assertTrue(store.put("draft_1", byteArrayOf(1, 2, 3)))

        assertArrayEquals(byteArrayOf(1, 2, 3), store.thumbnail("draft_1"))
        assertNull(store.thumbnail("draft_missing"))
        assertNull(store.thumbnail(""))
        assertEquals(1, store.size)
    }

    @Test
    fun theStoreCopiesBytesSoTheCallerCanWipeItsOwnCopy() {
        val store = AssistantLocalPreviews()
        val bytes = byteArrayOf(9, 8, 7)
        store.put("draft_1", bytes)

        bytes.fill(0)

        assertArrayEquals("写入后调用方清零自己的副本，不应影响存储", byteArrayOf(9, 8, 7), store.thumbnail("draft_1"))
    }

    @Test
    fun blankKeysAndEmptyPayloadsAreRejectedInsteadOfStored() {
        val store = AssistantLocalPreviews()

        assertFalse(store.put("", byteArrayOf(1)))
        assertFalse(store.put("   ", byteArrayOf(1)))
        assertFalse(store.put("draft_1", null))
        assertFalse(store.put("draft_1", ByteArray(0)))
        assertEquals(0, store.size)
        assertEquals(0, store.bytes)
    }

    @Test
    fun entriesBeyondTheCapEvictTheLeastRecentlyUsedThumbnail() {
        val store = AssistantLocalPreviews()
        repeat(AssistantLocalPreviews.MAX_ENTRIES) { index ->
            store.put("draft_$index", byteArrayOf(index.toByte()))
        }
        // 读取第 0 张，让它变成最近使用；随后再写入一张，被淘汰的应是第 1 张。
        assertNotNull(store.thumbnail("draft_0"))
        store.put("draft_new", byteArrayOf(42))

        assertNotNull(store.thumbnail("draft_0"))
        assertNull("最久未用的缩略图应被淘汰", store.thumbnail("draft_1"))
        assertEquals(AssistantLocalPreviews.MAX_ENTRIES, store.size)
    }

    @Test
    fun totalBytesStayWithinBudget() {
        val store = AssistantLocalPreviews()
        val chunk = ByteArray(AssistantLocalPreviews.MAX_TOTAL_BYTES / 2)
        assertTrue(store.put("a", chunk))
        assertTrue(store.put("b", chunk))
        assertTrue("第三张应触发淘汰，而不是超出预算", store.put("c", chunk))

        assertTrue("占用必须在预算内，实际 ${store.bytes}", store.bytes <= AssistantLocalPreviews.MAX_TOTAL_BYTES)
        assertNull(store.thumbnail("a"))
        assertNotNull(store.thumbnail("c"))
    }

    @Test
    fun aThumbnailLargerThanTheWholeBudgetIsRejected() {
        val store = AssistantLocalPreviews()

        assertFalse(store.put("huge", ByteArray(AssistantLocalPreviews.MAX_TOTAL_BYTES + 1)))
        assertEquals(0, store.size)
    }

    @Test
    fun removingOneThumbnailReleasesItsBytes() {
        val store = AssistantLocalPreviews()
        store.put("a", ByteArray(10))
        store.put("b", ByteArray(4))

        store.remove("a")

        assertEquals(4, store.bytes)
        assertNull(store.thumbnail("a"))
        assertNotNull(store.thumbnail("b"))
    }

    @Test
    fun closeClearsEverythingAndRejectsLateWrites() {
        val store = AssistantLocalPreviews()
        store.put("draft_1", byteArrayOf(1, 2, 3))
        store.putVoiceDuration("draft_voice", 3_250L)

        store.close()

        assertEquals(0, store.size)
        assertEquals(0, store.bytes)
        assertNull(store.thumbnail("draft_1"))
        assertNull(store.voiceDuration("draft_voice"))
        assertFalse("会话结束后迟到的截图不能写回", store.put("draft_late", byteArrayOf(4)))
    }

    @Test
    fun voiceDurationsOnlyAcceptRealPositiveValues() {
        val store = AssistantLocalPreviews()

        store.putVoiceDuration("voice_a", 3_250L)
        store.putVoiceDuration("voice_b", 0L)
        store.putVoiceDuration("voice_c", -5L)
        store.putVoiceDuration("", 1_000L)

        assertEquals(3_250L, store.voiceDuration("voice_a"))
        assertNull("0 表示不知道，不是 0 秒", store.voiceDuration("voice_b"))
        assertNull(store.voiceDuration("voice_c"))
        assertNull(store.voiceDuration(""))
    }

    @Test
    fun writesAdvanceTheRevisionSoComposeCanNoticeThem() {
        val store = AssistantLocalPreviews()
        val initial = store.revision

        store.put("draft_1", byteArrayOf(1))
        val afterThumbnail = store.revision
        store.putVoiceDuration("draft_voice", 1_500L)
        val afterDuration = store.revision

        assertTrue("缩略图写入应推进修订号", afterThumbnail > initial)
        assertTrue("时长写入应推进修订号", afterDuration > afterThumbnail)
    }

    @Test
    fun clearKeepsTheStoreUsableForTheSameSession() {
        val store = AssistantLocalPreviews()
        store.put("draft_1", byteArrayOf(1))
        store.clear()

        assertNull(store.thumbnail("draft_1"))
        assertTrue(store.put("draft_2", byteArrayOf(2)))
        assertNotNull(store.thumbnail("draft_2"))
    }

    @Test
    fun aPendingScreenshotMovesToTheAuthoritativeAttachmentId() {
        val store = AssistantLocalPreviews()
        // 圈选产物先落在本进程临时键上；权威草稿 ID 只有主进程知道。
        assertTrue(store.put("assistant-screen-selection", byteArrayOf(7, 8, 9)))
        store.markPendingImage("assistant-screen-selection")

        assertTrue("新出现的图片附件应认领这段字节", store.adoptPendingImage("draft_authoritative"))

        assertNull("临时键上的字节应已迁走", store.thumbnail("assistant-screen-selection"))
        assertArrayEquals(byteArrayOf(7, 8, 9), store.thumbnail("draft_authoritative"))
        assertEquals(3, store.bytes)
    }

    @Test
    fun adoptingWithoutAPendingImageChangesNothing() {
        val store = AssistantLocalPreviews()
        store.put("draft_1", byteArrayOf(1))

        assertFalse("没有待认领项时不得抢走已有缩略图", store.adoptPendingImage("draft_1"))
        assertArrayEquals(byteArrayOf(1), store.thumbnail("draft_1"))
    }

    @Test
    fun aClosedStoreDoesNotAdoptLateScreenshots() {
        val store = AssistantLocalPreviews()
        store.put("assistant-screen-selection", byteArrayOf(1))
        store.markPendingImage("assistant-screen-selection")
        store.close()

        assertFalse(store.adoptPendingImage("draft_authoritative"))
        assertNull(store.thumbnail("draft_authoritative"))
    }
}
