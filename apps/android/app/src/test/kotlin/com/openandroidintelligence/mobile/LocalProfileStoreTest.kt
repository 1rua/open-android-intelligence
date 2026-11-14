package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 本机显示偏好可重开、互相隔离，迟到修改不能重建已移除资料。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalProfileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    @Before fun clear() { context.getSharedPreferences("local-profile-preferences-v1", 0).edit().clear().commit() }
    @Test fun `本机资料与归属重开后保留且互不覆盖`() {
        val store = LocalProfileStore(context)
        val alice = LocalProfile("alice", "https://remote.example", "甲", "")
        val bob = LocalProfile("bob", "https://remote.example", "乙", "")
        store.save(alice, LocalProfileBinding("account-a", "install"))
        store.save(bob, LocalProfileBinding("account-b", "install"))
        store.saveAvatar("alice", "bounded-image")
        store.saveSelectedEndpoint("alice", "endpoint-a")
        val reopened = LocalProfileStore(context)
        assertEquals("bounded-image", reopened.find("alice")?.avatarBase64)
        assertEquals("endpoint-a", reopened.find("alice")?.selectedEndpointId)
        assertEquals(LocalProfileBinding("account-a", "install"), reopened.binding("alice"))
        assertEquals(bob, reopened.find("bob"))
        reopened.delete("alice")
        assertEquals(listOf(bob), LocalProfileStore(context).list())
        assertThrows(IllegalStateException::class.java) { reopened.saveAvatar("alice", "late-image") }
    }
    @Test fun `本机资料不包含认证会话或口令字段`() {
        LocalProfileStore(context).save(LocalProfile("local", "https://remote.example", "显示名", ""), LocalProfileBinding("account", "install"))
        val encoded = context.getSharedPreferences("local-profile-preferences-v1", 0).getString("profiles", "")!!
        for (secret in listOf("password", "accessToken", "refreshCredential", "sessionId", "deviceKey")) assertFalse(encoded.contains(secret))
        assertTrue(android.accounts.AccountManager.get(context).getAccountsByType("com.openandroidintelligence.gateway").isEmpty())
    }
}
