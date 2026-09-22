package com.openandroidintelligence.notification.control

import android.content.Context
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 设置页可见的通知采集策略快照。
 *
 * [installed] 是装配标志：宿主进程装配了 `:notification-host` 的组合根才为
 * true。未装配时的唯一合法形态是 [uninstalled]——每个字段都如实陈述
 * 「不可用」，绝不假装已授权。这与 `NotificationAgentQueryGateway` 读到的
 * 策略权威快照同源（由 `:notification-host` 保证），授权不再有两套账本。
 */
data class NotificationPolicySnapshot(
    val installed: Boolean,
    val granted: Boolean,
    val packageIds: List<String>,
    val fieldAccess: NotificationFieldAccess,
    val mode: NotificationDeliveryMode,
    val revision: ULong,
) {
    companion object {
        /** 未装配时的诚实快照：deny-first 的每一位都是 false/默认/零。 */
        fun uninstalled(): NotificationPolicySnapshot = NotificationPolicySnapshot(
            installed = false,
            granted = false,
            packageIds = emptyList(),
            fieldAccess = NotificationFieldAccess.METADATA,
            mode = NotificationDeliveryMode.ON_DEMAND,
            revision = 0u,
        )
    }
}

/**
 * 一次策略变更请求。[expectedRevision] 是乐观并发控制：请求方所见快照的
 * revision 不再是当前值时，实现必须拒绝而不是覆盖。字段为 null 表示不变更。
 */
data class NotificationPolicyUpdate(
    val expectedRevision: ULong,
    val granted: Boolean? = null,
    val packageIds: List<String>? = null,
    val fieldAccess: NotificationFieldAccess? = null,
    val mode: NotificationDeliveryMode? = null,
)

/** 更新的显式结果：拒绝必须携带机器可读原因，不允许静默假成功。 */
sealed interface NotificationPolicyUpdateOutcome {
    data class Accepted(val snapshot: NotificationPolicySnapshot) : NotificationPolicyUpdateOutcome
    data class Rejected(val reason: String) : NotificationPolicyUpdateOutcome
}

/**
 * 通知采集策略端口。实现由 `:notification-host` 装配并桥接
 * `PersistentNotificationPolicyAuthority`；端口自身没有采集、持久化或权限语义。
 */
interface NotificationPolicyPort {
    fun snapshot(): NotificationPolicySnapshot

    /** 快照的响应式视图；实现至少在每次 update 被接受后发出新值。 */
    fun observe(): Flow<NotificationPolicySnapshot>

    fun update(request: NotificationPolicyUpdate): NotificationPolicyUpdateOutcome
}

/**
 * 系统通知监听绑定端口。
 *
 * 系统的「通知使用权」只能由用户在系统设置页显式授予（权限红线）：
 * 本端口只查询绑定状态、并跳转到系统设置页，绝不代替用户授予。
 */
interface NotificationBindingPort {
    fun isListenerBound(): Boolean

    /**
     * 打开系统「通知使用权」设置页。返回是否真正发起了跳转；
     * 未装配（没有可授权的监听器）时实现必须返回 false 且不发起跳转。
     */
    fun openSystemListenerSettings(context: Context): Boolean
}

/**
 * 进程内唯一的装配点，deny-first 默认与 `NotificationRuntimeFactoryRegistry`
 * 同构：`:notification-host` 的 ContentProvider 在 app 进程启动时
 * `install(...)`；任何未装配的读法都落在下面的诚实默认值上。
 */
object NotificationControlRegistry {
    @Volatile
    private var policyPort: NotificationPolicyPort = UninstalledNotificationPolicyPort

    @Volatile
    private var bindingPort: NotificationBindingPort = UninstalledNotificationBindingPort

    fun install(policy: NotificationPolicyPort, binding: NotificationBindingPort) {
        this.policyPort = policy
        this.bindingPort = binding
    }

    fun policyPort(): NotificationPolicyPort = policyPort

    fun bindingPort(): NotificationBindingPort = bindingPort

    fun reset() {
        policyPort = UninstalledNotificationPolicyPort
        bindingPort = UninstalledNotificationBindingPort
    }

    /** 未装配时的策略端口：诚实快照 + 显式拒绝。 */
    internal object UninstalledNotificationPolicyPort : NotificationPolicyPort {
        override fun snapshot(): NotificationPolicySnapshot = NotificationPolicySnapshot.uninstalled()

        override fun observe(): Flow<NotificationPolicySnapshot> = flowOf(snapshot())

        override fun update(request: NotificationPolicyUpdate): NotificationPolicyUpdateOutcome =
            NotificationPolicyUpdateOutcome.Rejected("NOT_INSTALLED")
    }

    /** 未装配时的绑定端口：没有监听器可绑定，也没有可授权的系统页。 */
    internal object UninstalledNotificationBindingPort : NotificationBindingPort {
        override fun isListenerBound(): Boolean = false

        override fun openSystemListenerSettings(context: Context): Boolean = false
    }
}
