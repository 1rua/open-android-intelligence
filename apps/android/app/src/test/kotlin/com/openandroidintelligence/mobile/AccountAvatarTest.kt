package com.openandroidintelligence.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.gateway.account.AccountProfile
import java.io.File
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountAvatarTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun chosenAvatarIsBoundedAndSurvivesRemovalOfTheOriginalPhotoAndStoreReopening() {
        val original = File(context.cacheDir, "large-avatar.png")
        val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        original.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()
        val bytes = loadAccountAvatar(context, Uri.fromFile(original))
        assertTrue(bytes.size <= ACCOUNT_AVATAR_MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(256, bounds.outWidth)
        assertEquals(128, bounds.outHeight)
        val store = AndroidAccountProfileStore(context)
        store.save(AccountProfile("alice", "http://gateway.example", "alice", "",
            avatarBase64 = Base64.getEncoder().encodeToString(bytes), agentName = "Hermes"))
        assertTrue(original.delete())
        val restored = AndroidAccountProfileStore(context).find("alice")!!
        assertNotNull(decodeAccountAvatar(restored.avatarBase64))
        assertEquals("Hermes", restored.agentName)
    }

    @Test fun avatarMetadataRemainsIsolatedFromOtherAccountsAndTheirPairingBindings() {
        val store = AndroidAccountProfileStore(context)
        val first = AccountProfile("alice", "http://gateway.example", "alice", "", avatarBase64 = "first-avatar")
        val second = AccountProfile("bob", "http://gateway.example", "bob", "", avatarBase64 = "second-avatar")
        val binding = AndroidAccountProfileStore.Binding("account_bob", "device_bob", "session_bob", 1)
        store.save(first)
        store.save(second, binding)
        store.saveAvatar("alice", "replacement")
        assertEquals("replacement", store.find("alice")?.avatarBase64)
        assertEquals(second, store.find("bob"))
        assertEquals(binding, store.binding("bob"))
        store.delete("alice")
        assertNull(store.find("alice"))
        assertEquals(second, AndroidAccountProfileStore(context).find("bob"))
    }

    @Test fun aLateAvatarEditCannotRecreateARemovedAccount() {
        val store = AndroidAccountProfileStore(context)
        store.save(AccountProfile("alice", "http://gateway.example", "alice", ""))
        store.delete("alice")
        assertThrows(IllegalStateException::class.java) { store.saveAvatar("alice", "late-avatar") }
        assertTrue(store.list().isEmpty())
    }

    @Test fun invalidOrOversizedSavedImagesFallBackToTheAccountInitial() {
        assertNull(decodeAccountAvatar(null))
        assertNull(decodeAccountAvatar("not base64"))
        assertNull(decodeAccountAvatar("a".repeat(ACCOUNT_AVATAR_MAX_BYTES * 2)))
        val source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().apply { source.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        source.recycle()
        assertNull(decodeAccountAvatar(Base64.getEncoder().encodeToString(bytes)))
    }
}
