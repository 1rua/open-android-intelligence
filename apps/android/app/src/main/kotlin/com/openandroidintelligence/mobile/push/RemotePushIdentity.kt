package com.openandroidintelligence.mobile.push

import android.content.Context
import com.openandroidintelligence.control.remote.ControlAccessBinding
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityStore
import com.openandroidintelligence.gateway.remote.authproduction.AndroidRemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.authproduction.RemoteCredentialVault
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.network.AuthenticatedRemotePeer
import java.security.MessageDigest

/** 只读本机持久身份，不创建第三个设备、不恢复或抢占 Client 网络连接。 */
class RemotePushIdentity(context: Context) {
    private val vault=RemoteCredentialVault(context)
    private val store=RemoteIdentityStore(vault,AndroidRemoteIdentityProtector())
    fun active(): RemoteActiveAccount? = vault.activeAccount()
    fun target(peer: AuthenticatedRemotePeer,backend: String,workspaceId: String,sessionId: String): RemotePushTarget {
        val active=active() ?: error("PUSH_ACCOUNT_REQUIRED")
        val scope=peer.scope
        check(peer.role == RemoteRole.CLIENT && scope.origin == active.origin && scope.accountId == active.accountId) { "PUSH_PEER_MISMATCH" }
        val host=store.load(RemoteIdentityScope(active.origin,active.accountId,RemoteRole.HOST)) ?: error("PUSH_PHONE_IDENTITY_REQUIRED")
        return RemotePushTarget(active.origin,active.accountId,active.epoch,scope.clientDeviceId,peer.localFingerprint,
            scope.hostDeviceId,peer.peerFingerprint,host.identity.deviceId.value,fingerprint(host.identity.publicKey),backend,workspaceId,sessionId)
            .also { check(isCurrent(it)) { "PUSH_AUTHORIZATION_CHANGED" } }
    }
    fun isCurrent(target: RemotePushTarget): Boolean = runCatching {
        val active=active() ?: return false
        if (active.origin != target.origin || active.accountId != target.accountId || active.epoch != target.accountEpoch) return false
        val client=store.load(RemoteIdentityScope(active.origin,active.accountId,RemoteRole.CLIENT)) ?: return false
        val host=store.load(RemoteIdentityScope(active.origin,active.accountId,RemoteRole.HOST)) ?: return false
        if (client.identity.deviceId.value != target.clientDeviceId || fingerprint(client.identity.publicKey) != target.clientKeyFingerprint ||
            host.identity.deviceId.value != target.phoneHostDeviceId || fingerprint(host.identity.publicKey) != target.phoneHostKeyFingerprint) return false
        val pin=client.peers.firstOrNull { it.peerRole == RemoteRole.HOST && it.peerDeviceId.value == target.hostDeviceId } ?: return false
        if (fingerprint(pin.publicKey) != target.hostKeyFingerprint) return false
        vault.requireCurrent(active);true
    }.getOrDefault(false)

    /** 这里只核对手机账号与Virtual Host；桌面收件主体须另经本机明确批准绑定完整目标。 */
    fun accepts(access: ControlAccessBinding,target: RemotePushTarget): Boolean = isCurrent(target) &&
        access.authorizationEpoch == target.accountEpoch && access.principal.origin.value == target.origin &&
        access.principal.accountId == target.accountId && access.principal.hostDeviceId == target.phoneHostDeviceId &&
        access.principal.hostKeyFingerprint == target.phoneHostKeyFingerprint

    private fun fingerprint(bytes: ByteArray)="sha256:"+MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
