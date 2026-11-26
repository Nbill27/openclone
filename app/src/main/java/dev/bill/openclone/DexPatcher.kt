package dev.bill.openclone

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Ultra-fast, zero-AST binary `.dex` string-pool patcher.
 *
 * Why binary in-place instead of `com.reandroid.dex.model.DexFile`:
 * Full AST parsing of a single 10 MB 65k-method DEX allocates millions of
 * instruction objects, taking 15–40s per DEX shard and causing severe GC
 * stalls or OutOfMemoryError on multi-DEX apps like Facebook (20+ DEX files).
 *
 * By keeping vendor replacement identifiers exact-byte-length ASCII, we binary-
 * search `string_ids` (`O(log N)`, ~16 comparisons per rule) directly in the
 * raw byte buffer, verify UTF-16 neighbor sort order (`s[i-1] < new < s[i+1]`),
 * overwrite the ASCII bytes in-place without moving a single offset in the
 * DEX file, and recompute the SHA-1 signature + Adler32 checksum in ~10 ms.
 */
internal object DexPatcher {

    private const val HEADER_SIZE = 0x70
    private const val ENDIAN_CONSTANT = 0x12345678

    data class SameLengthRule(
        val oldAscii: String,
        val newAscii: String,
        val oldBytes: ByteArray,
        val newBytes: ByteArray,
    )

    data class Rules(
        val exactRules: List<SameLengthRule>,
        val contentPrefixRules: List<SameLengthRule>,
    ) {
        val isEmpty: Boolean
            get() = exactRules.isEmpty() && contentPrefixRules.isEmpty()
    }

    fun buildRules(
        renamedAuthorities: Map<String, String>,
        renamedPermissions: Map<String, String>,
        renamedAccountTypes: Map<String, String>,
    ): Rules {
        val exactMap = LinkedHashMap<String, SameLengthRule>()
        val prefixList = ArrayList<SameLengthRule>()

        fun addExact(oldVal: String, newVal: String) {
            if (oldVal.isEmpty() || oldVal == newVal) return
            if (!isPureAscii(oldVal) || !isPureAscii(newVal)) return
            val ob = oldVal.toByteArray(StandardCharsets.US_ASCII)
            val nb = newVal.toByteArray(StandardCharsets.US_ASCII)
            if (ob.size != nb.size) return
            exactMap[oldVal] = SameLengthRule(oldVal, newVal, ob, nb)
        }

        for ((oldAuth, newAuth) in renamedAuthorities) {
            if (oldAuth.isBlank() || oldAuth == newAuth || oldAuth.length != newAuth.length) continue
            addExact(oldAuth, newAuth)
            addExact("content://$oldAuth", "content://$newAuth")
            val oldSlash = "content://$oldAuth/"
            val newSlash = "content://$newAuth/"
            addExact(oldSlash, newSlash)
            if (isPureAscii(oldSlash) && isPureAscii(newSlash)) {
                prefixList += SameLengthRule(
                    oldSlash,
                    newSlash,
                    oldSlash.toByteArray(StandardCharsets.US_ASCII),
                    newSlash.toByteArray(StandardCharsets.US_ASCII),
                )
            }
        }
        prefixList.sortByDescending { it.oldBytes.size }

        for ((oldPerm, newPerm) in renamedPermissions) {
            addExact(oldPerm, newPerm)
        }
        for ((oldAcc, newAcc) in renamedAccountTypes) {
            addExact(oldAcc, newAcc)
        }
        return Rules(exactMap.values.toList(), prefixList)
    }

    suspend fun patchModule(
        module: ApkModule,
        rules: Rules,
        onDexProgress: suspend (Int, Int) -> Unit,
    ): Int {
        if (rules.isEmpty) return 0
        val dexSources = runCatching { module.listDexFiles() }.getOrNull() ?: return 0
        if (dexSources.isEmpty()) return 0

        var totalPatched = 0
        val totalDex = dexSources.size
        dexSources.forEachIndexed { index, dexSource ->
            onDexProgress(index + 1, totalDex)
            val entryPath = dexSource.alias
            val originalSource = module.getInputSource(entryPath) ?: dexSource

            val rawBytes = try {
                originalSource.openStream().use { it.readBytes() }
            } catch (_: Throwable) {
                null
            } ?: return@forEachIndexed

            val patchedCount = try {
                patchDexInPlace(rawBytes, rules)
            } catch (_: Throwable) {
                0
            }
            if (patchedCount <= 0) return@forEachIndexed

            val replacement = ByteInputSource(rawBytes, entryPath)
            replacement.copyAttributes(originalSource)
            replacement.method = originalSource.method
            replacement.isUncompressed = originalSource.isUncompressed
            if (originalSource.isUncompressed) {
                module.uncompressedFiles.addPath(entryPath)
            }
            module.zipEntryMap.remove(entryPath)
            module.add(replacement)
            totalPatched += patchedCount
        }
        return totalPatched
    }

