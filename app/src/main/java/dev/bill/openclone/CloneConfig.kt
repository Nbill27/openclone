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
)

data class CloneResult(
    val originalPackage: String,
    val newPackage: String,
    val newLabel: String,
    val outputFileName: String,
    val outputFileSize: Long,
    val hadSplits: Boolean,
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
        val candidate = "$originalPackage.klon$n"
        if (candidate !in existingPackages) return candidate
        n++
    }
}

fun suggestCloneLabel(originalLabel: String): String = "$originalLabel 2"

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
