package dev.bill.openclone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Color preset options for distinguishing cloned app icons from the original app.
 */
enum class IconColorOption(
    val id: String,
    val labelRes: Int,
    val targetHue: Float?,
    val swatchColor: Int,
) {
    AUTO("AUTO", R.string.icon_color_auto, null, 0xFF22C55E.toInt()),
    EMERALD("EMERALD", R.string.icon_color_emerald, 148f, 0xFF10B981.toInt()),
    PURPLE("PURPLE", R.string.icon_color_purple, 272f, 0xFF8B5CF6.toInt()),
    CRIMSON("CRIMSON", R.string.icon_color_crimson, 352f, 0xFFF43F5E.toInt()),
    AMBER("AMBER", R.string.icon_color_amber, 28f, 0xFFF59E0B.toInt()),
    CYAN("CYAN", R.string.icon_color_cyan, 188f, 0xFF06B6D4.toInt()),
    ROSE("ROSE", R.string.icon_color_rose, 322f, 0xFFEC4899.toInt()),
    ORIGINAL("ORIGINAL", R.string.icon_color_original, null, 0xFF71717A.toInt());

    companion object {
        fun fromId(id: String?): IconColorOption =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }
}

/**
 * Generates distinct colored launcher icons (and optional clone-number badges)
 * from an installed app's icon or a custom bitmap.
 *
 * Core pixel math is written in pure Kotlin without Android JNI calls so it
 * runs in ~1 ms per 192x192 icon and is directly unit-testable on the JVM.
 */
internal object IconColorizer {

    const val EXPORT_ICON_PX = 192
    const val PREVIEW_SIDECAR_PX = 96

    private val AUTO_HUE_SHIFTS = floatArrayOf(
        290f, // Clone 1: e.g. Facebook Blue -> Emerald Green
        62f,  // Clone 2: e.g. Facebook Blue -> Vivid Purple
        138f, // Clone 3: e.g. Facebook Blue -> Crimson Red
        178f, // Clone 4: e.g. Facebook Blue -> Amber Orange
        245f, // Clone 5: e.g. Facebook Blue -> Fresh Lime
        106f, // Clone 6: e.g. Facebook Blue -> Hot Pink
        325f, // Clone 7: e.g. Facebook Blue -> Cyan/Teal
        208f, // Clone 8: e.g. Facebook Blue -> Gold
    )

    /**
     * Returns a non-zero hue shift in `[40°, 320°]` that stays distinct across
     * sequential clones (`1, 2, 3, ...`) and never lands near `0°` (original color).
     */
    fun autoHueDelta(cloneNumber: Int): Float {
        val idx = (cloneNumber.coerceAtLeast(1) - 1)
        if (idx < AUTO_HUE_SHIFTS.size) {
            return AUTO_HUE_SHIFTS[idx]
        }
        return ((85f + idx * 137.508f) % 280f) + 40f
    }

    /**
     * Recolors [pixels] in-place according to [option] and [cloneNumber].
     *
     * - Preserves transparent pixels and bright white/light foreground glyphs
     *   (such as Facebook's white `"f"` or WhatsApp's white phone handset)
     *   with continuous HSV rotation so anti-aliased edges never halo.
     * - Automatically detects monochrome/dark icons (such as black-and-white
     *   logos) and tints dark/midground surfaces to the target accent hue.
     */
    fun recolorArgbPixels(
        pixels: IntArray,
        option: IconColorOption,
        cloneNumber: Int = 1,
    ) {
        if (option == IconColorOption.ORIGINAL || pixels.isEmpty()) return

        // Pass 1: inspect chromaticity and find dominant hue across 36 10-degree bins.
        val hueBins = FloatArray(36)
        var opaqueCount = 0
        var chromaticCount = 0
        for (argb in pixels) {
            val a = argb ushr 24
            if (a < 16) continue
            opaqueCount++
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            if (max > 20 && delta * 255 >= max * 36) { // saturation >= ~0.14
                chromaticCount++
                val h = computeHueDegrees(r, g, b, max, delta)
                val bin = ((h / 10f).toInt()).coerceIn(0, 35)
                hueBins[bin] += (delta / 255f) * (a / 255f)
            }
        }
        if (opaqueCount == 0) return

        var dominantBin = 0
        var bestWeight = -1f
        for (i in hueBins.indices) {
            if (hueBins[i] > bestWeight) {
                bestWeight = hueBins[i]
                dominantBin = i
            }
        }
        val dominantHue = dominantBin * 10f + 5f

        val isMonochrome = chromaticCount < opaqueCount * 0.12f
        val targetHue: Float
        val hueDelta: Float
        if (option == IconColorOption.AUTO || option.targetHue == null) {
            val shift = autoHueDelta(cloneNumber)
            targetHue = (dominantHue + shift) % 360f
            hueDelta = shift
        } else {
            // If user picked a specific color AND batching multiple clones, nudge
            // subsequent clones slightly only in AUTO; for explicit color, hit targetHue!
            targetHue = option.targetHue
            hueDelta = ((targetHue - dominantHue) % 360f + 360f) % 360f
        }

        // Pass 2: transform pixels smoothly.
        for (i in pixels.indices) {
            val argb = pixels[i]
            val a = argb ushr 24
            if (a < 8) continue
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            val v = max / 255f
            val s = if (max == 0) 0f else delta.toFloat() / max.toFloat()

            if (isMonochrome) {
                // Keep bright white foreground crisp; tint dark/midground background!
                if (v >= 0.86f && s < 0.12f) continue
                val liftWeight = ((0.86f - v) / 0.30f).coerceIn(0f, 1f)
                val newS = (s + 0.76f * liftWeight).coerceIn(0f, 0.88f)
                val newV = (0.52f + v * 0.42f) * liftWeight + v * (1f - liftWeight)
                pixels[i] = hsvToArgb(a, targetHue, newS, newV)
            } else {
                // Continuous HSV hue rotation: S=0 (white/black) stays unchanged
                // automatically with zero threshold halo on anti-aliased edges!
                if (delta == 0) continue
                val h = computeHueDegrees(r, g, b, max, delta)
                val boostedS = if (s > 0.25f) (s * 1.05f).coerceAtMost(1f) else s
                pixels[i] = hsvToArgb(a, h + hueDelta, boostedS, v)
            }
        }
    }

