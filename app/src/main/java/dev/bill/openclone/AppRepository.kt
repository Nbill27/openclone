package dev.bill.openclone

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock

/**
 * Reads the installed apps on this device.
 * System apps are included in the list but flagged, so the user can
 * decide for themselves. Split APKs are reported, not hidden.
 *
 * Two things keep this fast on phones with hundreds of apps:
 * - one bulk `getInstalledPackages` supplies every version name/code, instead
 *   of a `getPackageInfo` binder round-trip per package, and
 * - the finished list is cached for a few seconds, so coming back from the
 *   "Atur Clone" screen does not rescan the whole device.
 *
 * Icons are not part of this scan at all; see [AppIcons].
 */
class AppRepository(private val context: Context) {

    private val packageManager: PackageManager
        get() = context.packageManager

    /**
     * Cached for [CACHE_TTL_MS]. Returns the previous list while it is still
     * fresh. Call [invalidate] after installing or removing a package so the
     * next call rescans immediately.
     */
    fun listInstalledApps(): List<InstalledApp> = synchronized(LOCK) {
        val cached = cachedApps
        if (cached != null && SystemClock.elapsedRealtime() - cachedAtMs < CACHE_TTL_MS) {
            return@synchronized cached
        }
        val apps = scanInstalledApps()
        // Do not cache an empty result: that is what a failed scan looks like,
        // and pinning it for the whole TTL would show an empty list.
        if (apps.isNotEmpty()) {
            cachedApps = apps
            cachedAtMs = SystemClock.elapsedRealtime()
        }
        apps
    }

    @Suppress("DEPRECATION")
    private fun scanInstalledApps(): List<InstalledApp> {
        val versions = readVersions()
        val applications = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                packageManager.getInstalledApplications(0)
            }
        } catch (_: Exception) {
            emptyList()
        }
        return applications
            .mapNotNull { info ->
                // sourceDir is a platform type and can be null for a package
                // that vanished mid-scan; skip it rather than crash.
                info.sourceDir?.let { dir -> toInstalledApp(info, dir, versions[info.packageName]) }
            }
            .sortedWith(compareBy({ it.isSystemApp }, { it.lowerLabel }))
    }

    /** One bulk IPC for the version fields of every package. */
    @Suppress("DEPRECATION")
    private fun readVersions(): Map<String, Pair<String?, Long>> {
        val packages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getInstalledPackages(0)
            }
        } catch (_: Exception) {
            return emptyMap()
        }
        val versions = HashMap<String, Pair<String?, Long>>(packages.size)
        for (pkg in packages) {
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                pkg.versionCode.toLong()
            }
            versions[pkg.packageName] = pkg.versionName to code
        }
        return versions
    }

    private fun toInstalledApp(
        info: ApplicationInfo,
        sourceDir: String,
        version: Pair<String?, Long>?,
    ): InstalledApp = InstalledApp(
        packageName = info.packageName,
        label = try {
            info.loadLabel(packageManager).toString()
        } catch (_: Exception) {
            info.packageName
        },
        versionName = version?.first,
        versionCode = version?.second ?: 0L,
        sourceDir = sourceDir,
        splitSourceDirs = info.splitSourceDirs?.toList().orEmpty(),
        isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
    )

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

    /**
     * Package names only. Deliberately does not go through
     * [listInstalledApps]: that path resolves a label per package, which is a
     * resource lookup for a set of strings we throw away.
     */
    @Suppress("DEPRECATION")
    fun installedPackageNames(): Set<String> {
        return try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getInstalledPackages(0)
            }
            packages.mapNotNullTo(HashSet(packages.size)) { it.packageName }
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Single lookup for the "Atur Clone" screen, without building the whole list. */
    fun findInstalledApp(packageName: String): InstalledApp? {
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val version = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }.let { pkg ->
                pkg.versionName to
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pkg.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pkg.versionCode.toLong()
                    }
            }
        } catch (_: Exception) {
            null
        }
        val sourceDir = info.sourceDir ?: return null
        return toInstalledApp(info, sourceDir, version)
    }

    companion object {
        /** Short enough to notice a new install, long enough to skip a rescan. */
        private const val CACHE_TTL_MS = 15_000L

        private val LOCK = Any()
        private var cachedApps: List<InstalledApp>? = null
        private var cachedAtMs = 0L

        /**
         * Forgets the package list and cached icons. Call it after a package
         * was installed or removed so the next screen shows the real state.
         */
        fun invalidate() {
            synchronized(LOCK) {
                cachedApps = null
                cachedAtMs = 0L
            }
            AppIcons.clear()
        }
    }
}
