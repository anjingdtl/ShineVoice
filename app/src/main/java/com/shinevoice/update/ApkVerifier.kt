package com.shinevoice.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: String,
    val apkSha256: String,
)

/** Fail-closed APK hash, package, version and certificate verifier. */
class ApkVerifier(private val context: Context) {
    suspend fun verify(
        apk: File,
        expectedVersionCode: Long,
        expectedSha256: String,
        expectedPackageName: String = UpdateProtocol.EXPECTED_PACKAGE_NAME,
        expectedSignerSha256: String = UpdateProtocol.EXPECTED_SIGNER_SHA256,
    ): Result<ApkIdentity> = withContext(Dispatchers.IO) {
        runCatching {
            require(apk.isFile) { "安装包不存在" }
            val actualHash = sha256(apk)
            require(actualHash.equals(expectedSha256, ignoreCase = true)) { "安装包完整性校验失败" }
            val packageInfo = packageInfo(apk)
                ?: error("无法读取安装包信息")
            require(packageInfo.packageName == expectedPackageName) { "安装包包名不匹配" }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
            }
            require(versionCode == expectedVersionCode) { "安装包版本号不匹配" }
            val signatures = signatures(packageInfo)
            require(signatures.size == 1) { "安装包签名数量不符合要求" }
            val signer = sha256(signatures.single().toByteArray())
            require(signer.equals(expectedSignerSha256, ignoreCase = true)) { "安装包签名证书不匹配" }
            ApkIdentity(packageInfo.packageName, versionCode, signer, actualHash)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(apk: File) = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        )
    }

    @Suppress("DEPRECATION")
    private fun signatures(packageInfo: android.content.pm.PackageInfo): Array<Signature> =
        if (Build.VERSION.SDK_INT >= 28) {
            val signingInfo = packageInfo.signingInfo ?: error("安装包缺少签名信息")
            require(!signingInfo.hasMultipleSigners()) { "安装包使用多签名证书" }
            signingInfo.apkContentsSigners
        } else {
            packageInfo.signatures ?: emptyArray()
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
}
