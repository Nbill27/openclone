package dev.bill.openclone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Lazily loaded, memory-bounded cache of installed-app icons.
 *
 * The list used to carry a [Drawable] per package, which meant every refresh
 * decoded 200+ icons before the first row could be drawn. Now only visible
 * rows ask for an icon.
 *
 * Icons are rasterised once into a small bitmap and cached by byte size, so a
 * phone with hundreds of apps cannot blow up the heap on icon bitmaps: the
 * source drawables (often adaptive icons backed by large images) are never
 * retained.
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
     * Safe to call from the main thread: after the first row the bitmap comes
     * from the cache, and a miss only rasterises one small square.
     */
    fun load(context: Context, packageName: String, sizePx: Int): Drawable? {
        val px = sizePx.coerceIn(MIN_ICON_PX, MAX_ICON_PX)
        val key = "$packageName@$px"
        cache.get(key)?.let { return BitmapDrawable(context.resources, it) }

        val source = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            // Uninstalled between the scan and the bind, or a broken resource.
            null
        } ?: return null

        val bitmap = try {
            rasterise(source, px)
        } catch (_: Exception) {
            null
        } ?: return null

        cache.put(key, bitmap)
        return BitmapDrawable(context.resources, bitmap)
    }

    /** Drops every cached icon, e.g. after a clone was installed. */
    fun clear() {
        cache.evictAll()
    }

    private fun rasterise(source: Drawable, px: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        source.setBounds(0, 0, px, px)
        source.draw(canvas)
        return bitmap
    }
}
