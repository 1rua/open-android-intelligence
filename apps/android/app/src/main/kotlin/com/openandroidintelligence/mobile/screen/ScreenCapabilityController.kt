package com.openandroidintelligence.mobile.screen

import com.openandroidintelligence.control.ScreenHostRegistry
import com.openandroidintelligence.control.ScreenHostStatus
import com.openandroidintelligence.control.ScreenLocalSession
import com.openandroidintelligence.control.ScreenPermission
import com.openandroidintelligence.control.ScreenSessionEndReason
import com.openandroidintelligence.control.ScreenTargetIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 三层授权在界面上的呈现结论。
 *
 * 屏幕能力要求三件互相独立的事同时成立，任何一件缺失都必须指名缺的是哪一件：
 * 1. 系统权限（无障碍服务 / MediaProjection 系统授权）；
 * 2. 本机能力开关（安装并启用对应插件；不等同于系统权限）；
 * 3. 本进程内的屏幕会话（用户本次明确同意的 READ / CAPTURE / CONTROL 集合）。
 *
 * 绝不用一个「已授权」布尔值代替这三层 —— 那样用户无法判断该去哪个系统页面。
 */
enum class ScreenGate { READY, PROVIDER_MISSING, CONNECTION_MISSING, SYSTEM_PERMISSION_MISSING, SESSION_MISSING }

/** 单项权限（READ / CAPTURE / CONTROL）的呈现结论。 */
data class ScreenPermissionPresentation(
    val permission: ScreenPermission,
    val headline: String,
    val granted: Boolean,
    val supporting: String,
)

/** 本地屏幕会话的展示投影；不泄露 binding 代次等内部事实。 */
data class ScreenSessionPresentation(
    val permissions: Set<ScreenPermission>,
    val expiresAtEpochMs: Long,
    val remainingMillis: Long,
) {
    val isExpired: Boolean get() = remainingMillis <= 0L
}

/**
 * 屏幕能力界面的不可变 UI 状态。
 *
 * 所有字段都来自 [ScreenHostStatus] 的真实读数，没有一处是本地推测或默认可用。
 */
data class ScreenCapabilityUiState(
    val providerAvailable: Boolean = false,
    val connectionReady: Boolean = false,
    val accessibilityError: String? = null,
    val captureError: String? = null,
    val capabilityErrors: Map<ScreenPermission, String?> = emptyMap(),
    val session: ScreenSessionPresentation? = null,
    val denialCode: String? = null,
) {
    val gate: ScreenGate
        get() = when {
            !providerAvailable -> ScreenGate.PROVIDER_MISSING
            !connectionReady -> ScreenGate.CONNECTION_MISSING
            else -> ScreenGate.READY
        }

    val hasSession: Boolean get() = session != null && !session.isExpired

    fun has(permission: ScreenPermission): Boolean = session?.permissions?.contains(permission) == true && hasSession

    /** 无障碍是否可用：宿主状态里没有无障碍错误即为可用。 */
    val accessibilityReady: Boolean get() = providerAvailable && accessibilityError == null

    /** 独立截图源是否已获得系统授权。 */
    val captureSystemGranted: Boolean get() = providerAvailable && captureError == null
}

/**
 * 屏幕能力状态读取器。
 *
 * [refresh] 只调用 `host.status()` —— 它是只读诊断，会顺便清理已过期或绑定失效的旧会话，
 * **绝不会创建许可**。任何创建许可的路径都必须经用户真实的同意动作。
 */
class ScreenCapabilityController(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<ScreenCapabilityUiState> = _state.asStateFlow()

    /** 重新读取真实状态：回到前台、系统设置返回、授权结果返回后都应调用。 */
    fun refresh() {
        _state.value = readState()
    }

    /**
     * 用户明确同意后开始本地会话。
     *
     * @return 成功为 null，失败为用户可读的说明；不抛异常、不显示内部错误码。
     */
    fun startSession(permissions: Set<ScreenPermission>, ttlMillis: Long): String? {
        val host = ScreenHostRegistry.current
            ?: return "当前版本未接入屏幕能力，暂时不能开始"
        val binding = host.binding()
            ?: return "还没有连接到已配对的设备，请先登录并配对"
        if (permissions.isEmpty()) return "请至少选择一项权限"

        val target: ScreenTargetIdentity? = if (ScreenPermission.CONTROL in permissions) {
            // 控制必须先绑定当时的目标窗口，否则动作会在错误的应用上执行。
            runCatching { host.localTarget() }.getOrElse {
                return "现在无法确定要操作哪个界面，请先开启无障碍服务并保持目标应用在前台"
            }
        } else {
            null
        }

        return try {
            host.authorization.start(binding, permissions, target, ttlMillis)
            refresh()
            null
        } catch (_: IllegalArgumentException) {
            "会话有效期不合法，请重新选择"
        } catch (denied: com.openandroidintelligence.control.ScreenAuthorizationException) {
            readableScreenError(denied.code)
        } catch (_: Exception) {
            "开始屏幕会话失败，请稍后重试"
        }
    }

    /** 用户主动结束：撤销本地许可、清确认、取消请求、清除像素缓存。 */
    fun revokeSession() {
        ScreenHostRegistry.current?.revoke()
        refresh()
    }

    private fun readState(): ScreenCapabilityUiState {
        val host = ScreenHostRegistry.current
            ?: return ScreenCapabilityUiState(providerAvailable = false)
        val status: ScreenHostStatus = runCatching { host.status() }.getOrElse {
            // 读取失败不得显示成「可用」：明确降级为连接未就绪。
            return ScreenCapabilityUiState(providerAvailable = true, connectionReady = false)
        }
        val now = clock()
        return ScreenCapabilityUiState(
            providerAvailable = true,
            connectionReady = status.binding != null,
            accessibilityError = status.accessibilityError,
            captureError = status.captureError,
            capabilityErrors = status.capabilityErrors,
            session = status.local?.session?.toPresentation(now),
            denialCode = status.local?.denialCode,
        )
    }

    /** 启动一个周期性刷新，直到 scope 取消；用于展示会话倒计时。 */
    fun startPolling(intervalMillis: Long = 1_000L) {
        scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                refresh()
                kotlinx.coroutines.delay(intervalMillis)
            }
        }
    }
}

private fun ScreenLocalSession.toPresentation(now: Long) = ScreenSessionPresentation(
    permissions = permissions,
    expiresAtEpochMs = expiresAtEpochMs,
    remainingMillis = (expiresAtEpochMs - now).coerceAtLeast(0L),
)

/** 会话结束原因的用户可读说明。 */
fun readableSessionEndReason(reason: ScreenSessionEndReason): String = when (reason) {
    ScreenSessionEndReason.REPLACED -> "开始了新的屏幕会话，上一个已结束"
    ScreenSessionEndReason.REVOKED -> "你已主动结束屏幕会话"
    ScreenSessionEndReason.EXPIRED -> "屏幕会话已到期，需要重新确认"
    ScreenSessionEndReason.BINDING_CHANGED -> "账号或配对已变化，屏幕会话已结束"
    ScreenSessionEndReason.DISCONNECTED -> "与设备的连接已断开，屏幕会话已结束"
    ScreenSessionEndReason.ACCOUNT_CHANGED -> "已切换账号，屏幕会话已结束"
}
