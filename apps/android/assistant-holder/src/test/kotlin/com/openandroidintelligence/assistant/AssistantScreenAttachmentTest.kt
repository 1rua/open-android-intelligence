package com.openandroidintelligence.assistant

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * 屏幕附件的安全边界回归。
 *
 * 界面从原生 View 换成了 Compose 之后，这层边界必须保持：
 * 1. 编码发生在工作线程，且位图在回调前已经被拥有者释放；
 * 2. 界面关闭后迟到的编码结果必须被丢弃，不能把附件塞进已经结束的会话；
 * 3. 没有当前同意时附件立即回收，不会发出 `screen` 命令。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AssistantScreenAttachmentTest {
    private val commands = mutableListOf<String>()
    private var client: AssistantBridgeClient? = null

    private class BridgeContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        lateinit var connection: ServiceConnection
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            this.connection = connection
            return true
        }

        override fun unbindService(connection: ServiceConnection) {}
    }

    private fun connectedClient(consent: Boolean = true): AssistantBridgeClient {
        val context = BridgeContext()
        val target = AssistantBridgeClient(context).also { client = it }
        target.connect()
        val bridge = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                val command = message.data.getString("command")!!
                commands += command
                if (command == "connect") {
                    message.replyTo.send(
                        Message.obtain().apply {
                            data = Bundle().apply {
                                putInt("version", 1)
                                putString("token", "test-session")
                                putString("state", """{"connected":true,"consent":$consent}""")
                            }
                        },
                    )
                }
                true
            },
        )
        context.connection.onServiceConnected(ComponentName(context, "TestBridge"), bridge.binder)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("connect"), commands)
        return target
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("asynchronous screenshot operation did not complete", condition())
    }

    @After fun cleanup() {
        client?.dispose()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun selectedBitmapIsEncodedOffTheUiThreadAndReleasedBeforeReturningAnIntactPng() {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val completed = CountDownLatch(1)
        val encoded = AtomicReference<ByteArray?>()
        val callbackThread = AtomicReference<Thread>()
        val releasedBeforeCallback = AtomicReference<Boolean>()
        encodeAssistantScreen(bitmap) { bytes ->
            encoded.set(bytes)
            callbackThread.set(Thread.currentThread())
            releasedBeforeCallback.set(bitmap.isRecycled)
            completed.countDown()
        }
        assertTrue("the worker did not finish encoding", completed.await(5, TimeUnit.SECONDS))
        assertNotSame(Looper.getMainLooper().thread, callbackThread.get())
        assertEquals(true, releasedBeforeCallback.get())
        val bytes = encoded.get()!!
        assertTrue("the encoder owns and releases the selected bitmap", bitmap.isRecycled)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded)
        assertEquals(32, decoded.width)
        assertEquals(24, decoded.height)
        assertEquals(Color.RED, decoded.getPixel(10, 10))
        decoded.recycle()
        bytes.fill(0)
    }

    @Test fun closingTheBridgeBeforeTheEncodedImageReturnsDropsTheAttachment() {
        val target = connectedClient()
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)

        target.attachScreen(bitmap)
        target.dispose()
        awaitCondition { bitmap.isRecycled }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(commands.contains("screen"))
    }

    @Test fun anAttachmentWithoutCurrentConsentIsReleasedWithoutDelivery() {
        val target = connectedClient(consent = false)
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)

        target.attachScreen(bitmap)
        assertTrue(bitmap.isRecycled)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(commands.contains("screen"))
    }
}
