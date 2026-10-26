package com.openandroidintelligence.device.primitives

import com.openandroidintelligence.control.*
import org.junit.Assert.*
import org.junit.Test

class ScreenImageStoreTest {
    private val owner = ScreenAuthorizationBinding("a", "p", ControlRevision(1u, 1u, 1u, 1u))
    @Test fun expired_and_wrong_owner_handles_never_release_bytes_and_release_is_idempotent() {
        var now = 1_000L
        val store = ScreenImageStore { now }
        val image = store.put(owner, "session", byteArrayOf(1,2,3), 1, 1, 100_000)
        val id = image["imageId"] as String
        assertEquals(61_000L, image["expiresAt"])
        assertThrows(ScreenOperationException::class.java) { store.read(owner.copy(accountId = "b"), "session", id, 0) }
        assertThrows(ScreenOperationException::class.java) { store.release(owner, "other", id) }
        assertThrows(ScreenOperationException::class.java) { store.read(owner, "session", id, 1) }
        now = 61_000
        assertThrows(ScreenOperationException::class.java) { store.read(owner, "session", id, 0) }
        assertEquals(true, store.release(owner, "session", id)["released"])
        assertEquals(true, store.release(owner, "session", id)["released"])
    }

    @Test fun copy_ownership_and_image_count_are_enforced_without_eviction_of_a_live_handle() {
        val store = ScreenImageStore { 1_000 }
        val bytes = byteArrayOf(1,2,3)
        val id = store.put(owner, "s", bytes, 1, 1, 100_000)["imageId"] as String
        bytes.fill(0)
        assertEquals("AQID", store.read(owner, "s", id, 0)["data"])
        store.put(owner, "s", bytes, 1, 1, 100_000)
        assertThrows(ScreenOperationException::class.java) { store.put(owner, "s", bytes, 1, 1, 100_000) }
        assertEquals("AQID", store.read(owner, "s", id, 0)["data"])
        store.clear()
        assertThrows(ScreenOperationException::class.java) { store.read(owner, "s", id, 0) }
    }

    @Test fun the_expiry_timer_discards_pixels_even_if_the_wall_clock_moves_back() {
        var now = 1_000L
        val store = ScreenImageStore { now }
        val id = store.put(owner, "s", byteArrayOf(1,2,3), 1, 1, 1_020)["imageId"] as String
        now = 900 // Read-time wall-clock expiry alone would leave this image accessible.
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            try { store.read(owner, "s", id, 0) }
            catch (expired: ScreenOperationException) { assertEquals("SCREEN_IMAGE_UNAVAILABLE", expired.code); return }
            Thread.sleep(5)
        }
        fail("the original elapsed-time deadline must clear the image")
    }
}
