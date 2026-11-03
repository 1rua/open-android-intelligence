package com.openandroidintelligence.capability

/**
 * 按需截图的编排实现：**无障碍优先，MediaProjection 作为需明确同意的备用**。
 *
 * 为什么需要这一层：MediaProjection 的系统授权票据是一次性的，每次重新建立
 * 采集会话都要弹一次系统确认框，Agent 高频截图时无法接受。Android 11+
 * 的无障碍服务 `takeScreenshot()` 是**按需单帧**的，用户只需在系统设置里把
 * 无障碍服务开启一次，之后每次取帧都不再打扰用户。
 *
 * 但无障碍截图**不是无条件的**，本类只做如实反映，绝不绕过任何系统限制：
 *
 * - 无障碍截图要求服务真的由系统绑定（[AccessibilityScreenshotRuntime.isServiceConnected]），
 *   且服务信息声明了 `canTakeScreenshot`；两者缺一就退到备用方案并说明原因；
 * - 受保护窗口（FLAG_SECURE / 安全窗口）由系统直接拒绝，本类把这种拒绝
 *   识别成 [ScreenCaptureFailure.SECURE_WINDOW]，**没有任何绕过的路径**；
 * - 系统频率限制识别成 [ScreenCaptureFailure.RATE_LIMITED]，是可恢复状态，
 *   不得表述成「设备不支持截图」；
 * - 本类不会为了「让截图看起来成功」而重试、重建会话或返回旧帧。
 *
 * 授权模型保持三层不变：Agent 的请求仍必须先经 Core 的统一能力接口与本机
 * 全局授权；这个类只决定「这一帧像素从哪来」。用户对 MediaProjection 的
 * 系统同意仍然只能由宿主前台 Activity 发起（[createAuthorizationIntent]），
 * 本类不提供任何静默授权入口。
 */
