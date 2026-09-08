package com.shinevoice.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

data class InstallLaunchResult(
    val started: Boolean,
    val permissionRequired: Boolean = false,
)

/** Opens the Android package installer after all app-side checks pass. */
class ApkInstaller(private val context: Context) {
    fun canInstallPackages(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(): Result<InstallLaunchResult> = runCatching {
        if (canInstallPackages()) return@runCatching InstallLaunchResult(started = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                ("package:" + context.packageName).toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        InstallLaunchResult(started = false, permissionRequired = true)
    }

    fun openInstaller(apk: File): Result<InstallLaunchResult> = runCatching {
        val updatesDir = File(context.cacheDir, UpdateProtocol.UPDATE_CACHE_DIR).canonicalFile
        val canonicalApk = apk.canonicalFile
        require(canonicalApk.parentFile == updatesDir) { "安装包路径不在受限更新目录" }
        require(UpdateProtocol.isSafeApkName(canonicalApk.name) && canonicalApk.isFile) { "安装包不可安装" }
        if (!canInstallPackages()) return@runCatching requestInstallPermission().getOrThrow()

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            canonicalApk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        InstallLaunchResult(started = true)
    }
}