    /**
     * Renders a colored clone icon [Bitmap] from [baseBitmap], optionally drawing
     * a clean clone-number badge in the bottom-right corner.
     */
    fun createStyledBitmap(
        baseBitmap: Bitmap,
        option: IconColorOption,
        cloneNumber: Int,
        showBadge: Boolean,
        sizePx: Int = EXPORT_ICON_PX,
    ): Bitmap {
        val targetPx = sizePx.coerceAtLeast(48)
        val scaled = if (baseBitmap.width == targetPx && baseBitmap.height == targetPx) {
            baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            Bitmap.createScaledBitmap(baseBitmap, targetPx, targetPx, true)
                .copy(Bitmap.Config.ARGB_8888, true)
        }

        if (option != IconColorOption.ORIGINAL) {
            val pixels = IntArray(targetPx * targetPx)
            scaled.getPixels(pixels, 0, targetPx, 0, 0, targetPx, targetPx)
            recolorArgbPixels(pixels, option, cloneNumber)
            scaled.setPixels(pixels, 0, targetPx, 0, 0, targetPx, targetPx)
        }

        if (showBadge) {
            drawCloneBadge(scaled, cloneNumber)
        }
        return scaled
    }

    /**
     * Renders the installed app's launcher icon into an uncompressed ARGB [Bitmap]
     * at [sizePx], ready for [createStyledBitmap].
     */
    fun loadBaseAppBitmap(context: Context, packageName: String, sizePx: Int = EXPORT_ICON_PX): Bitmap? {
        val drawable: Drawable = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encodes [bitmap] as lossless PNG bytes.
     */
    fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun drawCloneBadge(bitmap: Bitmap, cloneNumber: Int) {
        val size = bitmap.width.toFloat()
        val label = cloneNumber.coerceAtLeast(1).toString()
        val canvas = Canvas(bitmap)

        val badgeHeight = size * 0.34f
        val minWidth = badgeHeight
        val extraDigitWidth = (label.length - 1).coerceAtLeast(0) * (size * 0.13f)
        val badgeWidth = (minWidth + extraDigitWidth).coerceAtMost(size * 0.68f)
        val inset = size * 0.04f
        val rect = RectF(
            size - badgeWidth - inset,
            size - badgeHeight - inset,
            size - inset,
            size - inset,
        )
        val radius = badgeHeight / 2f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xEE141418.toInt()
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (size * 0.03f).coerceAtLeast(1.5f)
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = badgeHeight * 0.58f
        }
        val fm = textPaint.fontMetrics
        val textY = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, rect.centerX(), textY, textPaint)
    }

    internal fun computeHueDegrees(r: Int, g: Int, b: Int, max: Int, delta: Int): Float {
        if (delta <= 0) return 0f
        val raw = when (max) {
            r -> 60f * ((g - b).toFloat() / delta.toFloat())
            g -> 60f * (((b - r).toFloat() / delta.toFloat()) + 2f)
            else -> 60f * (((r - g).toFloat() / delta.toFloat()) + 4f)
        }
        return if (raw < 0f) raw + 360f else raw % 360f
    }

    internal fun hsvToArgb(alpha: Int, hueDeg: Float, saturation: Float, value: Float): Int {
        val h = ((hueDeg % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val sector = h / 60f
        val x = c * (1f - abs(sector % 2f - 1f))
        val m = v - c
        val r1: Float
        val g1: Float
        val b1: Float
        when (sector.toInt()) {
            0 -> {
                r1 = c; g1 = x; b1 = 0f
            }
            1 -> {
                r1 = x; g1 = c; b1 = 0f
            }
            2 -> {
                r1 = 0f; g1 = c; b1 = x
            }
            3 -> {
                r1 = 0f; g1 = x; b1 = c
            }
            4 -> {
                r1 = x; g1 = 0f; b1 = c
            }
            else -> {
                r1 = c; g1 = 0f; b1 = x
            }
        }
        val ri = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gi = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bi = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return ((alpha and 0xFF) shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
