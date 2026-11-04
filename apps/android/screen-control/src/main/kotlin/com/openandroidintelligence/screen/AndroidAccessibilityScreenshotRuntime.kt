/* 无障碍按需截图：Android 11+ `AccessibilityService.takeScreenshot()` 的真实系统壳。 */
package com.openandroidintelligence.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import com.openandroidintelligence.capability.AccessibilityScreenshotRuntime
import com.openandroidintelligence.capability.ScreenCapture
import com.openandroidintelligence.capability.ScreenCaptureFailure
import com.openandroidintelligence.capability.ScreenCaptureOutcome
import com.openandroidintelligence.capability.accessibilityScreenshotFailure
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [AccessibilityScreenshotRuntime] 的真实实现。
 *
 * 三条查询严格分开，不做任何「乐观推断」：
 * - [isServiceEnabledInSettings] 读系统设置，服务没连上时也能回答，因此能区分
 *   「从未授权」与「开过但被系统解绑」；
 * - [isServiceConnected] 看进程里的真实绑定引用；
 * - [hasScreenshotCapability] 读 `AccessibilityServiceInfo.capabilities` 的能力位。
 *
 * 取帧只发一次请求，不重试、不缓存旧帧；系统错误码原样转成可分类原因
 * （频率限制 / 受保护窗口 / 系统拒绝 / 取帧失败各不相同）。
 * 受保护窗口由系统直接拒绝，这里没有任何绕过路径。
 */
class AndroidAccessibilityScreenshotRuntime(
    private val context: Context,
) : AccessibilityScreenshotRuntime {

    override fun isServiceEnabledInSettings(): Boolean = try {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val own = ComponentName(context, ScreenAccessibilityService::class.java)
        val full = own.flattenToString()
        val short = own.flattenToShortString()
        enabled.split(':').any { entry ->
            entry.equals(full, ignoreCase = true) || entry.equals(short, ignoreCase = true)
        }
    } catch (_: RuntimeException) {
        // 读不到系统设置时不能乐观地说「已开启」，否则界面会错误地引导用户「重新开启」。
        false
    }

    override fun isServiceConnected(): Boolean = ScreenAccessibilityConnection.service != null

    override fun hasScreenshotCapability(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val service = ScreenAccessibilityConnection.service ?: return false
        return try {
            val capabilities = service.serviceInfo?.capabilities ?: 0
            capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        } catch (_: RuntimeException) {
            false
        }
    }

    override suspend fun takeScreenshot(): ScreenCaptureOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenCaptureOutcome.Failure(ScreenCaptureFailure.UNSUPPORTED)
        }
        val service = ScreenAccessibilityConnection.service
            ?: return ScreenCaptureOutcome.Failure(ScreenCaptureFailure.SERVICE_DISCONNECTED)
        // 服务连着但没声明截图能力：这是配置事实，硬调只会拿到系统拒绝，不如如实报告。
        if (!hasScreenshotCapability()) {
            return ScreenCaptureOutcome.Failure(ScreenCaptureFailure.UNSUPPORTED)
        }

        val displayId = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java)?.defaultDisplay?.displayId
        }.getOrNull() ?: Display.DEFAULT_DISPLAY

        // 所有者从请求前活到编码返回之后，覆盖 Main/超时作用域丢弃成功结果的窗口。
        return ScreenshotBufferOwner().use { bufferOwner ->
            val outcome = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                withContext(Dispatchers.Main.immediate) {
                    suspendCancellableCoroutine { continuation ->
                        // 回调只交接结果，不在主线程编码；成功缓冲区先登记再恢复协程。
                        val executor = Executor { it.run() }
                        try {
                            service.takeScreenshot(
                                displayId,
                                executor,
                                object : AccessibilityService.TakeScreenshotCallback {
                                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                        // resume 的取消回调处理恢复前被取消的结果；即使已超时后
                                        // 才收到系统回调，也由同一个所有者关闭缓冲区。
                                        bufferOwner.resume(
                                            continuation,
                                            FrameHolder(result),
                                            result.hardwareBuffer,
                                        )
                                    }

                                    override fun onFailure(errorCode: Int) {
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                BitmapHolder(accessibilityScreenshotFailure(errorCode)),
                                            )
                                        }
                                    }
                                },
                            )
                        } catch (_: RuntimeException) {
                            // 服务刚好被系统解绑时可能抛异常：如实报告为断连，不伪装成取帧失败。
                            if (continuation.isActive) {
                                continuation.resume(
                                    BitmapHolder(
                                        if (isServiceConnected()) ScreenCaptureFailure.CAPTURE_FAILED
                                        else ScreenCaptureFailure.SERVICE_DISCONNECTED,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            when (val pending: Pending? = outcome) {
                // 编码 finally 负责正常消费后的释放；若结果在到达编码前被丢弃，
                // 外层 use 负责释放，二者共享同一个只关闭一次的所有者。
                is FrameHolder -> encodeScreenshot(pending.result, bufferOwner)
                is BitmapHolder -> ScreenCaptureOutcome.Failure(pending.failure)
                // 超时不是「成功」，也不能静默退化成未授权：明确报告本次取帧失败。
                null -> ScreenCaptureOutcome.Failure(ScreenCaptureFailure.CAPTURE_FAILED)
            }
        }
    }

    /** 把系统返回的硬件缓冲区转成 PNG；缓冲区无论如何都要关闭，避免泄漏图形内存。 */
    private suspend fun encodeScreenshot(
        result: AccessibilityService.ScreenshotResult,
        bufferOwner: ScreenshotBufferOwner,
    ): ScreenCaptureOutcome {
        val buffer = result.hardwareBuffer
        return try {
            withContext(Dispatchers.IO) {
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    ?: return@withContext ScreenCaptureOutcome.Failure(ScreenCaptureFailure.CAPTURE_FAILED)
                val encoded = encodePng(bitmap)
                if (encoded != null) {
                    ScreenCaptureOutcome.Success(encoded)
                } else {
                    ScreenCaptureOutcome.Failure(ScreenCaptureFailure.CAPTURE_FAILED)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            ScreenCaptureOutcome.Failure(ScreenCaptureFailure.CAPTURE_FAILED)
        } finally {
            bufferOwner.close()
        }
    }

    /** 把位图编码成 PNG；失败返回 null，由调用方归入取帧失败，绝不返回空字节冒充成功。 */
    private suspend fun encodePng(bitmap: Bitmap): ScreenCapture? {
        // wrapHardwareBuffer 返回硬件位图，压缩前必须复制成软件位图。
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        } ?: return null
        return try {
            val output = ByteArrayOutputStream()
            if (!software.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                null
            } else {
                currentCoroutineContext().ensureActive()
                ScreenCapture.copyOf(output.toByteArray(), software.width, software.height)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        } finally {
            // 只有在确实复制过时才单独回收副本，避免对同一个位图重复回收。
            if (software !== bitmap) software.recycle()
        }
    }

    /** withTimeoutOrNull 的中转基类：让挂起点的返回类型可确定，不依赖推断。 */
    private sealed interface Pending

    /** 截屏成功后的中转：携带系统结果，转码在 IO 上下文里进行。 */
    private class FrameHolder(val result: AccessibilityService.ScreenshotResult) : Pending

    /** 截屏失败的中转。 */
    private class BitmapHolder(val failure: ScreenCaptureFailure) : Pending

    private companion object {
        /** 系统对无障碍截图的单次等待上限；超时按取帧失败处理，不静默变成未授权。 */
        const val SCREENSHOT_TIMEOUT_MS = 5_000L
    }
}