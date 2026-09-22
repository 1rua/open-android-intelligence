package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.plugin.pkg.AlpVerifier
import com.openandroidintelligence.plugin.pkg.InstalledPlugin
import com.openandroidintelligence.plugin.pkg.PackageLimits
import com.openandroidintelligence.plugin.pkg.PackageRejected
import com.openandroidintelligence.plugin.pkg.PluginInstaller
import java.io.File

/** 宿主侧对一次插件安装的明确拒绝（区别于契约校验码）。 */
class PluginInstallRefused(code: String) : IllegalArgumentException(code)

/**
 * 一个已安装插件的展示视图，全部字段来自磁盘上的真实文件。
 *
 * [pluginId] 等为 null 表示该目录存在但 manifest 不可解析——这是如实呈现的
 * 「未知包」，不是可以伪造出元数据的借口。
 */
data class InstalledPluginView(
    val directory: File,
    val pluginId: String?,
    val version: String?,
    val displayName: String?,
    val runtimeType: String?,
    /** 包内 ui 目录下的 .json 声明式贡献文件，按文件名排序。 */
    val declarationFiles: List<File>,
)

/**
 * 插件管理区域的数据源（app 拥有）。
 *
 * - 已安装列表：扫描 [installRoot]（`filesDir/plugins/`，即 [PluginInstaller]
 *   的安装根），逐目录读取 `manifest.json` 提取展示用元数据；
 * - 安装：`AlpVerifier.verify`（§5 形状 + Ed25519 签名 + 容器约束）通过后交
 *   `PluginInstaller` 原子落盘。校验失败抛 [PackageRejected]，拒绝码原样
 *   交给界面显示；同 ID 已存在时明确拒绝（更新流程未接入，不得静默覆盖）。
 *
 * 注意：宿主当前生产装配没有任何插件运行时，这里只负责「包内容真实存在」，
 * 启用/执行仍由平台内核的六项交集裁决。
 */
class PluginInstallStore(private val context: Context) {

    val installRoot: File get() = File(context.filesDir, "plugins")

    private val limits = PackageLimits(
        maxEntries = 64,
        maxSingleEntryBytes = 8 * 1024 * 1024,
        maxTotalUncompressedBytes = 32 * 1024 * 1024,
    )

    /** 扫描真实安装根。空目录（或不存在）返回空列表，绝不伪造条目。 */
    fun listInstalled(): List<InstalledPluginView> {
        val root = installRoot
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map(::readView)
            .sortedBy { it.pluginId ?: it.directory.name }
    }

    /**
     * 校验并安装一个 `.alp` 包字节。
     *
     * @throws PackageRejected 包未通过契约校验（消息即契约拒绝码）。
     * @throws PluginInstallRefused 宿主侧拒绝（同 ID 已存在）。
     */
    fun install(packageBytes: ByteArray): InstalledPluginView {
        val verifier = AlpVerifier(
            limits = limits,
            stagingRoot = File(installRoot, ".verify-staging").apply { mkdirs() },
        )
        val verified = verifier.verify(packageBytes)
        val pluginId = verified.identity.pluginId
        val destination = File(installRoot, pluginId)
        if (destination.exists()) {
            deleteRecursively(verified.stagedDirectory)
            throw PluginInstallRefused("ALREADY_INSTALLED:$pluginId")
        }
        // 校验器的暂存目录刻意不落保留条目（manifest.json 等）；宿主在提交前
        // 把已验证包里的 manifest 原字节并回暂存目录，使安装目录自含元数据，
        // 供设置面展示与内核后续使用——写入同一暂存目录保证安装仍是一次原子提交。
        extractManifestBytes(packageBytes)?.let { manifestBytes ->
            runCatching { File(verified.stagedDirectory, "manifest.json").writeBytes(manifestBytes) }
        }
        val installer = PluginInstaller(installRoot)
        val installed: InstalledPlugin = try {
            installer.install(verified, current = null, approvalGranted = false)
        } finally {
            // 校验暂存区在安装提交后即无用途；清理失败不影响安装结果。
            runCatching { deleteRecursively(File(installRoot, ".verify-staging")) }
        }
        return readView(installed.directory)
    }

    /** 从同一份已校验字节里取回 manifest.json 原文（与校验读到的完全一致）。 */
    private fun extractManifestBytes(packageBytes: ByteArray): ByteArray? = runCatching {
        val archive = java.nio.file.Files.createTempFile("alp-manifest-", ".zip").toFile()
        try {
            archive.writeBytes(packageBytes)
            java.util.zip.ZipFile(archive).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                zip.getInputStream(entry).use { stream -> stream.readBytes() }
            }
        } finally {
            archive.delete()
        }
    }.getOrNull()

    private fun readView(directory: File): InstalledPluginView {
        val manifest = File(directory, "manifest.json")
        val declarations = (File(directory, "ui").listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.name }
        if (!manifest.isFile) {
            return InstalledPluginView(
                directory = directory,
                pluginId = null,
                version = null,
                displayName = null,
                runtimeType = null,
                declarationFiles = declarations,
            )
        }
        return parseManifest(manifest.readText(), directory, declarations)
    }

    /** 展示用最小解析：只读字段，不承载安全语义（安全语义在内核/校验器）。 */
    private fun parseManifest(
        text: String,
        directory: File,
        declarations: List<File>,
    ): InstalledPluginView {
        val fields = runCatching {
            val root = org.json.JSONObject(text)
            val plugin = root.optJSONObject("plugin")
            ManifestFields(
                pluginId = plugin?.optString("id")?.takeIf { it.isNotBlank() },
                version = plugin?.optString("version")?.takeIf { it.isNotBlank() },
                displayName = plugin?.optString("name")?.takeIf { it.isNotBlank() },
                runtimeType = root.optJSONObject("runtime")?.optString("type")?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
        return InstalledPluginView(
            directory = directory,
            pluginId = fields?.pluginId,
            version = fields?.version,
            displayName = fields?.displayName,
            runtimeType = fields?.runtimeType,
            declarationFiles = declarations,
        )
    }

    private data class ManifestFields(
        val pluginId: String?,
        val version: String?,
        val displayName: String?,
        val runtimeType: String?,
    )

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        file.delete()
    }
}
