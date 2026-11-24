package dev.bill.openclone

/**
 * Pure text rules for making a cloned APK coexist with its original.
 *
 * Package name alone is not enough: Android also requires unique declared
 * permissions, unique provider authorities, and component permissions that
 * point at those declared permissions. These helpers hold the naming rules
 * on their own so they can be unit tested without an APK or a device.
 */
internal object ManifestNaming {

    /**
     * Rewrites one `authorities` attribute value. Values are `;`-separated.
     *
     * - an authority equal to the old package becomes the new package
     * - one already under the old package gets the prefix swapped
     * - one already under the new package is left as is
     * - a bare or vendor authority (e.g. `com.facebook.appmanager`) is
     *   namespaced under the new package, so two clones never collide
     */
    fun renameAuthorities(value: String, oldPackage: String, newPackage: String): String {
        return value.split(';').joinToString(";") { raw ->
            val authority = raw.trim()
            when {
                authority.isEmpty() -> authority
                // Checked before the old prefix: the new package normally
                // starts with the old one ("a.b" -> "a.b.klon1"), so an
                // already renamed value would otherwise be renamed twice.
                authority == newPackage || authority.startsWith("$newPackage.") -> authority
                authority == oldPackage -> newPackage
                authority.startsWith("$oldPackage.") ->
                    newPackage + authority.removePrefix(oldPackage)
                else -> "$newPackage.$authority"
            }
        }
    }

    /** Rewrites `android:process` when it derives from the old package, else null. */
    fun renameProcess(value: String, oldPackage: String, newPackage: String): String? {
        // Already renamed, and idempotent: the new package usually starts
        // with the old one, so this must be tested first.
        if (value.startsWith("$newPackage:") || value.startsWith("$newPackage.")) return null
        return when {
            value.startsWith("$oldPackage:") ->
                newPackage + value.removePrefix(oldPackage)
            value.startsWith("$oldPackage.") ->
                newPackage + value.removePrefix(oldPackage)
            else -> null
        }
    }

    /**
     * Rewrites a permission name.
     *
     * @param mustBeOwned when true only names already under [oldPackage] are
     *   rewritten, so platform permissions such as `android.permission.CAMERA`
     *   are left untouched. When false (used for `<permission>` declarations),
     *   a bare name without any dot is also namespaced, because a declared
     *   permission cannot stay global.
     */
    fun renamePermissionName(
        value: String,
        oldPackage: String,
        newPackage: String,
        mustBeOwned: Boolean,
    ): String? {
        // Already renamed: the new package normally starts with the old one,
        // so this must be checked before the old-prefix case, or a second
        // pass would double the prefix.
        if (value == newPackage || value.startsWith("$newPackage.")) return null
        if (value == oldPackage) return newPackage
        if (value.startsWith("$oldPackage.")) return newPackage + value.removePrefix(oldPackage)
        if (mustBeOwned) return null
        return if (!value.contains('.')) "$newPackage.$value" else null
    }

    /**
     * True for top-level `META-INF/` signature files only. Sub-directories
     * such as `META-INF/services/` and the `META-INF/` Kotlin module files
     * must stay, so they are not treated as signatures.
     */
    fun isSignatureEntry(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val fileName = name.removePrefix("META-INF/")
        if (fileName.contains('/')) return false
        return fileName.equals("MANIFEST.MF", ignoreCase = true) ||
            fileName.endsWith(".SF", ignoreCase = true) ||
            fileName.endsWith(".RSA", ignoreCase = true) ||
            fileName.endsWith(".DSA", ignoreCase = true) ||
            fileName.endsWith(".EC", ignoreCase = true) ||
            fileName.startsWith("SIG-", ignoreCase = true)
    }
}
