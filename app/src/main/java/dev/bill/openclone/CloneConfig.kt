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
    /**
     * Patch hardcoded provider authorities, content:// URIs, custom permissions,
     * and account authenticator types in DEX string pools, and extract native
     * libraries so complex apps (e.g. Facebook) coexist cleanly with the original.
     */
    val compatMode: Boolean = true,
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

const val MAX_BATCH_CLONES = 20

data class BatchCloneSpec(
    val cloneNumber: Int,
    val newLabel: String,
    val newPackage: String,
)

fun nextCloneNumber(originalPackage: String, existingPackages: Set<String>): Int {
    var n = 1
    while ("$originalPackage.clone$n" in existingPackages) {
        n++
    }
    return n
}

fun suggestClonePackage(originalPackage: String, existingPackages: Set<String>): String {
    val n = nextCloneNumber(originalPackage, existingPackages)
    return "$originalPackage.clone$n"
}

fun suggestCloneLabel(originalLabel: String, cloneNumber: Int = 1): String =
    "$originalLabel (Clone ${cloneNumber.coerceAtLeast(1)})"

private val DEFAULT_CLONE_LABEL_SUFFIX = Regex("""\s*\(Clone\s+(\d+)\)\s*$""", RegexOption.IGNORE_CASE)
private val PACKAGE_CLONE_SUFFIX = Regex("""\.clone(\d+)$""", RegexOption.IGNORE_CASE)

/**
 * Extracts a positive clone index from `com.foo.cloneN` or `App (Clone N)`, or
 * falls back to [fallback].
 */
fun extractCloneNumber(packageName: String, label: String = "", fallback: Int = 1): Int {
    PACKAGE_CLONE_SUFFIX.find(packageName.trim())
        ?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?.takeIf { it >= 1 }
        ?.let { return it }
    DEFAULT_CLONE_LABEL_SUFFIX.find(label.trim())
        ?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?.takeIf { it >= 1 }
        ?.let { return it }
    return fallback.coerceAtLeast(1)
}

/**
 * Builds `1..MAX_BATCH_CLONES` non-colliding clone targets.
 *
 * - When `count == 1`, honors the user's exact [customLabelInput] and [customPackageInput].
 * - When `count > 1`, generates sequential non-colliding `.cloneN` package names
 *   and matching numbered labels (`App (Clone N)` for the default base label, or
 *   `CustomBase N` if the user entered a custom base name).
 */
fun buildBatchCloneSpecs(
    originalPackage: String,
    originalLabel: String,
    customLabelInput: String,
    customPackageInput: String,
    count: Int,
    existingPackages: Set<String>,
): List<BatchCloneSpec> {
    val total = count.coerceIn(1, MAX_BATCH_CLONES)
    if (total == 1) {
        val label = customLabelInput.trim().ifEmpty {
            suggestCloneLabel(originalLabel, nextCloneNumber(originalPackage, existingPackages))
        }
        val pkg = customPackageInput.trim().ifEmpty {
            suggestClonePackage(originalPackage, existingPackages)
        }
        val num = extractCloneNumber(
            packageName = pkg,
            label = label,
            fallback = nextCloneNumber(originalPackage, existingPackages),
        )
        return listOf(BatchCloneSpec(cloneNumber = num, newLabel = label, newPackage = pkg))
    }

    val strippedBase = customLabelInput.trim()
        .replace(DEFAULT_CLONE_LABEL_SUFFIX, "")
        .trim()
        .ifEmpty { originalLabel.trim().ifEmpty { "Clone" } }
    val useStandardFormat = strippedBase.equals(originalLabel.trim(), ignoreCase = true)

    val reserved = HashSet<String>(existingPackages.size + total)
    reserved.addAll(existingPackages)
    reserved.add(originalPackage)

    val results = ArrayList<BatchCloneSpec>(total)
    var candidateNum = extractCloneNumber(
        packageName = customPackageInput,
        label = customLabelInput,
        fallback = 1,
    )
    // Start at 1 when default so missing lower slots (or sequential slots) are filled cleanly.
    if (customPackageInput.trim() == suggestClonePackage(originalPackage, existingPackages)) {
        candidateNum = nextCloneNumber(originalPackage, reserved)
    }

    while (results.size < total) {
        val pkg = "$originalPackage.clone$candidateNum"
        if (pkg !in reserved) {
            reserved += pkg
            val label = if (useStandardFormat) {
                suggestCloneLabel(strippedBase, candidateNum)
            } else {
                "$strippedBase $candidateNum"
            }
            results += BatchCloneSpec(
                cloneNumber = candidateNum,
                newLabel = label,
                newPackage = pkg,
            )
        }
        candidateNum++
    }
    return results
}

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
