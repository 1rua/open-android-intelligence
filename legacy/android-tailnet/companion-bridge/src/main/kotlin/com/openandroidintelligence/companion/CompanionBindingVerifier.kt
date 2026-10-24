package com.openandroidintelligence.companion

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

/** 抛出当 Companion 身份或版本与插件声明不符时。 */
class CompanionRejected(message: String) : SecurityException(message)

/**
 * 插件声明中绑定的 Companion 属性。
 * 对应 `docs/contracts/device-plugin-package-v1.md` §5.3。
 */
data class CompanionDeclaration(
    val packageName: String,
    val certificateSha256: String,
    val minVersionCode: Long,
    val ipcContract: String = "open-android-intelligence-companion-v1",
)

/** 已通过 PackageManager 真机校验的 Companion 实例。 */
data class VerifiedCompanion(
    val packageName: String,
    val versionCode: Long,
    val certificateSha256: String,
    val uid: Int,
)

/** 安装包信息模型，解耦 PackageManager 便于单测。 */
data class InstalledPackageInfo(
    val packageName: String,
    val versionCode: Long,
    val certificateSha256: String,
    val uid: Int,
)

/** 安装包信息提供者接口。 */
interface PackageInfoProvider {
    fun getPackageInfo(packageName: String): InstalledPackageInfo?
}

/** 供单测使用的假包信息提供者。 */
class FakePackageInfoProvider(
    private val installedPackages: Map<String, InstalledPackageInfo>,
) : PackageInfoProvider {
    override fun getPackageInfo(packageName: String): InstalledPackageInfo? =
        installedPackages[packageName]
}

/** 基于真实 Android PackageManager 的包信息提供者。 */
class AndroidPackageInfoProvider(private val context: Context) : PackageInfoProvider {
    override fun getPackageInfo(packageName: String): InstalledPackageInfo? {
        val pm = context.packageManager
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }

        val certSha256 = extractCertificateSha256(pkgInfo)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
        val uid = pkgInfo.applicationInfo?.uid ?: -1

        return InstalledPackageInfo(
            packageName = packageName,
            versionCode = versionCode,
            certificateSha256 = certSha256,
            uid = uid,
        )
    }

    private fun extractCertificateSha256(pkgInfo: PackageInfo): String {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
        if (signatures.isNullOrEmpty()) {
            return ""
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val certBytes = signatures[0].toByteArray()
        val hash = digest.digest(certBytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 校验 Companion 的真实 APK 签名与包属性。
 *
 * 核心原则：
 * 1. 宿主必须从 PackageManager 读取真实安装信息，不得信任自报信息；
 * 2. 包名、签名证书 SHA-256 和最低版本必须严格一致；
 * 3. 任何不匹配均立即以 SecurityException 失败关闭。
 */
class CompanionBindingVerifier(
    private val packageInfoProvider: PackageInfoProvider,
) {
    fun verify(declaration: CompanionDeclaration): VerifiedCompanion {
        val installed = packageInfoProvider.getPackageInfo(declaration.packageName)
            ?: throw CompanionRejected("PACKAGE_NOT_FOUND:${declaration.packageName}")

        val expectedCert = declaration.certificateSha256.lowercase(Locale.US)
        val actualCert = installed.certificateSha256.lowercase(Locale.US)
        if (expectedCert != actualCert) {
            throw CompanionRejected(
                "CERTIFICATE_MISMATCH:expected=$expectedCert,actual=$actualCert",
            )
        }

        if (installed.versionCode < declaration.minVersionCode) {
            throw CompanionRejected(
                "VERSION_TOO_LOW:min=${declaration.minVersionCode},actual=${installed.versionCode}",
            )
        }

        return VerifiedCompanion(
            packageName = installed.packageName,
            versionCode = installed.versionCode,
            certificateSha256 = installed.certificateSha256,
            uid = installed.uid,
        )
    }

    fun verify(
        packageName: String,
        certificateSha256: String,
        minVersionCode: Long,
    ): VerifiedCompanion {
        return verify(
            CompanionDeclaration(
                packageName = packageName,
                certificateSha256 = certificateSha256,
                minVersionCode = minVersionCode,
            )
        )
    }
}

