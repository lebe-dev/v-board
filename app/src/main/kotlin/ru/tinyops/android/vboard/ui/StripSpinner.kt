package ru.tinyops.android.vboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock

/** The spinner the strip draws on a button while its request runs; the caller keeps redrawing the strip. */
internal class StripSpinner(
    private val iconSize: Float,
) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = iconSize * SpinnerDrawable.STROKE
        }
    private val bounds = RectF()

    fun draw(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        tint: Int,
    ) {
        paint.color = tint
        val radius = iconSize * SpinnerDrawable.RADIUS
        bounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        val angle =
            (SystemClock.uptimeMillis() % SpinnerDrawable.PERIOD_MS) * SpinnerDrawable.FULL_TURN /
                SpinnerDrawable.PERIOD_MS
        canvas.drawArc(bounds, angle, SpinnerDrawable.SWEEP, false, paint)
    }
}
