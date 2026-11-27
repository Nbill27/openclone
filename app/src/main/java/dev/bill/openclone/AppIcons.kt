package dev.bill.openclone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.File

/**
 * Lazily loaded, memory-bounded cache of installed-app icons and generated
 * clone preview icons.
 */
object AppIcons {

    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    private const val MIN_ICON_PX = 48
    private const val MAX_ICON_PX = 192

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * Icon for [packageName], rendered at [sizePx] device pixels.
     *
     * Checks installed packages first, then falls back to the generated clone
     * icon sidecar (`files/clones/<packageName>.png`) so freshly created clones
     * show their recolored icon before installation.
     */
    fun load(context: Context, packageName: String, sizePx: Int): Drawable? {
        val px = sizePx.coerceIn(MIN_ICON_PX, MAX_ICON_PX)
        val key = "$packageName@$px"
        cache.get(key)?.let { return BitmapDrawable(context.resources, it) }

        val installedSource = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }

        val bitmap = if (installedSource != null) {
            try {
                rasterise(installedSource, px)
            } catch (_: Exception) {
                null
            }
        } else {
            loadSidecarBitmap(context, packageName, px)
        } ?: return null

        cache.put(key, bitmap)
        return BitmapDrawable(context.resources, bitmap)
    }

    /** Drops cached icons for [packageName] (e.g. when regenerating a clone icon). */
    fun evict(packageName: String) {
        val prefix = "$packageName@"
        cache.snapshot().keys
            .filter { it.startsWith(prefix) }
            .forEach { cache.remove(it) }
    }

    /** Drops every cached icon, e.g. after a clone was installed. */
    fun clear() {
        cache.evictAll()
    }

    fun clonePreviewFile(context: Context, newPackage: String): File =
        File(File(context.filesDir, "clones"), "$newPackage.png")

    private fun loadSidecarBitmap(context: Context, packageName: String, px: Int): Bitmap? {
        val file = clonePreviewFile(context, packageName)
        if (!file.exists()) return null
        return try {
            val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            if (decoded.width == px && decoded.height == px) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, px, px, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun rasterise(source: Drawable, px: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        source.setBounds(0, 0, px, px)
        source.draw(canvas)
        return bitmap
    }
}
