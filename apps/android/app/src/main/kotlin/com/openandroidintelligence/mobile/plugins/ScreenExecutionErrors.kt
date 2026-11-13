package com.openandroidintelligence.mobile.plugins

import com.openandroidintelligence.control.ScreenAuthorizationException
import com.openandroidintelligence.control.ScreenOperationException
import com.openandroidintelligence.gateway.device.DeviceRequestResult

/** Preserve safe typed error codes through WASM wrappers, never expose exception text. */
internal object ScreenExecutionErrors {
    fun result(failure: Throwable): DeviceRequestResult? {
        var current: Throwable? = failure
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        repeat(16) {
            val cause = current ?: return null
            if (!visited.add(cause)) return null
            val code = when (cause) {
                is ScreenAuthorizationException -> cause.code
                is ScreenOperationException -> cause.code
                else -> null
            }
            if (code != null) {
                if (!code.matches(Regex("[A-Z0-9_]{1,128}"))) return null
                return if (cause is ScreenAuthorizationException) DeviceRequestResult.Denied(mapOf("code" to code))
                    else if (code == "SCREEN_ACTION_OUTCOME_UNKNOWN") DeviceRequestResult.OutcomeUnknown
                    else DeviceRequestResult.Failed(mapOf("code" to code))
            }
            current = cause.cause
        }
        return null
    }
}
