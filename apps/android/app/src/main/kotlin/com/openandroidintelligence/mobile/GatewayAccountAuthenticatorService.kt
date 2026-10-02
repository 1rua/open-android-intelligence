package com.openandroidintelligence.mobile

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/** The system can manage profile metadata but cannot request our Keystore secrets. */
class GatewayAccountAuthenticatorService : Service() {
    internal val authenticator: AbstractAccountAuthenticator by lazy {
        object : AbstractAccountAuthenticator(this) {
            private fun unsupported() = Bundle().apply {
                putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
                putString(AccountManager.KEY_ERROR_MESSAGE, "Manage Gateway sessions in Open Android Intelligence")
            }
            override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?) = unsupported()
            override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle?) = unsupported()
            override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?) = unsupported()
            override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?) = unsupported()
            override fun getAuthTokenLabel(authTokenType: String?) = "Gateway session (in app only)"
            override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?) = unsupported()
            override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?) = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
            // Framework removal cannot run the Gateway revoke/Keystore cleanup.
            // In-app removal uses removeAccountExplicitly after that lifecycle.
            override fun getAccountRemovalAllowed(response: AccountAuthenticatorResponse?, account: Account?) = Bundle().apply {
                putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT) authenticator.iBinder else null
}
