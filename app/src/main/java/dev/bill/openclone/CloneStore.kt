package dev.bill.openclone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tiny history store for finished clones, kept as one JSON file in
 * private storage. No database, no account, no network.
 */
class CloneStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "clones.json")

    @Synchronized
    fun list(): List<CloneRecord> {
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

    @Synchronized
    fun add(record: CloneRecord) {
        val records = list().filter { it.newPackage != record.newPackage } + record
        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject().apply {
                    put("originalPackage", r.originalPackage)
                    put("originalLabel", r.originalLabel)
                    put("newPackage", r.newPackage)
                    put("newLabel", r.newLabel)
                    put("fileName", r.fileName)
                    put("createdAt", r.createdAt)
                },
            )
        }
        file.writeText(array.toString())
    }

    @Synchronized
    fun remove(newPackage: String) {
        val records = list().filter { it.newPackage != newPackage }
        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject().apply {
                    put("originalPackage", r.originalPackage)
                    put("originalLabel", r.originalLabel)
                    put("newPackage", r.newPackage)
                    put("newLabel", r.newLabel)
                    put("fileName", r.fileName)
                    put("createdAt", r.createdAt)
                },
            )
        }
        file.writeText(array.toString())
    }
}
