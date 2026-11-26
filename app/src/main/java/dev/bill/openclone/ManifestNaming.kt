package dev.bill.openclone

/**
 * Pure text rules for making a cloned APK coexist with its original.
 */
internal object ManifestNaming {

    /**
     * Derives a 2-character alphanumeric tag starting with a letter (`c1`..`c9`
     * for `.clone1`..`.clone9`, or `k` + base36 char for custom package names)
     * used for zero-offset same-length vendor identifier rewriting.
     */
    fun cloneTag2(newPackage: String, upperCase: Boolean = false): String {
        val lastSeg = newPackage.substringAfterLast('.')
        val digits = lastSeg.removePrefix("clone").removePrefix("klon")
        val raw = if (digits.length == 1 && digits[0] in '1'..'9') {
            "c$digits"
        } else {
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
            val h = (newPackage.hashCode() and 0x7FFFFFFF) % alphabet.length
            "k${alphabet[h]}"
        }
        return if (upperCase) raw.uppercase() else raw
    }

    /**
     * Mutates a dotted vendor identifier (not starting with `oldPackage`) to an
     * identical-byte-length clone-unique identifier when its final segment has
     * at least 3 ASCII identifier characters (e.g.
     * `com.facebook.auth.login` -> `com.facebook.auth.logc1`,
     * `com.facebook.permission.prod.FB_APP_COMMUNICATION` -> `...FB_APP_COMMUNICATIC1`).
     *
     * Keeping the exact ASCII byte length allows [DexPatcher] to patch all
     * matching literals across 20+ DEX shards in-place in ~10 ms without
     * shifting a single offset in the DEX file. When two distinct identifiers
     * only differ in their last 2 characters, [usedTargets] prevents collisions
     * by selecting the next available 2-char tag of identical length and case.
     */
    fun mutateVendorSameLength(
        value: String,
        newPackage: String,
        usedTargets: Set<String> = emptySet(),
    ): String? {
        if (value.length < 8) return null
        val lastDot = value.lastIndexOf('.')
        if (lastDot <= 0 || value.length - 1 - lastDot < 3) return null
        if (!value.all { it.code in 0x21..0x7E }) return null
        val tail1 = value[value.length - 2]
        val tail2 = value[value.length - 1]
        if (!isAsciiAlnumOrUnderscore(tail1) || !isAsciiAlnumOrUnderscore(tail2)) return null
        val isUpper = tail1.isUpperCase() || tail2.isUpperCase()
        val prefix = value.substring(0, value.length - 2)

        val primaryTag = cloneTag2(newPackage, upperCase = isUpper)
        val primary = prefix + primaryTag
        if (primary != value && primary !in usedTargets) {
            return primary
        }

        val secondChars = if (isUpper) "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0"
        else "123456789abcdefghijklmnopqrstuvwxyz0"
        val firstChars = if (isUpper) "DEFGHIJKLMNOPQRSTUVWXYZABC"
        else "defghijklmnopqrstuvwxyzabc"
        for (c1 in firstChars) {
            for (c2 in secondChars) {
                val alt = "$prefix$c1$c2"
                if (alt != value && alt !in usedTargets) {
                    return alt
                }
            }
        }
        return null
    }

    /**
     * Rewrites one `authorities` attribute value. Values are `;`-separated.
     */
    fun renameAuthorities(
        value: String,
        oldPackage: String,
        newPackage: String,
        sameLengthVendor: Boolean = false,
        usedTargets: MutableSet<String>? = null,
    ): String {
        return value.split(';').joinToString(";") { raw ->
            val authority = raw.trim()
            val rewritten = when {
                authority.isEmpty() -> authority
                authority == newPackage || authority.startsWith("$newPackage.") -> authority
                authority == oldPackage -> newPackage
                authority.startsWith("$oldPackage.") ->
                    newPackage + authority.removePrefix(oldPackage)
                sameLengthVendor ->
                    mutateVendorSameLength(
                        value = authority,
                        newPackage = newPackage,
                        usedTargets = usedTargets ?: emptySet(),
                    ) ?: "$newPackage.$authority"
                else -> "$newPackage.$authority"
            }
            if (rewritten.isNotEmpty()) {
                usedTargets?.add(rewritten)
            }
            rewritten
        }
    }

    /** Rewrites `android:process` when it derives from the old package, else null. */
    fun renameProcess(value: String, oldPackage: String, newPackage: String): String? {
        if (value.isEmpty() || value.startsWith(":")) return null
        if (value == newPackage ||
            value.startsWith("$newPackage:") ||
            value.startsWith("$newPackage.")
        ) {
            return null
        }
        return when {
            value == oldPackage -> newPackage
            value.startsWith("$oldPackage:") ->
                newPackage + value.removePrefix(oldPackage)
            value.startsWith("$oldPackage.") ->
                newPackage + value.removePrefix(oldPackage)
            else -> null
        }
    }

