package dev.bill.openclone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Circular progress indicator for Proses Cloning (DESAIN.md section 4,
 * Layar 3): a 10dp stroke arc with rounded caps drawn over a #2A2A2F track,
 * with the percentage rendered bold in the centre.
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val strokeWidth = dp(10f)
    private val bounds = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RingProgressView.strokeWidth
        color = ContextCompat.getColor(context, R.color.border_subtle)
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RingProgressView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_white)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = ContextCompat.getColor(context, R.color.accent_white)
    }

    /** 0..100, clamped. */
    var progress: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, 100)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = min(w, h).toFloat()
        val inset = strokeWidth / 2f
        val left = (w - size) / 2f + inset
        val top = (h - size) / 2f + inset
        bounds.set(left, top, left + size - strokeWidth, top + size - strokeWidth)
        textPaint.textSize = size * 0.2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        if (progress > 0) {
            canvas.drawArc(bounds, -90f, 360f * progress / 100f, false, arcPaint)
        }
        val centerY = bounds.centerY() -
            (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("$progress%", bounds.centerX(), centerY, textPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
