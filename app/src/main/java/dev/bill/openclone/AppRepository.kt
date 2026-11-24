package dev.bill.openclone

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Reads the installed apps on this device.
 * System apps are included in the list but flagged, so the user can
 * decide for themselves. Split APKs are reported, not hidden.
 */
class AppRepository(private val packageManager: PackageManager) {

    @Suppress("DEPRECATION")
    fun listInstalledApps(): List<InstalledApp> {
        @Suppress("DEPRECATION")
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }
        return apps
            .filter { it.sourceDir != null }
            .map { info ->
                val label = try {
                    info.loadLabel(packageManager).toString()
                } catch (_: Exception) {
                    info.packageName
                }
                val (versionName, versionCode) = try {
                    val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(
                            info.packageName,
                            PackageManager.PackageInfoFlags.of(0),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo(info.packageName, 0)
                    }
                    pkgInfo.versionName to
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            pkgInfo.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            pkgInfo.versionCode.toLong()
                        }
                } catch (_: Exception) {
                    null to 0L
                }
                InstalledApp(
                    packageName = info.packageName,
                    label = label,
                    versionName = versionName,
                    versionCode = versionCode,
                    sourceDir = info.sourceDir,
                    splitSourceDirs = info.splitSourceDirs?.toList().orEmpty(),
                    icon = try {
                        info.loadIcon(packageManager)
                    } catch (_: Exception) {
                        null
                    },
                    isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedWith(compareBy({ it.isSystemApp }, { it.label.lowercase() }))
    }

    fun isInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun installedPackageNames(): Set<String> {
        return try {
            listInstalledApps().map { it.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
