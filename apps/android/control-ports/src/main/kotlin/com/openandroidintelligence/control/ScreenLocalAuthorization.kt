package com.openandroidintelligence.control

import java.util.Collections
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 只由已认证的本地装配代码构造，不从远程请求参数读取。 */
data class ScreenAuthorizationBinding(
    val accountId: String,
    val pairingId: String,
    val revision: ControlRevision,
) {
    init {
        require(accountId.isNotBlank())
        require(pairingId.isNotBlank())
    }
}

enum class ScreenPermission { READ, CAPTURE, CONTROL }

enum class ScreenSessionEndReason { REPLACED, REVOKED, EXPIRED, BINDING_CHANGED, DISCONNECTED, ACCOUNT_CHANGED }

data class ScreenLocalSession internal constructor(
    val sessionId: String,
    val binding: ScreenAuthorizationBinding,
    val permissions: Set<ScreenPermission>,
    val startedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val target: ScreenTargetIdentity?,
)

data class ScreenLocalStatus(val session: ScreenLocalSession?, val denialCode: String?)

class ScreenAuthorizationException(val code: String) : IllegalStateException(code)

/**
 * 当前进程内的屏幕会话授权记录。它补充既有配对/插件/内核授权，不签发配对
 * 授权，也不替代 Android 无障碍或 MediaProjection 系统授权。
 *
 * [start] 与 [installConfirmation] 仅供可信本地代码在真实用户同意后调用；
 * 不得把它们注册为远程能力。进程重启后没有会话、没有确认，默认拒绝。
 * [onSessionEnded] 负责清除调用方持有的像素、取消任务和停止远程投影来源。
 * 执行方在进入调用和真正交付数据/执行操作之前都必须再次检查授权。
 */
