package com.openandroidintelligence.tailnet.core

/**
 * Boundary for app-private encrypted no-backup state. A production Android
 * implementation supplies the encrypted keystore-backed delegate; this MVP
 * adapter deliberately keeps the dependency injectable and content-free.
 */
interface EncryptedNoBackupState {
    fun read(): ByteArray?
    fun write(value: ByteArray)
    fun clear()
}

/** Enrollment credentials have a shorter lifetime than the userspace node state. */
interface EnrollmentAuthKeyStore {
    fun clear()
}

class NoBackupTailnetStateStore(
    private val delegate: EncryptedNoBackupState,
    private val authKeys: EnrollmentAuthKeyStore = NoopEnrollmentAuthKeyStore,
) {
    fun restoreNodeState(): ByteArray? = delegate.read()?.copyOf()

    fun persistNodeState(nodeState: ByteArray) {
        require(nodeState.isNotEmpty()) { "node state must not be empty" }
        delegate.write(nodeState.copyOf())
    }

    /** Clear the enrollment credential while retaining state needed to reconnect. */
    fun clearAfterEnrollment() = authKeys.clear()

    fun clearNodeState() = delegate.clear()
}

private object NoopEnrollmentAuthKeyStore : EnrollmentAuthKeyStore {
    override fun clear() = Unit
}

class InMemoryEncryptedNoBackupState : EncryptedNoBackupState {
    private var value: ByteArray? = null

    override fun read(): ByteArray? = value?.copyOf()

    override fun write(value: ByteArray) {
        this.value = value.copyOf()
    }

    override fun clear() {
        value?.fill(0)
        value = null
    }
}
