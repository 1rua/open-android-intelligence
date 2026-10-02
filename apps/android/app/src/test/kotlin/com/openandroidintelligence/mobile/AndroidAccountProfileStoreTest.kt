package com.openandroidintelligence.mobile

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.account.AccountProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidAccountProfileStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun profile(id: String) = AccountProfile(id, "http://gateway.example", id, "")

    @Test fun frameworkProfilesAndBindingsSurviveReopeningTheStore() {
        val store = AndroidAccountProfileStore(context)
        store.save(profile("alice"))
        val binding = AndroidAccountProfileStore.Binding("account_a", "device_a", "session_a", 1)
        store.saveBinding("alice", binding)
        val reopened = AndroidAccountProfileStore(context)
        assertEquals(profile("alice"), reopened.find("alice"))
        assertEquals(binding, reopened.binding("alice"))
    }

    @Test fun accountManagerStoresNoPasswordOrRefreshCredential() {
        val store = AndroidAccountProfileStore(context)
        store.save(profile("alice"))
        val manager = AccountManager.get(context)
        val account = Account("alice", AndroidAccountProfileStore.TYPE)
        assertNull(manager.getPassword(account))
        assertNull(manager.getUserData(account, "password"))
        assertNull(manager.getUserData(account, "refreshCredential"))
        assertNull(manager.peekAuthToken(account, "refresh"))
    }

    @Test fun deletingOneProfilePreservesAnotherProfilesBinding() {
        val store = AndroidAccountProfileStore(context)
        store.save(profile("alice")); store.save(profile("bob"))
        val binding = AndroidAccountProfileStore.Binding("account_b", "device_b", null, 1)
        store.saveBinding("bob", binding)
        store.delete("alice")
        assertNull(store.find("alice"))
        assertEquals(listOf(profile("bob")), store.list())
        assertEquals(binding, store.binding("bob"))
    }

    @Test fun updatingPublicMetadataDoesNotCreateAnotherAccount() {
        val store = AndroidAccountProfileStore(context)
        store.save(profile("alice"))
        val changed = profile("alice").copy(gatewayBaseUrl = "http://new.example")
        store.save(changed)
        assertEquals(listOf(changed), store.list())
    }

    @Test fun frameworkRemovalCannotBypassInAppCredentialCleanup() {
        val store = AndroidAccountProfileStore(context)
        store.save(profile("alice"))
        val controller = Robolectric.buildService(GatewayAccountAuthenticatorService::class.java).create()
        try {
            val account = Account("alice", AndroidAccountProfileStore.TYPE)
            assertFalse(controller.get().authenticator.getAccountRemovalAllowed(null, account)
                .getBoolean(AccountManager.KEY_BOOLEAN_RESULT))
            assertEquals(profile("alice"), store.find("alice"))
            store.delete("alice")
            assertNull(store.find("alice"))
        } finally { controller.destroy() }
    }
}