class ScreenLocalAuthorization(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSessionEnded: (ScreenSessionEndReason) -> Unit = {},
    private val controlAuthorizer: ControlAuthorizer = DefaultControlAuthorizer(),
) : AutoCloseable {
    private val lock = Any()
    private var session: ScreenLocalSession? = null
    private var expiry: ScheduledFuture<*>? = null
    private val confirmations = mutableMapOf<String, UserConfirmation>()

    fun start(
        binding: ScreenAuthorizationBinding,
        permissions: Set<ScreenPermission>,
        target: ScreenTargetIdentity? = null,
        ttlMillis: Long,
    ): ScreenLocalSession {
        require(permissions.isNotEmpty())
        require(ttlMillis in 1..MAX_SCREEN_SESSION_TTL_MS)
        require(target == null || ScreenPermission.CONTROL in permissions)
        require(ScreenPermission.CONTROL !in permissions || target != null)
        val now = clock()
        require(now >= 0 && now <= Long.MAX_VALUE - ttlMillis)
        val opened = ScreenLocalSession(
            UUID.randomUUID().toString(), binding,
            Collections.unmodifiableSet(permissions.toSet()), now, now + ttlMillis, target,
        )
        synchronized(lock) {
            val replaced = session != null
            session = null
            expiry?.cancel(false)
            confirmations.clear()
            // 状态迁移与清理串行化，撤销不能被并发 start 的迟到发布覆盖。
            if (replaced) notifyEnded(ScreenSessionEndReason.REPLACED)
            session = opened
            scheduleExpiry(opened, ttlMillis)
        }
        return opened
    }

    fun status(binding: ScreenAuthorizationBinding): ScreenLocalStatus {
        return synchronized(lock) {
            val current = session ?: return@synchronized ScreenLocalStatus(null, "SCREEN_SESSION_REQUIRED")
            val now = clock()
            val reason = when {
                current.binding != binding -> ScreenSessionEndReason.BINDING_CHANGED
                now >= current.expiresAtEpochMs || now < current.startedAtEpochMs -> ScreenSessionEndReason.EXPIRED
                else -> null
            }
            if (reason == null) ScreenLocalStatus(current, null)
            else {
                session = null
                expiry?.cancel(false)
                expiry = null
                confirmations.clear()
                notifyEnded(reason)
                ScreenLocalStatus(null, if (reason == ScreenSessionEndReason.EXPIRED) "SCREEN_SESSION_EXPIRED" else "SCREEN_SESSION_BINDING_CHANGED")
            }
        }
    }

    fun requirePermission(binding: ScreenAuthorizationBinding, permission: ScreenPermission): ScreenLocalSession {
        val state = status(binding)
        val current = state.session ?: throw ScreenAuthorizationException(checkNotNull(state.denialCode))
        if (permission !in current.permissions) throw ScreenAuthorizationException("SCREEN_PERMISSION_REQUIRED")
        return current
    }

    /** 确认来自本地现有确认流程；请求正文中的 confirmation 永不参与授权。 */
    fun installConfirmation(binding: ScreenAuthorizationBinding, requestId: String, confirmation: UserConfirmation) {
        require(requestId.isNotBlank())
        val active = requirePermission(binding, ScreenPermission.CONTROL)
        synchronized(lock) {
            if (session?.sessionId != active.sessionId || clock() >= active.expiresAtEpochMs) {
                throw ScreenAuthorizationException("SCREEN_SESSION_EXPIRED")
            }
            require(confirmation.confirmedAtEpochMs <= clock())
            require(confirmation.expiresAtEpochMs > clock())
            require(confirmation.expiresAtEpochMs <= active.expiresAtEpochMs)
            confirmations.entries.removeAll { it.value.expiresAtEpochMs <= clock() }
            check(requestId in confirmations || confirmations.size < MAX_PENDING_CONFIRMATIONS) { "SCREEN_CONFIRMATION_LIMIT" }
            confirmations[requestId] = confirmation
        }
    }

    fun clearConfirmation(requestId: String) {
        synchronized(lock) { confirmations.remove(requestId) }
    }

    fun authorizeControl(
        binding: ScreenAuthorizationBinding,
        request: ControlAuthorizationRequest,
        currentWindow: ScreenTargetIdentity?,
    ): ControlAuthorization {
        val active = try { requirePermission(binding, ScreenPermission.CONTROL) }
        catch (_: ScreenAuthorizationException) { return ControlAuthorization.Denied(ControlDenialReason.NO_LOCAL_GRANT) }
        return synchronized(lock) {
            if (session?.sessionId != active.sessionId || clock() >= active.expiresAtEpochMs) {
                return@synchronized ControlAuthorization.Denied(ControlDenialReason.SESSION_NOT_ACTIVE)
            }
            val target = active.target
                ?: return@synchronized ControlAuthorization.Denied(ControlDenialReason.WINDOW_CHANGED)
            val localSession = ScreenControlSession(
                active.sessionId, target, active.startedAtEpochMs, active.expiresAtEpochMs, target.windowGeneration,
            )
            val localRequest = request.copy(
                confirmation = confirmations[request.requestId],
                screenSession = localSession,
            )
            controlAuthorizer.authorize(
                request = localRequest,
                grant = ControlGrant(true, setOf(ControlCapability.SCREEN_CONTROL), active.binding.revision),
                revision = binding.revision,
                activeSession = true,
                currentWindow = currentWindow,
                nowEpochMs = clock(),
            )
        }
    }

    fun revoke(reason: ScreenSessionEndReason = ScreenSessionEndReason.REVOKED) {
        synchronized(lock) {
            val existed = session != null
            session = null
            expiry?.cancel(false)
            expiry = null
            confirmations.clear()
            if (existed) notifyEnded(reason)
        }
    }

    private fun notifyEnded(reason: ScreenSessionEndReason) { runCatching { onSessionEnded(reason) } }

    override fun close() { revoke() }

    private fun scheduleExpiry(opened: ScreenLocalSession, delayMillis: Long) {
        expiry = EXPIRY_TIMER.schedule({
            synchronized(lock) {
                if (session?.sessionId != opened.sessionId) return@synchronized
                // ScheduledExecutorService measures elapsed time. A clock
                // adjustment must never extend the user's original TTL.
                session = null
                expiry = null
                confirmations.clear()
                notifyEnded(ScreenSessionEndReason.EXPIRED)
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val MAX_PENDING_CONFIRMATIONS = 256
        val EXPIRY_TIMER = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "oai-screen-session-expiry").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