    /**
     * Patches matching strings directly inside [dex] in-place and updates the
     * DEX SHA-1 signature and Adler32 checksum when at least one string changed.
     *
     * @return number of `string_ids` entries modified in [dex].
     */
    internal fun patchDexInPlace(dex: ByteArray, rules: Rules): Int {
        if (rules.isEmpty || dex.size < HEADER_SIZE) return 0
        // Verify "dex\n03x\0" magic and standard 0x70 single-container header.
        if (dex[0] != 0x64.toByte() ||
            dex[1] != 0x65.toByte() ||
            dex[2] != 0x78.toByte() ||
            dex[3] != 0x0A.toByte() ||
            dex[7] != 0x00.toByte()
        ) {
            return 0
        }
        val fileSize = readIntLE(dex, 32)
        val headerSize = readIntLE(dex, 36)
        val endianTag = readIntLE(dex, 40)
        if (endianTag != ENDIAN_CONSTANT || headerSize != HEADER_SIZE || fileSize != dex.size) {
            return 0
        }

        val stringIdsSize = readIntLE(dex, 56)
        val stringIdsOff = readIntLE(dex, 60)
        if (stringIdsSize <= 0 ||
            stringIdsOff < HEADER_SIZE ||
            stringIdsOff.toLong() + stringIdsSize.toLong() * 4L > dex.size.toLong()
        ) {
            return 0
        }

        var modified = 0

        // 1. O(log N) binary search for every exact-match rule.
        for (rule in rules.exactRules) {
            val idx = binarySearchAscii(dex, stringIdsOff, stringIdsSize, rule.oldBytes)
            if (idx < 0) continue
            if (overwriteIfSortPreserved(dex, stringIdsOff, stringIdsSize, idx, rule.newAscii, rule.newBytes)) {
                modified++
            }
        }

        // 2. O(log N + K) range scan for `content://<authority>/...` URI literals.
        for (prefixRule in rules.contentPrefixRules) {
            val startIdx = lowerBoundAscii(dex, stringIdsOff, stringIdsSize, prefixRule.oldBytes)
            var idx = startIdx
            while (idx in 0 until stringIdsSize) {
                val loc = locateStringBytes(dex, stringIdsOff, idx) ?: break
                val (payloadOff, payloadLen) = loc
                if (!startsWithBytes(dex, payloadOff, payloadLen, prefixRule.oldBytes)) {
                    break
                }
                // Build candidate string with swapped prefix of identical byte length.
                val candidateBytes = dex.copyOfRange(payloadOff, payloadOff + payloadLen)
                System.arraycopy(prefixRule.newBytes, 0, candidateBytes, 0, prefixRule.newBytes.size)
                val candidateStr = decodeMutf8(candidateBytes, 0, candidateBytes.size)
                if (candidateStr != null &&
                    overwriteIfSortPreserved(dex, stringIdsOff, stringIdsSize, idx, candidateStr, candidateBytes)
                ) {
                    modified++
                }
                idx++
            }
        }

        if (modified > 0) {
            updateHashes(dex)
        }
        return modified
    }

    private fun overwriteIfSortPreserved(
        dex: ByteArray,
        stringIdsOff: Int,
        stringIdsSize: Int,
        idx: Int,
        newStr: String,
        newBytes: ByteArray,
    ): Boolean {
        val loc = locateStringBytes(dex, stringIdsOff, idx) ?: return false
        val (payloadOff, payloadLen) = loc
        if (payloadLen != newBytes.size) return false
        // Never touch Dalvik type descriptors (`Lcom/foo/Bar;`).
        if (payloadLen >= 2 && dex[payloadOff] == 'L'.code.toByte() &&
            dex[payloadOff + payloadLen - 1] == ';'.code.toByte()
        ) {
            return false
        }
        if (idx > 0) {
            val prev = readStringAt(dex, stringIdsOff, idx - 1) ?: return false
            if (compareUtf16CodeUnits(prev, newStr) >= 0) return false
        }
        if (idx + 1 < stringIdsSize) {
            val next = readStringAt(dex, stringIdsOff, idx + 1) ?: return false
            if (compareUtf16CodeUnits(newStr, next) >= 0) return false
        }
        System.arraycopy(newBytes, 0, dex, payloadOff, newBytes.size)
        return true
    }

