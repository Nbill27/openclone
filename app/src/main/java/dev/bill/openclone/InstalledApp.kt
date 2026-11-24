package dev.bill.openclone

/**
 * One app installed on the device, as shown in the "Pilih Aplikasi" list.
 *
 * Icons are deliberately not part of this model: loading a drawable for every
 * installed package during the scan is what made the list feel slow on phones
 * with 200+ apps. [AppIcons] loads them lazily per visible row instead.
 *
 * [lowerLabel] and [searchKey] are cached per instance because the search field
 * filters the list on every keystroke; lowercasing a few hundred labels on each
 * character is pure allocation for no benefit.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val isSystemApp: Boolean,
) {
    val splitCount: Int get() = splitSourceDirs.size
    val displayVersion: String get() = versionName ?: "?"

    /** Lowercased label, also used as the sort key. */
    val lowerLabel: String by lazy(LazyThreadSafetyMode.NONE) { label.lowercase() }

    /** Lowercased "label package" haystack for the search field. */
    val searchKey: String
        by lazy(LazyThreadSafetyMode.NONE) { "$lowerLabel\u0000${packageName.lowercase()}" }
}
