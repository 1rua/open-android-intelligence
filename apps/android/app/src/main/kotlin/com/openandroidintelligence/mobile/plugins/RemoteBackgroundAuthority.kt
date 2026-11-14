package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.control.remote.ControlOrigin
import com.openandroidintelligence.control.remote.RemoteControlPrincipal
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityStore
import com.openandroidintelligence.gateway.remote.authproduction.AndroidRemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.authproduction.RemoteCredentialVault
import com.openandroidintelligence.gateway.remote.identity.RemoteDeviceId
import com.openandroidintelligence.gateway.remote.identity.RemoteIdentityScope
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import java.security.MessageDigest

/** 已认证控制者的稳定身份；不保存临时 connectionId，也不恢复一次性批准。 */
data class RemoteBackgroundSubject(val principal: RemoteControlPrincipal, val accountEpoch: Long, val localGrantRevision: Long) {
    init { require(accountEpoch >= 0 && localGrantRevision >= 0) { "REMOTE_BACKGROUND_EPOCH_INVALID" } }
    fun encode(): JsonValue = Json.of(mapOf(
        "origin" to principal.origin.value, "accountId" to principal.accountId,
        "controllerDeviceId" to principal.controllerDeviceId, "hostDeviceId" to principal.hostDeviceId,
        "controllerKeyFingerprint" to principal.controllerKeyFingerprint, "hostKeyFingerprint" to principal.hostKeyFingerprint,
        "accountEpoch" to accountEpoch, "localGrantRevision" to localGrantRevision,
    ))
    companion object {
        fun decode(value: JsonValue?): RemoteBackgroundSubject {
            val row = value as? JsonValue.JObject ?: error("REMOTE_BACKGROUND_SUBJECT_MISSING")
            fun text(key: String) = JsonFields.string(row, key) ?: error("REMOTE_BACKGROUND_SUBJECT_INVALID")
            return RemoteBackgroundSubject(RemoteControlPrincipal(ControlOrigin.fromCanonical(text("origin")), text("accountId"),
                text("controllerDeviceId"), text("hostDeviceId"), text("controllerKeyFingerprint"), text("hostKeyFingerprint")),
                JsonFields.long(row, "accountEpoch") ?: error("REMOTE_BACKGROUND_SUBJECT_INVALID"),
                JsonFields.long(row, "localGrantRevision") ?: error("REMOTE_BACKGROUND_SUBJECT_INVALID"))
        }
    }
}

sealed interface RemoteBackgroundAdmission {
    data object Ready : RemoteBackgroundAdmission
    /** 密钥暂时不可读时保留任务，由系统任务退避后再次复核。 */
    data class Waiting(val code: String) : RemoteBackgroundAdmission
    data class Denied(val code: String) : RemoteBackgroundAdmission
}

fun interface RemoteBackgroundAuthority {
    fun revalidate(subject: RemoteBackgroundSubject): RemoteBackgroundAdmission
}

/**
 * 定时查询只在手机执行；数据源是本机加密凭据库，不恢复网络或切换聊天目标。
 * 退出先持久撤销账号代次，因此旧角色记录即使尚未清理也不能继续执行。
 */
class ProductionRemoteBackgroundAuthority(
    private val vault: RemoteCredentialVault,
    private val store: RemoteIdentityStore,
) : RemoteBackgroundAuthority {
    constructor(context: Context) : this(RemoteCredentialVault(context), AndroidRemoteIdentityProtector())
    private constructor(vault: RemoteCredentialVault, protector: AndroidRemoteIdentityProtector) :
        this(vault, RemoteIdentityStore(vault, protector))

    override fun revalidate(subject: RemoteBackgroundSubject): RemoteBackgroundAdmission = try {
        val active = vault.activeAccount()
        if (active == null || active.origin != subject.principal.origin.value || active.accountId != subject.principal.accountId ||
            active.epoch != subject.accountEpoch) RemoteBackgroundAdmission.Denied("REMOTE_BACKGROUND_ACCOUNT_CHANGED")
        else {
            val host = store.load(RemoteIdentityScope(active.origin, active.accountId, RemoteRole.HOST))
            if (host == null || host.identity.deviceId.value != subject.principal.hostDeviceId ||
                fingerprint(host.identity.publicKey) != subject.principal.hostKeyFingerprint) {
                RemoteBackgroundAdmission.Denied("REMOTE_BACKGROUND_HOST_CHANGED")
            } else {
                val controller = host.peers.firstOrNull { it.peerRole == RemoteRole.CLIENT &&
                    it.peerDeviceId == RemoteDeviceId(subject.principal.controllerDeviceId) }
                if (controller == null || fingerprint(controller.publicKey) != subject.principal.controllerKeyFingerprint)
                    RemoteBackgroundAdmission.Denied("REMOTE_BACKGROUND_CONTROLLER_CHANGED")
                else { vault.requireCurrent(active); RemoteBackgroundAdmission.Ready }
            }
        }
    } catch (_: com.openandroidintelligence.gateway.remote.auth.RemoteIdentityLockedException) {
        RemoteBackgroundAdmission.Waiting("REMOTE_IDENTITY_LOCKED")
    } catch (_: Exception) {
        RemoteBackgroundAdmission.Denied("REMOTE_BACKGROUND_IDENTITY_UNAVAILABLE")
    }

    private fun fingerprint(key: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(key)
        .joinToString("") { "%02x".format(it) }
}
