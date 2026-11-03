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

        val outcome: Pending? = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    // 回调直接在当前线程执行：转码放在回调里会阻塞主线程，
                    // 因此只把结果交回挂起点，转码在下面的 IO 上下文里做。
                    val executor = Executor { it.run() }
                    try {
                        service.takeScreenshot(
                            displayId,
                            executor,
                            object : AccessibilityService.TakeScreenshotCallback {
                                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                    if (continuation.isActive) continuation.resume(FrameHolder(result))
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
        } ?: return ScreenCaptureOutcome.Failure(ScreenCaptureFailure.CAPTURE_FAILED)

        return when (outcome) {
            is FrameHolder -> encodeScreenshot(outcome.result)
            is BitmapHolder -> ScreenCaptureOutcome.Failure(outcome.failure)
        }
    }

    /** 把系统返回的硬件缓冲区转成 PNG；缓冲区无论如何都要关闭，避免泄漏图形内存。 */
    private suspend fun encodeScreenshot(result: AccessibilityService.ScreenshotResult): ScreenCaptureOutcome {
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
            runCatching { buffer.close() }
        }
    }

    /** 把位图编码成 PNG；失败返回 null，由调用方归入取帧失败，绝不返回空字节冒充成功。 */
    private suspend fun encodePng(bitmap: Bitmap): ScreenCapture? {
        var software: Bitmap? = null
        return try {
            // wrapHardwareBuffer 得到硬件位图，压缩前必须转成软件位图。
            software = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            } ?: return null
            val output = ByteArrayOutputStream()
            if (!software.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
            currentCoroutineContext().ensureActive()
            ScreenCapture.copyOf(output.toByteArray(), software.width, software.height)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        } finally {
            if (software !== bitmap) software?.recycle()
            bitmap.recycle()
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