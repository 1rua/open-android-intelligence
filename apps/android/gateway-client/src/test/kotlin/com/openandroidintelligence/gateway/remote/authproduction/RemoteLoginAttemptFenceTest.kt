package com.openandroidintelligence.gateway.remote.authproduction

import com.openandroidintelligence.gateway.remote.auth.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemoteLoginAttemptFenceTest {
    private class Persistence : RemoteIdentityPersistence {
        var bytes: ByteArray? = null
        var failClear = false
        override fun read(scopeKey: String) = bytes?.copyOf()
        override fun writeAtomic(scopeKey: String, value: ByteArray) { bytes = value.copyOf() }
        override fun clear(scopeKey: String) { check(!failClear) { "模拟加密仓库不可写" }; bytes = null }
    }
    @Test fun 加密票据清空失败仍通过独立持久撤销位阻断重启恢复() {
        val directory = Files.createTempDirectory("remote-login-fence").toFile()
        val persistence = Persistence(); val store = RemoteBrowserLoginStore(persistence) { 1000L }
        val fence = RemoteLoginAttemptFence(directory)
        fence.allowNewAttempt()
        val attempt = store.begin(RemoteOAuthProvider.GITHUB, 0)
        persistence.failClear = true
        fence.block()
        assertThrows(IllegalStateException::class.java) { store.cancel() }
        assertNotNull(store.pending())
        val restarted = RemoteLoginAttemptFence(directory)
        assertTrue(restarted.blocked())
        assertThrows(IllegalStateException::class.java) {
            check(!restarted.blocked())
            store.consume(attempt.returnTo + "&token=" + "t".repeat(32), 0)
        }
        persistence.failClear = false
        store.cancel(); restarted.allowNewAttempt()
        assertFalse(RemoteLoginAttemptFence(directory).blocked())
        assertNull(store.pending())
    }
    @Test fun 撤销标记不可读默认拒绝而不是恢复旧票据() {
        val directory = Files.createTempDirectory("remote-login-corrupt-fence").toFile()
        java.io.File(directory, "remote-login-revocation.bin").writeBytes(byteArrayOf())
        assertTrue(RemoteLoginAttemptFence(directory).blocked())
    }
}
