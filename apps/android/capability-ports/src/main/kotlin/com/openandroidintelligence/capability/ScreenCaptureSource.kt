package com.openandroidintelligence.capability

import android.content.Intent

/**
 * 一帧真实屏幕截图（PNG 编码）。
 *
 * 像素在 JVM 边界上是可变字节数组，因此与模块内 [ScreenContentSnapshot]
 * 同样采用 owned-copy 语义：[copyOf] 内部复制，[pngBytes] 每次返回新副本，
 * 接收方与采集端互不能改动对方手里的帧。采集端随后停止会话或释放资源，
 * 不影响已经交付的帧。
 */
class ScreenCapture private constructor(
    private val png: ByteArray,
    val widthPixels: Int,
    val heightPixels: Int,
) {
    /** 每次返回新的副本；改动返回值不影响帧本体。 */
    fun pngBytes(): ByteArray = png.copyOf()

    companion object {
        fun copyOf(pngBytes: ByteArray, widthPixels: Int, heightPixels: Int): ScreenCapture {
            require(widthPixels > 0) { "capture width must be positive" }
            require(heightPixels > 0) { "capture height must be positive" }
            require(pngBytes.isNotEmpty()) { "capture bytes must not be empty" }
            return ScreenCapture(pngBytes.copyOf(), widthPixels, heightPixels)
        }
    }
}

/**
 * 屏幕截图来源：圈选画框的唯一合法截图供给口。
 *
 * 屏幕采集属高敏能力，接口形状直接编码隐私红线：
 * - 可用性可查询（[isAvailable]）：只有「系统提供投影服务 + 用户已通过
 *   系统对话框显式授予 + 会话仍存活」三件事同时成立才为 true；
 * - 采集必须经用户授权：系统授权对话框由宿主前台 Activity 用
 *   [createAuthorizationIntent] 发起，结果经 [onAuthorizationResult] 转交，
 *   本接口不提供任何绕过系统授权的入口，也不支持后台静默采集；
 * - 失败/未授权一律返回 null（或 false），绝不抛异常冒充成功、绝不静默
 *   重试、绝不伪造截图。
 *
 * 实现方注意（Android 14+）：投影会话活跃期间必须存在
 * `foregroundServiceType="mediaProjection"` 的前台服务。宿主需在转交授权
 * 结果前启动 [MediaProjectionCaptureService]，并在 [release] 或会话被系统
 * 回收后停止它。
 */
interface ScreenCaptureSource {
    /** 用户已显式授予且当前可采集时返回 true；从未授予/已撤销/不可用返回 false。 */
    val isAvailable: Boolean

    /**
     * 生成必须由宿主前台 Activity 经 `startActivityForResult` 发起的系统
     * 授权对话框 Intent。设备不支持屏幕投影时返回 null。
     */
    fun createAuthorizationIntent(): Intent?

    /**
     * 宿主在授权对话框返回后转交系统结果；返回是否成功建立了可用的采集
     * 会话。用户拒绝、结果数据缺失或会话建立失败时返回 false 且能力保持
     * 不可用。重复转交新授权时，旧会话会先被释放。
     */
    fun onAuthorizationResult(resultCode: Int, resultData: Intent?): Boolean

    /**
     * 采集一帧当前屏幕。未授权/会话已被系统回收/截帧失败返回 null；
     * 每次调用最多尝试一次取帧，不做内部重试。
     */
    suspend fun capture(): ScreenCapture?

    /** 停止会话并释放资源；幂等。释放后能力明确不可用。 */
    fun release()
}

/**
 * 系统投影运行时 seam：真实实现把 MediaProjection API 的系统调用收口在
 * 这里（[MediaProjectionRuntime]），编排层的授权决策测试用 fake 供给本
 * 接口，不驱动真实投屏。
 */
interface ScreenProjectionRuntime : AutoCloseable {
    /** 设备是否提供 MediaProjectionManager 服务。 */
    fun hasProjectionService(): Boolean

    /** 系统授权对话框 Intent；服务缺失时为 null。 */
    fun createAuthorizationIntent(): Intent?

    /** 用授权结果建立投屏显示会话；拒绝或系统失败返回 null。 */
    fun openSession(resultCode: Int, resultData: Intent?): ProjectionDisplaySession?
}

/**
 * 一次授权建立的投影显示会话 seam：封装 VirtualDisplay + ImageReader 的
 * 生命周期与按需截帧。实现必须在 [close] 中释放 VirtualDisplay、
 * ImageReader 并停止 MediaProjection，不得泄漏。
 */
interface ProjectionDisplaySession : AutoCloseable {
    /** 会话仍可截帧；被系统回收（投影停止）后为 false。 */
    val isActive: Boolean

    /** 截一帧并编码为 [ScreenCapture]；无帧/失败返回 null，不抛异常。 */
    fun grabFrame(): ScreenCapture?
}
