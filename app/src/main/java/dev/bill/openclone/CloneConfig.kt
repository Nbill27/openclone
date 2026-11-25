package dev.bill.openclone

/**
 * What the user asked for on the "Atur Clone" screen.
 */
data class CloneConfig(
    val originalPackage: String,
    val originalLabel: String,
    val newPackage: String,
    val newLabel: String,
    /** Raw PNG/JPEG bytes picked from the gallery, or null to keep the original icon. */
    val iconBytes: ByteArray?,
    /**
     * Drop the `com.google.android.gms.permission.AD_ID` permission from the
     * clone's manifest. Without it the system reports a zeroed advertising ID
     * to the app on Android 13+ (targetSdk 33+), so the clone is not linked to
     * the original install by the ad identifier.
     */
    val stripAdId: Boolean = false,
)

data class CloneResult(
    val originalPackage: String,
    val newPackage: String,
    val newLabel: String,
    val outputFileName: String,
    val outputFileSize: Long,
    val hadSplits: Boolean,
    /** Compatibility findings detected while reading the source manifest. */
    val compatNotes: List<String> = emptyList(),
    /** Per-clone identity facts the user can verify after installing. */
    val identityNotes: List<String> = emptyList(),
)

/** One finished clone, remembered so it can be re-installed later. */
data class CloneRecord(
    val originalPackage: String,
    val originalLabel: String,
    val newPackage: String,
    val newLabel: String,
    val fileName: String,
    val createdAt: Long,
)

fun suggestClonePackage(originalPackage: String, existingPackages: Set<String>): String {
    var n = 1
    while (true) {
        val candidate = "$originalPackage.clone$n"
        if (candidate !in existingPackages) return candidate
        n++
    }
}

fun suggestCloneLabel(originalLabel: String): String = "$originalLabel (Clone 1)"

/** Android package names: letters, digits, underscore, dots, at least one dot, no leading digit per segment. */
fun isValidPackageName(name: String): Boolean {
    if (name.isBlank() || name.length > 200) return false
    if (!name.contains('.')) return false
    return name.split('.').all { segment ->
        segment.isNotEmpty() &&
            segment.all { it.isLetterOrDigit() || it == '_' } &&
            !segment.first().isDigit()
    }
}