    private fun binarySearchAscii(
        dex: ByteArray,
        stringIdsOff: Int,
        stringIdsSize: Int,
        targetAscii: ByteArray,
    ): Int {
        var low = 0
        var high = stringIdsSize - 1
        val targetStr = String(targetAscii, StandardCharsets.US_ASCII)
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midStr = readStringAt(dex, stringIdsOff, mid) ?: return -1
            val cmp = compareUtf16CodeUnits(midStr, targetStr)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun lowerBoundAscii(
        dex: ByteArray,
        stringIdsOff: Int,
        stringIdsSize: Int,
        prefixAscii: ByteArray,
    ): Int {
        var low = 0
        var high = stringIdsSize
        val prefixStr = String(prefixAscii, StandardCharsets.US_ASCII)
        while (low < high) {
            val mid = (low + high) ushr 1
            val midStr = readStringAt(dex, stringIdsOff, mid) ?: return stringIdsSize
            if (compareUtf16CodeUnits(midStr, prefixStr) < 0) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    /** Returns `(payloadOffset, payloadByteLength)` excluding ULEB128 header and trailing NUL. */
    private fun locateStringBytes(dex: ByteArray, stringIdsOff: Int, idx: Int): Pair<Int, Int>? {
        val dataOff = readIntLE(dex, stringIdsOff + idx * 4)
        if (dataOff <= 0 || dataOff >= dex.size) return null
        var pos = dataOff
        // Skip ULEB128 utf16_size (1..5 bytes).
        var count = 0
        while (pos < dex.size && count < 5) {
            val b = dex[pos].toInt()
            pos++
            count++
            if ((b and 0x80) == 0) break
        }
        val start = pos
        while (pos < dex.size && dex[pos] != 0.toByte()) {
            pos++
        }
        if (pos >= dex.size) return null
        return start to (pos - start)
    }

    private fun readStringAt(dex: ByteArray, stringIdsOff: Int, idx: Int): String? {
        val (off, len) = locateStringBytes(dex, stringIdsOff, idx) ?: return null
        return decodeMutf8(dex, off, len)
    }

    private fun decodeMutf8(bytes: ByteArray, offset: Int, length: Int): String? {
        // Fast path for pure ASCII strings (99%+ of DEX identifiers).
        var allAscii = true
        val end = offset + length
        for (i in offset until end) {
            val b = bytes[i].toInt()
            if (b <= 0 || b > 0x7F) {
                allAscii = false
                break
            }
        }
        if (allAscii) {
            return String(bytes, offset, length, StandardCharsets.US_ASCII)
        }
        val chars = CharArray(length)
        var out = 0
        var i = offset
        while (i < end) {
            val a = bytes[i++].toInt() and 0xFF
            when {
                (a and 0x80) == 0 -> chars[out++] = a.toChar()
                (a and 0xE0) == 0xC0 -> {
                    if (i >= end) return null
                    val b = bytes[i++].toInt() and 0xFF
                    chars[out++] = (((a and 0x1F) shl 6) or (b and 0x3F)).toChar()
                }
                (a and 0xF0) == 0xE0 -> {
                    if (i + 1 >= end) return null
                    val b = bytes[i++].toInt() and 0xFF
                    val c = bytes[i++].toInt() and 0xFF
                    chars[out++] = (((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar()
                }
                else -> return null
            }
        }
        return String(chars, 0, out)
    }

    /** DEX `string_ids` are ordered by unsigned 16-bit UTF-16 code units. */
    private fun compareUtf16CodeUnits(a: String, b: String): Int {
        val minLen = minOf(a.length, b.length)
        for (i in 0 until minLen) {
            val ca = a[i].code
            val cb = b[i].code
            if (ca != cb) return ca - cb
        }
        return a.length - b.length
    }

    private fun startsWithBytes(
        dex: ByteArray,
        offset: Int,
        length: Int,
        prefix: ByteArray,
    ): Boolean {
        if (length < prefix.size) return false
        for (i in prefix.indices) {
            if (dex[offset + i] != prefix[i]) return false
        }
        return true
    }

    private fun updateHashes(dex: ByteArray) {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(dex, 32, dex.size - 32)
        val digest = sha1.digest()
        System.arraycopy(digest, 0, dex, 12, 20)

        val adler = Adler32()
        adler.update(dex, 12, dex.size - 12)
        writeIntLE(dex, 8, adler.value.toInt())
    }

    private fun readIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun isPureAscii(text: String): Boolean =
        text.all { it.code in 0x20..0x7E }
}
