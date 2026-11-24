package dev.bill.openclone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Tiny history store for finished clones, kept as one JSON file in
 * private storage. No database, no account, no network.
 *
 * Access is serialized on a process-wide lock: every screen constructs its
 * own [CloneStore], so an instance-level lock would let a read in one
 * activity interleave with a read-modify-write in another and lose records.
 * Writes go to a temp file first and are then renamed over the real file, so
 * a crash or a full disk leaves the previous history intact instead of a
 * truncated JSON document.
 */
class CloneStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "clones.json")

    fun list(): List<CloneRecord> = synchronized(LOCK) {
        readLocked()
    }

    fun add(record: CloneRecord) = synchronized(LOCK) {
        val records = readLocked().filter { it.newPackage != record.newPackage } + record
        writeLocked(records)
    }

    fun remove(newPackage: String) = synchronized(LOCK) {
        writeLocked(readLocked().filter { it.newPackage != newPackage })
    }

    private fun readLocked(): List<CloneRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                CloneRecord(
                    originalPackage = obj.optString("originalPackage"),
                    originalLabel = obj.optString("originalLabel"),
                    newPackage = obj.optString("newPackage"),
                    newLabel = obj.optString("newLabel"),
                    fileName = obj.optString("fileName"),
                    createdAt = obj.optLong("createdAt"),
                )
            }.sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeLocked(records: List<CloneRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(array.toString(), StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // renameTo does not replace an existing file on every file
                // system, so fall back to an explicit copy.
                file.writeText(array.toString(), StandardCharsets.UTF_8)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun CloneRecord.toJson(): JSONObject = JSONObject().apply {
        put("originalPackage", originalPackage)
        put("originalLabel", originalLabel)
        put("newPackage", newPackage)
        put("newLabel", newLabel)
        put("fileName", fileName)
        put("createdAt", createdAt)
    }

    private companion object {
        val LOCK = Any()
    }
}
