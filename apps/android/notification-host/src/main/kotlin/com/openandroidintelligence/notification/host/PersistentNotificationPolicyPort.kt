package com.openandroidintelligence.notification.host

import com.openandroidintelligence.core.model.NotificationCollectionPolicyV1
import com.openandroidintelligence.core.model.PolicyRevisionRace
import com.openandroidintelligence.core.model.sortNotificationPackageIds
import com.openandroidintelligence.notification.control.NotificationPolicyPort
import com.openandroidintelligence.notification.control.NotificationPolicySnapshot
import com.openandroidintelligence.notification.control.NotificationPolicyUpdate
import com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome
import com.openandroidintelligence.policy.NotificationAuthoritySnapshot
import com.openandroidintelligence.policy.PersistentNotificationPolicyAuthority
import com.openandroidintelligence.policy.PolicyStateCorrupted
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * `NotificationPolicyPort` 的权威实现：快照与更新全部落在同一个
 * [PersistentNotificationPolicyAuthority] 上——
 * snapshot().granted 就是 NotificationAgentQueryGateway 读的那个授权位，
 * 设置页写入的每一笔都进同一个持久化权威，授权不再有两套账本。
 *
 * revision 语义：与权威的 authorizationRevision 一致——每次被接受的更新
 * （无论改的是授权、白名单、字段访问还是投递模式）都会使其单调前进。
 */
class PersistentNotificationPolicyPort(
    private val authority: PersistentNotificationPolicyAuthority,
) : NotificationPolicyPort {

    override fun snapshot(): NotificationPolicySnapshot = authority.snapshot().toPortSnapshot()

    override fun observe(): Flow<NotificationPolicySnapshot> = callbackFlow {
        trySend(authority.snapshot().toPortSnapshot())
        val registration = authority.addListener { state -> trySend(state.toPortSnapshot()) }
        awaitClose { registration.close() }
    }

    override fun update(request: NotificationPolicyUpdate): NotificationPolicyUpdateOutcome {
        val current = authority.snapshot()
        if (request.expectedRevision != current.authorizationRevision) {
            return NotificationPolicyUpdateOutcome.Rejected(REVISION_STALE)
        }
        val nextPolicy = try {
            nextPolicy(current.policy, request)
        } catch (failure: IllegalArgumentException) {
            return NotificationPolicyUpdateOutcome.Rejected(INVALID_PACKAGE_IDS)
        }
        return try {
            authority.localController().apply(
                nextPolicy,
                authorizationRevision = request.expectedRevision + 1uL,
                granted = request.granted ?: current.granted,
                deliveryMode = request.mode ?: current.deliveryMode,
            )
            NotificationPolicyUpdateOutcome.Accepted(authority.snapshot().toPortSnapshot())
        } catch (race: PolicyRevisionRace) {
            // 并发更新赢了 revision 竞争：明确拒绝，不静默覆盖。
            NotificationPolicyUpdateOutcome.Rejected(REVISION_STALE)
        } catch (corrupted: PolicyStateCorrupted) {
            // 持久化证据损坏时权威保持 deny-first；更新同样被拒绝。
            NotificationPolicyUpdateOutcome.Rejected(LOCAL_POLICY_CORRUPTED)
        }
    }

    /**
     * 只有策略内容真的变化时才推进 policyRevision：保持采集器
     * captureRevision 与权威策略的一致性；授权/投递模式的变化只推进
     * authorizationRevision（权威的修订约束在 applyLocal 内校验）。
     */
    private fun nextPolicy(
        current: NotificationCollectionPolicyV1,
        request: NotificationPolicyUpdate,
    ): NotificationCollectionPolicyV1 {
        val packageIds = request.packageIds?.let(::sortedPackageIds) ?: current.packageIds
        val fieldAccess = request.fieldAccess ?: current.fieldAccess
        val contentChanged = packageIds != current.packageIds || fieldAccess != current.fieldAccess
        return if (contentChanged) {
            current.copy(
                packageIds = packageIds,
                fieldAccess = fieldAccess,
                policyRevision = current.policyRevision + 1uL,
            )
        } else {
            current
        }
    }

    /** 包名白名单按 Unicode 码点重排；空白/重复包名在这里被拒绝为 INVALID_PACKAGE_IDS。 */
    private fun sortedPackageIds(values: List<String>): List<String> = sortNotificationPackageIds(values)

    private fun NotificationAuthoritySnapshot.toPortSnapshot(): NotificationPolicySnapshot =
        NotificationPolicySnapshot(
            installed = true,
            granted = granted,
            packageIds = policy.packageIds.toList(),
            fieldAccess = policy.fieldAccess,
            mode = deliveryMode,
            revision = authorizationRevision,
        )

    private companion object {
        const val REVISION_STALE = "REVISION_STALE"
        const val INVALID_PACKAGE_IDS = "INVALID_PACKAGE_IDS"
        const val LOCAL_POLICY_CORRUPTED = "LOCAL_POLICY_CORRUPTED"
    }
}