    /**
     * Rewrites non-empty `android:taskAffinity` so a clone never shares its
     * foreground task stack with the original app in Recent Apps, while strictly
     * obeying AOSP `ComponentParseUtils.buildTaskAffinityName` /
     * `FrameworkParsingPackageUtils.validateName` rules:
     *
     * - `""` disables affinity and must stay untouched.
     * - `":foo"` (leading colon) is ALREADY scoped by Android at install time to
     *   `"$newPackage:foo"` (`buildCompoundName`), so prepending `"$newPackage."`
     *   would produce `"$newPackage.:foo"` and trigger
     *   `INSTALL_PARSE_FAILED_MANIFEST_MALFORMED` ("bad character ':'")!
     * - `"$oldPackage:foo"` becomes `"$newPackage:foo"` (or `":foo"` if needed).
     * - Any dotted or plain name is prefixed with `"$newPackage."` ONLY when the
     *   resulting string satisfies `validateName(..., requireSeparator = true)`;
     *   otherwise it is converted to a valid `:oc_<sanitized>` compound name.
     */
    fun renameTaskAffinity(value: String, oldPackage: String, newPackage: String): String? {
        if (value.isEmpty()) return null
        if (value.startsWith(":")) {
            val sub = value.substring(1)
            return if (isValidValidateName(sub, requireSeparator = false)) {
                null
            } else {
                ":oc_${sanitizeSegment(sub)}"
            }
        }
        if (value == newPackage ||
            value.startsWith("$newPackage.") ||
            value.startsWith("$newPackage:")
        ) {
            return null
        }
        if (value == "system") return null
        if (value == oldPackage) return newPackage
        if (value.startsWith("$oldPackage.")) {
            val candidate = newPackage + value.removePrefix(oldPackage)
            return if (isValidValidateName(candidate, requireSeparator = true)) {
                candidate
            } else {
                ":oc_${sanitizeSegment(value.removePrefix("$oldPackage."))}"
            }
        }
        if (value.startsWith("$oldPackage:")) {
            val sub = value.removePrefix("$oldPackage:")
            return if (isValidValidateName(sub, requireSeparator = false)) {
                ":$sub"
            } else {
                ":oc_${sanitizeSegment(sub)}"
            }
        }
        val dottedCandidate = "$newPackage.$value"
        return if (isValidValidateName(dottedCandidate, requireSeparator = true)) {
            dottedCandidate
        } else {
            ":oc_${sanitizeSegment(value)}"
        }
    }

    /**
     * Rewrites a permission name while ensuring valid Android `validateName` syntax.
     */
    fun renamePermissionName(
        value: String,
        oldPackage: String,
        newPackage: String,
        mustBeOwned: Boolean,
        declaredPermissions: Map<String, String> = emptyMap(),
        sameLengthVendor: Boolean = false,
        usedTargets: Set<String> = emptySet(),
    ): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        declaredPermissions[trimmed]?.let { mapped ->
            return if (mapped == trimmed) null else mapped
        }
        if (trimmed == newPackage || trimmed.startsWith("$newPackage.")) return null
        if (trimmed == oldPackage) return newPackage
        if (trimmed.startsWith("$oldPackage.")) {
            return newPackage + trimmed.removePrefix(oldPackage)
        }
        if (mustBeOwned) return null
        if (trimmed.startsWith("android.permission.")) return null
        if (sameLengthVendor) {
            mutateVendorSameLength(
                value = trimmed,
                newPackage = newPackage,
                usedTargets = usedTargets,
            )?.let { return it }
        }
        val prefixed = "$newPackage.$trimmed"
        return if (isValidValidateName(prefixed, requireSeparator = true)) {
            prefixed
        } else {
            "$newPackage.p_${sanitizeSegment(trimmed)}"
        }
    }

    /**
     * Exact mirror of AOSP `FrameworkParsingPackageUtils.validateName` character
     * state machine (`[a-zA-Z]` at start of each `.`-separated segment, followed
     * by `[a-zA-Z0-9_]*`).
     */
    internal fun isValidValidateName(name: String, requireSeparator: Boolean): Boolean {
        if (name.isEmpty()) return false
        var hasSep = false
        var front = true
        for (c in name) {
            if (c in 'a'..'z' || c in 'A'..'Z') {
                front = false
                continue
            }
            if (!front && (c in '0'..'9' || c == '_')) {
                continue
            }
            if (c == '.') {
                if (front) return false
                hasSep = true
                front = true
                continue
            }
            return false
        }
        if (front) return false
        return hasSep || !requireSeparator
    }

    private fun sanitizeSegment(raw: String): String {
        val sb = StringBuilder(raw.length.coerceAtLeast(1))
        for (c in raw) {
            sb.append(
                if (isAsciiAlnumOrUnderscore(c)) c else '_',
            )
        }
        return if (sb.isEmpty()) "main" else sb.toString()
    }

    private fun isAsciiAlnumOrUnderscore(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    /**
     * True for top-level `META-INF/` signature files and Play Source Stamp
     * digests.
     */
    fun isSignatureEntry(name: String): Boolean {
        if (name.equals("stamp-cert-sha256", ignoreCase = true)) return true
        if (!name.startsWith("META-INF/")) return false
        val fileName = name.removePrefix("META-INF/")
        if (fileName.contains('/')) return false
        return fileName.equals("MANIFEST.MF", ignoreCase = true) ||
            fileName.equals("stamp-cert-sha256", ignoreCase = true) ||
            fileName.endsWith(".SF", ignoreCase = true) ||
            fileName.endsWith(".RSA", ignoreCase = true) ||
            fileName.endsWith(".DSA", ignoreCase = true) ||
            fileName.endsWith(".EC", ignoreCase = true) ||
            fileName.startsWith("SIG-", ignoreCase = true)
    }
}
