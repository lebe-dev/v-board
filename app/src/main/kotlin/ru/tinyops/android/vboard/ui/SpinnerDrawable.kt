package ru.tinyops.android.vboard.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock

/** The spinner of a button waiting for a server, for a view that shows a drawable; it redraws itself while shown. */
internal class SpinnerDrawable(
    tint: Int,
    private val iconSize: Float,
) : Drawable() {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = iconSize * STROKE
            color = tint
        }
    private val arc = RectF()

    override fun draw(canvas: Canvas) {
        val radius = iconSize * RADIUS
        arc.set(
            bounds.exactCenterX() - radius,
            bounds.exactCenterY() - radius,
            bounds.exactCenterX() + radius,
            bounds.exactCenterY() + radius,
        )
        val angle = (SystemClock.uptimeMillis() % PERIOD_MS) * FULL_TURN / PERIOD_MS
        canvas.drawArc(arc, angle, SWEEP, false, paint)
        invalidateSelf()
    }

    override fun getIntrinsicWidth() = iconSize.toInt()

    override fun getIntrinsicHeight() = iconSize.toInt()

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val RADIUS = 0.4f
        const val STROKE = 0.1f
        const val SWEEP = 270f
        const val PERIOD_MS = 1_000L
        const val FULL_TURN = 360f
    }
}
