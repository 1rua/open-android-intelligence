package com.openandroidintelligence.gateway.remote.auth

import org.junit.Assert.*
import org.junit.Test

class RemoteBrowserLoginTest {
    private class Memory : RemoteIdentityPersistence {
        val records = mutableMapOf<String, ByteArray>()
        var failClear = false
        override fun read(scopeKey: String) = records[scopeKey]?.copyOf()
        override fun writeAtomic(scopeKey: String, value: ByteArray) { records[scopeKey] = value.copyOf() }
        override fun clear(scopeKey: String) { if (failClear) error("写盘失败"); records.remove(scopeKey) }
    }
    @Test fun 本次随机票据可跨进程恢复并只消费一次() {
        val memory = Memory(); val first = RemoteBrowserLoginStore(memory) { 1000L }
        val attempt = first.begin(RemoteOAuthProvider.GITHUB, 7)
        val restored = RemoteBrowserLoginStore(memory) { 1001L }
        assertEquals(attempt.state, restored.pending()!!.state)
        val uri = attempt.returnTo + "&token=" + "t".repeat(32)
        assertEquals("t".repeat(32), restored.consume(uri, 7).token)
        assertThrows(IllegalStateException::class.java) { restored.consume(uri, 7) }
        assertFalse(attempt.toString().contains(attempt.state))
    }
    @Test fun 取消过期代次变更和新登录均拒绝旧回调() {
        var now = 1000L; val memory = Memory(); val store = RemoteBrowserLoginStore(memory) { now }
        val attempt = store.begin(RemoteOAuthProvider.GOOGLE, 1); val uri = attempt.returnTo + "&token=" + "t".repeat(32)
        assertThrows(IllegalStateException::class.java) { store.consume(uri, 2) }
        store.cancel()
        assertThrows(IllegalStateException::class.java) { store.consume(uri, 1) }
        store.begin(RemoteOAuthProvider.GOOGLE, 1)
        assertThrows(IllegalStateException::class.java) { store.consume(uri, 1) }
        now += 600001
        assertNull(store.pending())
        assertThrows(IllegalStateException::class.java) { store.consume(uri, 1) }
    }
    @Test fun 恶意回调不能消耗合法票据且错误回调允许重新开始() {
        val memory = Memory(); val store = RemoteBrowserLoginStore(memory) { 1000L }
        val attempt = store.begin(RemoteOAuthProvider.ZHIHU, 0)
        val token = "&token=" + "t".repeat(32)
        for (uri in listOf(
            attempt.returnTo.replace("dshremote:", "https:") + token,
            attempt.returnTo.replace("//oauth", "//attacker@oauth") + token,
            attempt.returnTo.replace("//oauth", "//oauth/path") + token,
            attempt.returnTo.replace("provider=zhihu", "provider=github") + token,
            attempt.returnTo + "&state=other" + token,
            attempt.returnTo + token + "&error=oauth_failed",
            attempt.returnTo + token + "#fragment",
            attempt.returnTo + token + "&unexpected=value",
        )) {
            assertThrows(IllegalStateException::class.java) { store.consume(uri, 0) }
            assertEquals(attempt.state, store.pending()!!.state)
        }
        assertEquals("oauth_failed", store.consume(attempt.returnTo + "&error=oauth_failed", 0).error)
        assertNotEquals(attempt.state, store.begin(RemoteOAuthProvider.ZHIHU, 0).state)
    }
    @Test fun 消费清空写盘失败不返回token() {
        val memory = Memory(); val store = RemoteBrowserLoginStore(memory) { 1000L }
        val attempt = store.begin(RemoteOAuthProvider.GITHUB, 0); memory.failClear = true
        assertThrows(IllegalStateException::class.java) { store.consume(attempt.returnTo + "&token=" + "t".repeat(32), 0) }
    }
    @Test fun 浏览器地址只使用官方发现入口并拒绝跨域及编码越界() {
        val attempt = RemoteBrowserAttempt(RemoteOAuthProvider.GITHUB, "s".repeat(43), 10000, 0)
        assertTrue(RemoteBrowserLoginStore.authorizeUrl("https://dsh.r2049.cn", "/api/v1/auth/oauth/github/start", attempt)
            .startsWith("https://dsh.r2049.cn/api/v1/auth/oauth/github/start?return_to="))
        for (path in listOf("//evil.example/auth", "https://evil.example/auth", "/api/v1/auth/../evil", "/api/v1/auth/%2e%2e/evil",
            "/api/v1/auth/start?return_to=evil", "/api/v1/auth/start#evil")) {
            assertThrows(Exception::class.java) { RemoteBrowserLoginStore.authorizeUrl("https://dsh.r2049.cn", path, attempt) }
        }
    }
}
