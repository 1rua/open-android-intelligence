package com.openandroidintelligence.plugin.pkg

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * A fully installed plugin, reachable only through the live pointer.
 *
 * The security declaration travels with the installed version because the
 * installer cannot compare widening without knowing what is already there, and
 * the runtime needs the same surface to compute effective capabilities.
 */
data class InstalledPlugin(
    val identity: PluginIdentity,
    val version: SemVer,
    val security: SecurityDeclaration,
    val directory: File,
    /** The previous verified version, kept so rollback has something real to restore. */
    val previous: InstalledPlugin?,
) {
    val previousVersionDirectory: File? get() = previous?.directory
}

/**
 * Installs a verified package atomically.
 *
 * Contract requirement: an interrupted install must leave either the previous
 * verified version or `installed-disabled`, never a partial new version. The
 * single atomic step is the pointer swap, so a crash before it simply means the
 * new directory is never referenced.
 */
class PluginInstaller(
    private val installRoot: File,
    private val updatePolicy: PluginUpdatePolicy = PluginUpdatePolicy(),
    private val commitHook: (String) -> Unit = {},
    private val directorySync: (File) -> Unit = DirectoryDurability::sync,
) {
    init { recoverInterruptedCommits() }

    @Synchronized fun install(
        verified: VerifiedPluginPackage,
        current: InstalledPlugin?,
        approvalGranted: Boolean = false,
    ): InstalledPlugin {
        val identity = verified.identity
        if (current != null) {
            val decision = updatePolicy.classify(
                current = InstalledSurface(
                    authorKey = current.identity.authorKeyFingerprint,
                    version = current.identity.version,
                    surface = current.security.surface,
                ),
                candidate = CandidateSurface(
                    authorKey = identity.authorKeyFingerprint,
                    version = identity.version,
                    surface = verified.security.surface,
                ),
            )
            when (decision) {
                is UpdateDecision.Reject -> throw PackageRejected(decision.reason)
                is UpdateDecision.RequireApproval ->
                    if (!approvalGranted) throw PackageRejected("APPROVAL_REQUIRED:${decision.reasons.first()}")
                UpdateDecision.AutoApply -> Unit
            }
        }

        val destination = File(installRoot, sanitize(identity.pluginId))
        val staged = createStagingDirectory(identity)
        val committed = try {
            copyVerified(verified, staged)
            fsyncDirectory(staged)
            commitAtomically(destination, staged, identity.pluginId)
        } catch (cause: Exception) {
            staged.deleteRecursively()
            recoverInterruptedCommits()
            throw cause
        }
        // `current` keeps pointing at `.previous/<id>` after the commit, which is
        // exactly where the old version was just moved to.
        val retainedPrevious = current?.let {
            if (retainedDirectory(identity.pluginId).exists()) {
                it.copy(directory = retainedDirectory(identity.pluginId))
            } else {
                null
            }
        }
        return InstalledPlugin(
            identity = identity,
            version = verified.version,
            security = verified.security,
            directory = committed,
            previous = retainedPrevious,
        )
    }

    /**
     * Rolls back to the version kept from the previous install.
     *
     * Rollback is only ever to a previously verified version; a package that
     * was never installed cannot be rolled back to.
     */
    @Synchronized fun rollback(current: InstalledPlugin): InstalledPlugin {
        val previous = current.previous
            ?: throw PackageRejected("NO_PREVIOUS_VERSION")
        val retained = retainedDirectory(current.identity.pluginId)
        if (!retained.exists()) throw PackageRejected("PREVIOUS_VERSION_MISSING")

        val destination = File(installRoot, sanitize(current.identity.pluginId))
        val staged = createStagingDirectory(previous.identity)
        try {
            copyInto(retained, staged)
            staged.walkBottomUp().filter { it.isDirectory }.forEach(::fsyncDirectory)
            commitAtomically(destination, staged, current.identity.pluginId)
        } catch (cause: Exception) {
            staged.deleteRecursively()
            recoverInterruptedCommits()
            throw cause
        }
        return previous.copy(directory = destination, previous = null)
    }

    /**
     * The retained version lives under `.previous/`, never at the live path: the
     * live directory is about to be replaced, and a rollback target that shares
     * a path with it would be destroyed by the very commit that retains it.
     */
    private fun retainedDirectory(pluginId: String): File =
        File(File(installRoot, ".previous"), sanitize(pluginId))

    private fun commitAtomically(destination: File, staged: File, pluginId: String): File {
        installRoot.mkdirs()
        File(installRoot, ".previous").mkdirs()
        if (destination.exists()) {
            val retained = retainedDirectory(pluginId)
            deleteRecursively(retained)
            Files.move(destination.toPath(), retained.toPath(), StandardCopyOption.ATOMIC_MOVE)
            fsyncDirectory(retained.parentFile!!)
            fsyncDirectory(installRoot)
            commitHook("previous-retained")
        }
        // The pointer swap: the only step that makes the new version visible.
        Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        fsyncDirectory(staged.parentFile!!)
        fsyncDirectory(installRoot)
        commitHook("candidate-visible")
        return destination
    }

    /** A crash between the two directory renames always restores a complete verified version. */
    fun recoverInterruptedCommits() {
        val previous = File(installRoot, ".previous")
        for (retained in previous.listFiles().orEmpty()) {
            if (!retained.isDirectory) continue
            val live = File(installRoot, retained.name)
            if (!live.exists()) {
                Files.move(retained.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE)
                fsyncDirectory(previous)
                fsyncDirectory(installRoot)
            }
        }
    }

    private fun copyVerified(verified: VerifiedPluginPackage, target: File) {
        val source = verified.stagedDirectory
        val actual = source.walkTopDown().filter { it != source }.onEach {
            if (Files.isSymbolicLink(it.toPath())) throw PackageRejected("STAGING_SYMLINK")
        }.filter { it.isFile }.map { it.relativeTo(source).invariantSeparatorsPath }.toSet()
        if (actual != verified.verifiedFiles.keys) throw PackageRejected("STAGING_FILE_SET_CHANGED")
        for ((path, expected) in verified.verifiedFiles) {
            val file = File(source, path)
            val destination = File(target, path)
            destination.parentFile!!.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > expected.size) throw PackageRejected("STAGING_SIZE_CHANGED:$path")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (size != expected.size || hash != expected.sha256) throw PackageRejected("STAGING_DIGEST_CHANGED:$path")
            syncFile(destination)
        }
        target.walkBottomUp().filter { it.isDirectory }.forEach(::fsyncDirectory)
    }

    private fun copyInto(source: File, target: File) {
        target.mkdirs()
        source.walkTopDown().forEach { file ->
            if (file == source) return@forEach
            val relative = file.relativeTo(source).path
            val destination = File(target, relative)
            if (file.isDirectory) {
                destination.mkdirs()
            } else {
                destination.parentFile?.mkdirs()
                Files.copy(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                syncFile(destination)
            }
        }
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { stream ->
            stream.fd.sync()
        }
    }

    private fun fsyncDirectory(directory: File) {
        directorySync(directory)
    }

    private fun createStagingDirectory(identity: PluginIdentity): File {
        val base = File(installRoot, ".staging")
        base.mkdirs()
        return Files.createTempDirectory(base.toPath(), "${sanitize(identity.pluginId)}-").toFile()
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }

    /**
     * Plugin IDs are reverse-domain and cannot contain separators, but the
     * install root is built from external input, so the mapping is made total.
     */
    private fun sanitize(pluginId: String): String =
        pluginId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