class AccessibilityFirstScreenCaptureSource(
    private val accessibility: AccessibilityScreenshotRuntime,
    private val projection: ScreenCaptureSource,
    /** 系统版本是否支持无障碍截图（API 30+）。缺省视为支持，由运行时如实回答。 */
    private val accessibilityScreenshotSupported: () -> Boolean = { true },
    /** 设备是否锁定；锁屏时无障碍与投屏都取不到可用画面。 */
    private val deviceLocked: () -> Boolean = { false },
) : ScreenCaptureSource {

    /**
     * 只要任一路径当前可用即为 true。
     *
     * 注意语义差别：无障碍路径「服务已连接且声明能力」就算可用（不需要每次同意），
     * MediaProjection 路径仍然要求用户已经通过系统对话框授予且会话存活。
     */
    override val isAvailable: Boolean
        get() = accessibilityAvailable() || projection.isAvailable

    /** 最近一次截图的失败原因，供诊断与界面展示；成功后清空。 */
    @Volatile
    var lastFailure: ScreenCaptureFailure? = null
        private set

    /** 当前实际会使用的取景路径；两路都不可用时为 null。 */
    val preferredOrigin: ScreenCaptureOrigin?
        get() = when {
            accessibilityAvailable() -> ScreenCaptureOrigin.ACCESSIBILITY
            projection.isAvailable -> ScreenCaptureOrigin.MEDIA_PROJECTION
            else -> null
        }

    /** 无障碍截图这一路是否就绪（服务已连接 + 已声明截图能力 + 系统版本支持）。 */
    fun accessibilityAvailable(): Boolean = try {
        accessibilityScreenshotSupported() &&
            accessibility.isServiceConnected() &&
            accessibility.hasScreenshotCapability()
    } catch (_: RuntimeException) {
        false
    }

    /**
     * 诊断当前截图不可用的**具体**原因，让界面能给出可执行的下一步。
     *
     * 返回 null 表示当前至少有一条可用路径。判定顺序对应「用户最该先做什么」：
     * 锁屏 > 系统不支持 > 上次真实失败 > 服务断连 > 从未授权。
     */
    fun diagnose(): ScreenCaptureFailure? {
        if (isAvailable) return null
        if (deviceLocked()) return ScreenCaptureFailure.DEVICE_LOCKED
        if (!accessibilityScreenshotSupported()) {
            // 系统不支持无障碍截图时，只有投屏可用才不算不可用（已由 isAvailable 排除）。
            return ScreenCaptureFailure.UNSUPPORTED
        }
        lastFailure?.let { recent ->
            // 频率限制与取帧失败是瞬时事实，报告它比笼统的「未授权」更准确。
            if (recent == ScreenCaptureFailure.RATE_LIMITED || recent == ScreenCaptureFailure.SECURE_WINDOW ||
                recent == ScreenCaptureFailure.CAPTURE_FAILED) return recent
        }
        // 关键区分：设置里开过、但系统当前没绑定 → 服务断连（去无障碍设置重新开启）；
        // 从来没开过 → 从未授权（这是正常起点，不是阻塞）。
        val enabledInSettings = runCatching { accessibility.isServiceEnabledInSettings() }.getOrDefault(false)
        val connected = runCatching { accessibility.isServiceConnected() }.getOrDefault(false)
        return if (enabledInSettings && !connected) {
            ScreenCaptureFailure.SERVICE_DISCONNECTED
        } else if (connected && !runCatching { accessibility.hasScreenshotCapability() }.getOrDefault(false)) {
            // 服务连着却声明不了截图能力：这是服务的配置事实，需要更新服务配置或升级系统，
            // 不是用户操作能解决的，也不是「未授权」。
            ScreenCaptureFailure.UNSUPPORTED
        } else {
            ScreenCaptureFailure.NOT_AUTHORIZED
        }
    }

    override fun createAuthorizationIntent(): android.content.Intent? {
        // 无障碍路径不需要系统授权对话框；只有备用路径需要，且仍由宿主前台 Activity 发起。
        return projection.createAuthorizationIntent()
    }

    override fun onAuthorizationResult(resultCode: Int, resultData: android.content.Intent?): Boolean {
        val granted = projection.onAuthorizationResult(resultCode, resultData)
        lastFailure = if (granted) null else ScreenCaptureFailure.SYSTEM_REJECTED
        return granted
    }

    override suspend fun capture(): ScreenCapture? = when (val result = captureDetailed()) {
        is ScreenCaptureResult.Success -> result.capture
        is ScreenCaptureResult.Failure -> null
    }

    /**
     * 截一帧并保留分类诊断。
     *
     * 顺序刻意固定：**先试无障碍**（无需重复同意），失败后仅在符合条件时
     * 回退到 MediaProjection（需要用户此前已明确同意）。回退不是「静默重试」，
     * 而是使用另一条用户已经单独授予过的合法路径。
     */
    suspend fun captureDetailed(): ScreenCaptureResult {
        if (deviceLocked()) {
            lastFailure = ScreenCaptureFailure.DEVICE_LOCKED
            return ScreenCaptureResult.Failure(ScreenCaptureFailure.DEVICE_LOCKED, null)
        }

        if (accessibilityAvailable()) {
            val outcome = try {
                accessibility.takeScreenshot()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                ScreenCaptureOutcome.Failure(ScreenCaptureFailure.CAPTURE_FAILED)
            }
            when (outcome) {
                is ScreenCaptureOutcome.Success -> {
                    lastFailure = null
                    return ScreenCaptureResult.Success(outcome.capture, ScreenCaptureOrigin.ACCESSIBILITY)
                }
                is ScreenCaptureOutcome.Failure -> {
                    lastFailure = outcome.reason
                    // 系统拒绝 / 受保护窗口 / 频率限制都不回退：
                    // 前两者换路也拿不到像素；频率限制换到投屏会额外消耗一次会话，
                    // 反而更容易触发系统回收。只有「本次取帧失败」值得换路再试一次。
                    if (outcome.reason != ScreenCaptureFailure.CAPTURE_FAILED) {
                        return ScreenCaptureResult.Failure(outcome.reason, ScreenCaptureOrigin.ACCESSIBILITY)
                    }
                }
            }
        }

        // 备用路径：必须此前已由用户经系统对话框明确同意。
        if (projection.isAvailable) {
            val frame = try {
                projection.capture()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
            if (frame != null) {
                lastFailure = null
                return ScreenCaptureResult.Success(frame, ScreenCaptureOrigin.MEDIA_PROJECTION)
            }
            lastFailure = ScreenCaptureFailure.CAPTURE_FAILED
            return ScreenCaptureResult.Failure(ScreenCaptureFailure.CAPTURE_FAILED, ScreenCaptureOrigin.MEDIA_PROJECTION)
        }

        val reason = diagnose() ?: ScreenCaptureFailure.NOT_AUTHORIZED
        lastFailure = reason
        return ScreenCaptureResult.Failure(reason, null)
    }

    override fun release() {
        projection.release()
        // 无障碍路径没有会话可释放：它是按需单帧，释放只清诊断状态。
        lastFailure = null
    }
}