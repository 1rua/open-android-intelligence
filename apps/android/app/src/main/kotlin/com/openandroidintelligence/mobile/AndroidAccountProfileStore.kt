package com.openandroidintelligence.mobile

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.gateway.account.AccountProfileStore

/** Android owns public profiles; passwords and refresh credentials stay in Keystore. */
class AndroidAccountProfileStore(context: Context) : AccountProfileStore {
    private val manager = AccountManager.get(context)
    data class Binding(val accountId: String, val deviceId: String, val sessionId: String?, val keyEncoding: Int)

    override fun list(): List<AccountProfile> = manager.getAccountsByType(TYPE).mapNotNull { account ->
        runCatching {
            AccountProfile(account.name, required(account, "gateway"), required(account, "username"), manager.getUserData(account, "tlsTrustId").orEmpty())
        }.getOrNull()
    }
    override fun find(localProfileId: String): AccountProfile? = list().find { it.localProfileId == localProfileId }

    override fun save(profile: AccountProfile) {
        val account = Account(profile.localProfileId, TYPE)
        val values = mapOf("gateway" to profile.gatewayBaseUrl, "username" to profile.username, "tlsTrustId" to profile.tlsTrustId)
        if (manager.getAccountsByType(TYPE).none { it == account }) {
            val data = Bundle().apply { values.forEach { (key, value) -> putString(key, value) } }
            check(manager.addAccountExplicitly(account, null, data)) { "PROFILE_PERSISTENCE_FAILED" }
        } else values.forEach { (key, value) -> manager.setUserData(account, key, value) }
    }

    fun saveBinding(profileId: String, binding: Binding) {
        check(find(profileId) != null) { "UNKNOWN_PROFILE" }
        val account = Account(profileId, TYPE)
        manager.setUserData(account, "accountId", binding.accountId)
        manager.setUserData(account, "deviceId", binding.deviceId)
        manager.setUserData(account, "sessionId", binding.sessionId)
        manager.setUserData(account, "keyEncoding", binding.keyEncoding.toString())
    }

    fun binding(profileId: String): Binding? {
        val account = Account(profileId, TYPE)
        return runCatching { Binding(required(account, "accountId"), required(account, "deviceId"), manager.getUserData(account, "sessionId"), required(account, "keyEncoding").toInt()) }.getOrNull()
    }

    override fun delete(localProfileId: String) {
        val account = Account(localProfileId, TYPE)
        if (manager.getAccountsByType(TYPE).any { it == account }) {
            check(manager.removeAccountExplicitly(account)) { "PROFILE_CLEANUP_FAILED" }
        }
    }

    private fun required(account: Account, key: String): String = manager.getUserData(account, key)?.takeIf { it.isNotBlank() } ?: error("PROFILE_INVALID")
    companion object { const val TYPE = "com.openandroidintelligence.gateway" }
}
