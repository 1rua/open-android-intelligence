package com.openandroidintelligence.gateway.remote.protocol

import com.openandroidintelligence.gateway.schema.JsonValue

/** 只负责固定账号线格式；登录状态、账号作用域和秘密存储由身份层持有。 */
object RemoteAccountCodec {
    fun parseRegistration(value: JsonValue): JsonValue.JObject = schema(value, strict = true) {
        required("v", literalNumber(1))
        required("device", ::descriptor)
    }

    fun parseHostRegistrationCode(value: JsonValue): JsonValue.JObject = schema(value, strict = true) {
        required("v", literalNumber(1))
        required("code") {
            string()(it).also { checked ->
                if (!Regex("[A-Z0-9]{4}-[A-Z0-9]{4}").matches((checked as JsonValue.JString).value)) invalid()
            }
        }
        required("device") { descriptor(it, requiredRole = "host") }
    }

    fun parseRefresh(value: JsonValue): JsonValue.JObject = schema(value, strict = true) {
        required("deviceId", deviceId())
        required("refreshToken", token())
    }

    fun parseTokenPair(value: JsonValue): JsonValue.JObject = tokenPair(value, browserAccount = false)

    fun parseBrowserExchangeRequest(value: JsonValue): JsonValue.JObject = schema(value, strict = true) {
        required("v", literalNumber(1))
        required("device") { descriptor(it, requiredRole = "client", browser = true) }
    }

    fun parseBrowserExchangeResponse(value: JsonValue): JsonValue.JObject = tokenPair(value, browserAccount = true)

    private fun tokenPair(value: JsonValue, browserAccount: Boolean): JsonValue.JObject = schema(value, strict = true) {
        required("accessToken", token())
        required("accessTokenExpiresAt", integer(1))
        required("refreshToken", token())
        required("refreshTokenExpiresAt", integer(1))
        if (browserAccount) required("account", visibleString(254))
    }

    private fun descriptor(value: JsonValue, requiredRole: String? = null, browser: Boolean = false): JsonValue.JObject {
        val source = value as? JsonValue.JObject ?: invalid()
        val role = text(source, "role")
        if (role !in setOf("host", "client") || requiredRole != null && role != requiredRole) invalid()
        return schema(value, strict = true) {
            required("deviceId", deviceId())
            required("name", visibleString(128))
            required("role", oneOf(role))
            required("platform") {
                val platform = (string(1, 64)(it) as JsonValue.JString).value
                if (browser && platform != "browser" || !Regex("[a-z0-9][a-z0-9._-]*").matches(platform)) invalid()
                it
            }
            required("identityKey", identityKey())
            required("clientVersion", string(1, 64, utf8 = true))
            if (role == "host") optional("harnessVersion", string(1, 64, utf8 = true))
        }
    }

    private fun deviceId(): ValueRule = {
        val value = (it as? JsonValue.JString)?.value ?: invalid()
        if (!ulid.matches(value) && !uuid.matches(value)) invalid()
        it
    }

    private fun token(): ValueRule = string(16, 8 * 1024, utf8 = true)
    private fun visibleString(maximum: Int): ValueRule = {
        string(1, maximum, utf8 = true)(it).also { checked ->
            if ((checked as JsonValue.JString).value.isBlank()) invalid()
        }
    }

    private val ulid = Regex("[0-9A-HJKMNP-TV-Z]{26}", RegexOption.IGNORE_CASE)
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
}
