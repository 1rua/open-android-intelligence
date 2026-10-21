package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 原图按需加载的控制器契约。
 *
 * 原图与缩略图是两条链路：缩略图随消息自动保存，原图只在用户打开大图预览时读取。
 * 这里用假的媒体端口覆盖成功、失败、无端口、代数失效、任务取消与缓存淘汰，
 * 完全不依赖真实图片解码或磁盘。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchOriginalImageTest {

    private val mediaMessage = TimelineMessage(
        "msg_media",
        "assistant",
        listOf(MessagePart.Attachment(AttachmentDraftId("file_one"), "回答.png", "image/png")),
        10,
        conversationId = ConversationId("conv_a"),
    )

    private val twoAttachmentMessage = TimelineMessage(
        "msg_media",
        "assistant",
        listOf(
            MessagePart.Attachment(AttachmentDraftId("file_one"), "一.png", "image/png"),
            MessagePart.Attachment(AttachmentDraftId("file_two"), "二.png", "image/png"),
        ),
        10,
        conversationId = ConversationId("conv_a"),
    )

    @Test
    fun loadingAnOriginalGoesThroughEnsureLocalThenOpenOriginalAndPublishesReady() = runTest {
        val host = Host().apply { rows = listOf(mediaMessage) }
        val media = Media()
        var compressorInput: ByteArray? = null
        val controller = createController(host, media, compressor = { bytes ->
            compressorInput = bytes
            byteArrayOf(9, 8, 7)
        })
        try {
            controller.openThread("conv_a"); runCurrent()
            assertEquals("原图未请求时只有缩略图", OriginalImageState.IDLE, attachment(controller).originalState)
            assertNull(attachment(controller).originalBytes)
            // 打开会话时缩略图链路已经调用过一次 ensureLocal；原图请求必须自己再确保一次。
            val ensureLocalBefore = media.ensureLocalCalls

            controller.requestOriginalImage("file_one")
            assertEquals("请求后立刻进入加载态，界面不会一直显示空白", OriginalImageState.LOADING, attachment(controller).originalState)
            runCurrent()

            val loaded = attachment(controller)
            assertEquals(OriginalImageState.READY, loaded.originalState)
            assertArrayEquals(byteArrayOf(9, 8, 7), loaded.originalBytes)
            assertNull(loaded.originalError)
            assertEquals("原图请求必须自己先确保原件在本地", ensureLocalBefore + 1, media.ensureLocalCalls)
            assertEquals("必须打开真实原件端口", 1, media.openOriginalCalls)
            assertArrayEquals("压缩前的字节必须来自 openOriginal", media.bytes, compressorInput)
        } finally {
            controller.close()
        }
    }

    @Test
    fun repeatedRequestsWhileLoadingKeepASingleTask() = runTest {
        val host = Host().apply { rows = listOf(mediaMessage) }
        val media = Media()
        val controller = createController(host, media, compressor = { it.copyOf() })
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.requestOriginalImage("file_one")
            controller.requestOriginalImage("file_one")
            runCurrent()
            assertEquals("同一附件只允许一个在途原图任务", 1, media.openOriginalCalls)
            assertEquals(OriginalImageState.READY, attachment(controller).originalState)
        } finally {
            controller.close()
        }
    }

    @Test
    fun mediaFailureIsMappedToFailedWithThePortErrorCode() = runTest {
        val host = Host().apply { rows = listOf(mediaMessage) }
        val media = Media().apply { failure = IOException("REMOTE_UNAVAILABLE") }
        val controller = createController(host, media)
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.requestOriginalImage("file_one"); runCurrent()
            val failed = attachment(controller)
            assertEquals(OriginalImageState.FAILED, failed.originalState)
            assertNull(failed.originalBytes)
            assertEquals("REMOTE_UNAVAILABLE", failed.originalError)
        } finally {
            controller.close()
        }
    }

    @Test
    fun decodeFailureIsReportedInsteadOfPublishingBrokenBytes() = runTest {
        val host = Host().apply { rows = listOf(mediaMessage) }
        val media = Media()
        val controller = createController(host, media, compressor = { null })
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.requestOriginalImage("file_one"); runCurrent()
            val failed = attachment(controller)
            assertEquals(OriginalImageState.FAILED, failed.originalState)
            assertEquals("ORIGINAL_DECODE_FAILED", failed.originalError)
            assertNull(failed.originalBytes)
        } finally {
            controller.close()
        }
    }

    @Test
    fun withoutAMediaPortTheRequestFailsImmediately() = runTest {
        val host = Host().apply { rows = listOf(mediaMessage) }
        val controller = createController(host, null)
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.requestOriginalImage("file_one"); runCurrent()
            val failed = attachment(controller)
            assertEquals("没有端口就没有原件，必须明确失败而不是无限加载", OriginalImageState.FAILED, failed.originalState)
            assertEquals("LOCAL_MEDIA_UNAVAILABLE", failed.originalError)
        } finally {
            controller.close()
        }
    }

    @Test
    fun bytesOverTheReadLimitFailWithoutReachingTheDecoder() = runTest {
        val host = Host().apply { rows = listOf(mediaMessage) }
        val media = Media().apply { bytes = ByteArray(24 * 1024 * 1024 + 1) }
        var compressorCalls = 0
        val controller = createController(host, media, compressor = { compressorCalls++; it.copyOf() })
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.requestOriginalImage("file_one"); runCurrent()
            val failed = attachment(controller)
            assertEquals(OriginalImageState.FAILED, failed.originalState)
            assertTrue("超限必须给出明确错误码", failed.originalError?.contains("ORIGINAL_TOO_LARGE") == true)
            assertEquals("超限文件不能进入解码", 0, compressorCalls)
        } finally {
            controller.close()
        }
    }

    @Test
    fun aRequestBeforeOpeningAThreadDoesNothing() = runTest {
        val host = Host()
        val media = Media()
        val controller = createController(host, media, compressor = { it.copyOf() })
        try {
            runCurrent()
            controller.requestOriginalImage("file_one"); runCurrent()
            assertEquals("没有活动会话就不发起原图请求", 0, media.openOriginalCalls)
        } finally {
            controller.close()
        }
    }

    @Test
    fun aReplacedOriginalGenerationIsRejectedInsteadOfPublished() = runTest {
        val host = Host().apply { rows = listOf(mediaMessage) }
        val media = Media()
        val gate = CompletableDeferred<Unit>()
        media.loadingGate = gate
        val controller = createController(host, media, compressor = { it.copyOf() })
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.requestOriginalImage("file_one"); runCurrent()
            assertEquals(OriginalImageState.LOADING, attachment(controller).originalState)

            // 读取期间原件被替换：旧代数即使读完也不能发布到界面。
            media.epoch = 1
            gate.complete(Unit); runCurrent()

            val failed = attachment(controller)
            assertEquals(OriginalImageState.FAILED, failed.originalState)
            assertEquals("ORIGINAL_EPOCH_CHANGED", failed.originalError)
            assertNull("失效代数不得留下原图字节", failed.originalBytes)
        } finally {
            gate.complete(Unit)
            controller.close()
        }
    }

    @Test
    fun deletingTheMessageCancelsTheOriginalTaskBeforeItDecodesOrPublishes() = runTest {
        val host = Host().apply { rows = listOf(mediaMessage.copy(contentRevision = 1)) }
        val media = Media()
        val gate = CompletableDeferred<Unit>()
        media.loadingGate = gate
        var compressorCalls = 0
        val controller = createController(host, media, compressor = { compressorCalls++; it.copyOf() })
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.requestOriginalImage("file_one"); runCurrent()
            assertEquals(OriginalImageState.LOADING, attachment(controller).originalState)

            host.rows = emptyList()
            host.events.emit(
                VerifiedConversationEvent.TimelineTombstoned("evt_delete", 20, "msg_media", 2, ConversationId("conv_a")),
            )
            runCurrent()
            gate.complete(Unit); runCurrent()

            assertTrue("已删除的消息不能凭迟到的原图重新出现", rows(controller).isEmpty())
            assertEquals("被取消的任务不得继续解码", 0, compressorCalls)
        } finally {
            gate.complete(Unit)
            controller.close()
        }
    }

    @Test
    fun onlyTheMostRecentOriginalsStayCachedAndEvictedBytesAreZeroed() = runTest {
        val host = Host().apply { rows = listOf(twoAttachmentMessage) }
        val media = Media()
        // 注入小上限：真实默认值是 3 张，测试用 1 张即可验证淘汰行为。
        val controller = createController(host, media, cacheSize = 1, compressor = { it.copyOf() })
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.requestOriginalImage("file_one"); runCurrent()
            val first = attachment(controller, "file_one")
            assertEquals(OriginalImageState.READY, first.originalState)
            val firstBytes = requireNotNull(first.originalBytes)

            controller.requestOriginalImage("file_two"); runCurrent()
            assertEquals("被淘汰的原图必须回到 IDLE", OriginalImageState.IDLE, attachment(controller, "file_one").originalState)
            assertEquals("最新一张原图仍可用", OriginalImageState.READY, attachment(controller, "file_two").originalState)
            assertTrue("淘汰的原图字节必须清零", firstBytes.all { it == 0.toByte() })
        } finally {
            controller.close()
        }
    }

    private fun attachment(controller: WorkbenchController, id: String = "file_one") =
        rows(controller).single().attachments.first { it.draftId == id }

    private fun rows(controller: WorkbenchController) =
        (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty()

    private fun TestScope.createController(
        host: Host,
        media: Media?,
        cacheSize: Int = 3,
        compressor: (ByteArray) -> ByteArray? = { it.copyOf() },
    ) = WorkbenchController(
        this,
        host,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("test"), emptyList())
        },
        { ConversationScope("profile", "gateway", "account", "install") },
        media = media,
        mediaDispatcher = UnconfinedTestDispatcher(testScheduler),
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        originalImageCacheSize = cacheSize,
        originalImageCompressor = compressor,
    )

    private class Host : ConversationRepository {
        var rows = emptyList<TimelineMessage>()
        var submissions = 0
        val sent = mutableListOf<OutgoingMessage>()
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)

        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
            ConversationPage(emptyList(), null)

        override suspend fun timeline(conversationId: String, page: PageRequest) =
            TimelinePage(if (conversationId == "conv_a") rows else emptyList(), null)

        override suspend fun submitMessage(message: OutgoingMessage) = submitMessage("conv_a", message)

        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            submissions++
            sent += message
            return MessageAcceptance("msg_upload", message.clientMessageId.value)
        }

        override fun observeEvents(scope: ConversationScope) = events

        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance =
            error("本测试不提交批次")

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation =
            error("本测试不创建会话")

        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }

    /** 假的媒体端口：可注入失败、线程闸门与原件代数，记录真实调用次数。 */
    private class Media : HistoricalMediaPort {
        var bytes: ByteArray = byteArrayOf(1, 2, 3)
        var failure: Exception? = null
        var loadingGate: CompletableDeferred<Unit>? = null
        var epoch = 0
        var ensureLocalCalls = 0
        var openOriginalCalls = 0
        val local = linkedMapOf<Pair<String, String>, HistoricalMediaMetadata>()

        override suspend fun metadata(conversationId: String, attachmentId: String) = HistoricalMediaMetadata(
            conversationId,
            attachmentId,
            "回答.png",
            "image/png",
            bytes.size.toLong(),
            "sha256:" + "0".repeat(64),
            true,
            bytes.size.toLong(),
        )

        override suspend fun retain(metadata: HistoricalMediaMetadata) {
            local[metadata.conversationId to metadata.attachmentId] = metadata
        }

        override suspend fun localMetadata(conversationId: String, attachmentId: String) =
            local[conversationId to attachmentId]

        override suspend fun ensureLocal(conversationId: String, attachmentId: String): HistoricalMediaMetadata {
            ensureLocalCalls++
            failure?.let { throw it }
            return metadata(conversationId, attachmentId).also { retain(it) }
        }

        override fun isRetained(attachmentId: String) = local.keys.any { it.second == attachmentId }

        override fun preview(attachmentId: String) = byteArrayOf(7, 7, 7)

        override fun remove(attachmentId: String) {
            local.keys.removeAll { it.second == attachmentId }
        }

        /** 删除/恢复事件会走到这里；不实现会让事件处理抛错，时间线就永远不刷新。 */
        override suspend fun applyTransition(transition: HistoricalMediaTransition) {
            if (transition.deleted) transition.attachmentIds.forEach { id ->
                local.keys.removeAll { it.second == id }
            }
        }

        override suspend fun openOriginal(conversationId: String, attachmentId: String): InputStream {
            openOriginalCalls++
            // 闸门在 suspend 点等待：取消后 await 会立刻抛 CancellationException。
            loadingGate?.await()
            failure?.let { throw it }
            return ByteArrayInputStream(bytes)
        }

        override fun originalEpoch(conversationId: String, attachmentId: String): String = "gen-$epoch"

        override fun <T> withOriginalEpoch(
            conversationId: String,
            attachmentId: String,
            epoch: String,
            commit: () -> T,
        ): T {
            check(epoch == "gen-${this.epoch}") { "LOCAL_MEDIA_GENERATION_CHANGED" }
            return commit()
        }
    }
}
