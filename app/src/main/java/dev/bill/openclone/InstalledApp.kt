package dev.bill.openclone

import android.graphics.drawable.Drawable

/**
 * One app installed on the device, as shown in the "Pilih Aplikasi" list.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val icon: Drawable?,
    val isSystemApp: Boolean,
) {
    val splitCount: Int get() = splitSourceDirs.size
    val displayVersion: String get() = versionName ?: "?"
}
