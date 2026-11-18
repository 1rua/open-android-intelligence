package com.openandroidintelligence.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 本机显示与偏好索引，不含凭据、网络会话或远端授权。 */
data class LocalProfile(
    val localProfileId: String,
    val serviceUrl: String,
    val displayName: String,
    val trustId: String,
    val avatarBase64: String? = null,
    val agentName: String? = null,
    val selectedEndpointId: String? = null,
) {
    init { require(localProfileId.isNotBlank()) { "LOCAL_PROFILE_INVALID" } }
}

/** 本机偏好归属坐标，和远端连接代次、身份密钥及认证状态分开。 */
data class LocalProfileBinding(val accountId: String, val installId: String) {
    init { require(accountId.isNotBlank() && installId.isNotBlank()) { "LOCAL_PROFILE_BINDING_INVALID" } }
}

class LocalProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("local-profile-preferences-v1", Context.MODE_PRIVATE)
    @Synchronized fun list(): List<LocalProfile> = records().map { row ->
        LocalProfile(row.getString("id"), row.getString("service"), row.getString("name"), row.getString("trust"),
            row.optional("avatar"), row.optional("agent"), row.optional("selected"))
    }
    fun find(id: String): LocalProfile? = list().firstOrNull { it.localProfileId == id }
    @Synchronized fun save(profile: LocalProfile, binding: LocalProfileBinding? = null) {
        val previous = records().firstOrNull { it.getString("id") == profile.localProfileId }
        val row = JSONObject().put("id", profile.localProfileId).put("service", profile.serviceUrl)
            .put("name", profile.displayName).put("trust", profile.trustId).put("avatar", profile.avatarBase64)
            .put("agent", profile.agentName).put("selected", profile.selectedEndpointId)
        val scope = binding ?: previous?.optional("account")?.let { LocalProfileBinding(it, previous.getString("install")) }
        scope?.let { row.put("account", it.accountId).put("install", it.installId) }
        write(records().filterNot { it.getString("id") == profile.localProfileId } + row)
    }
    @Synchronized fun delete(id: String) = write(records().filterNot { it.getString("id") == id })
    @Synchronized fun binding(id: String): LocalProfileBinding? = records().firstOrNull { it.getString("id") == id }?.let { row ->
        row.optional("account")?.let { LocalProfileBinding(it, row.getString("install")) }
    }
    fun saveAvatar(id: String, avatarBase64: String) = save(checkNotNull(find(id)) { "LOCAL_PROFILE_MISSING" }.copy(avatarBase64 = avatarBase64))
    fun saveSelectedEndpoint(id: String, endpoint: String?) = save(checkNotNull(find(id)) { "LOCAL_PROFILE_MISSING" }.copy(selectedEndpointId = endpoint))
    private fun records(): List<JSONObject> = JSONArray(preferences.getString("profiles", "[]")).let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun write(rows: List<JSONObject>) {
        check(preferences.edit().putString("profiles", JSONArray(rows).toString()).commit()) { "LOCAL_PROFILE_PERSISTENCE_FAILED" }
    }
    private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
