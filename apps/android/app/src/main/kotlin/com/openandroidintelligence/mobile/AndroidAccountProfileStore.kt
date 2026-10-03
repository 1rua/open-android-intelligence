package com.openandroidintelligence.mobile

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.gateway.account.AccountProfileStore
import org.json.JSONObject

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

    override fun save(profile: AccountProfile) = saveProfile(profile, null)

    /** A new framework account is created with its complete binding in one Bundle. */
    fun save(profile: AccountProfile, binding: Binding) = saveProfile(profile, binding)

    private fun saveProfile(profile: AccountProfile, binding: Binding?) {
        val account = Account(profile.localProfileId, TYPE)
        val values = mutableMapOf("gateway" to profile.gatewayBaseUrl, "username" to profile.username, "tlsTrustId" to profile.tlsTrustId)
        binding?.let { values["binding"] = encode(it) }
        if (manager.getAccountsByType(TYPE).none { it == account }) {
            val data = Bundle().apply { values.forEach { (key, value) -> putString(key, value) } }
            check(manager.addAccountExplicitly(account, null, data)) { "PROFILE_PERSISTENCE_FAILED" }
        } else values.forEach { (key, value) -> manager.setUserData(account, key, value) }
    }

    fun saveBinding(profileId: String, binding: Binding) {
        check(find(profileId) != null) { "UNKNOWN_PROFILE" }
        val account = Account(profileId, TYPE)
        manager.setUserData(account, "binding", encode(binding))
    }

    fun binding(profileId: String): Binding? {
        val account = Account(profileId, TYPE)
        manager.getUserData(account, "binding")?.let { encoded ->
            return runCatching {
                val data = JSONObject(encoded)
                Binding(data.getString("accountId").also { check(it.isNotBlank()) },
                    data.getString("deviceId").also { check(it.isNotBlank()) },
                    if (data.isNull("sessionId")) null else data.getString("sessionId"), data.getInt("keyEncoding"))
            }.getOrNull()
        }
        return runCatching { Binding(required(account, "accountId"), required(account, "deviceId"), manager.getUserData(account, "sessionId"), required(account, "keyEncoding").toInt()) }.getOrNull()
    }

    fun accountId(profileId: String): String? {
        val account = Account(profileId, TYPE)
        return binding(profileId)?.accountId ?: manager.getUserData(account, "accountId")?.takeIf { it.isNotBlank() }
    }

    private fun encode(binding: Binding) = JSONObject().apply {
        put("accountId", binding.accountId); put("deviceId", binding.deviceId)
        put("sessionId", binding.sessionId ?: JSONObject.NULL); put("keyEncoding", binding.keyEncoding)
    }.toString()

    override fun delete(localProfileId: String) {
        val account = Account(localProfileId, TYPE)
        if (manager.getAccountsByType(TYPE).any { it == account }) {
            check(manager.removeAccountExplicitly(account)) { "PROFILE_CLEANUP_FAILED" }
        }
    }

    private fun required(account: Account, key: String): String = manager.getUserData(account, key)?.takeIf { it.isNotBlank() } ?: error("PROFILE_INVALID")
    companion object { const val TYPE = "com.openandroidintelligence.gateway" }
}
