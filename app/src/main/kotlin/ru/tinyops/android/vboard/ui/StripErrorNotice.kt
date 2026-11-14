package ru.tinyops.android.vboard.ui

import android.graphics.Canvas
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/** A short red message drawn in place of the suggestions of [view], gone after a few seconds. */
class StripErrorNotice(
    private val view: View,
    base: TextPaint,
    darkTheme: Boolean,
) {
    private var text: String? = null
    private val hide = Runnable { clear() }
    private val paint =
        TextPaint(base).apply {
            color = if (darkTheme) COLOR_DARK else COLOR_LIGHT
            typeface = Typeface.DEFAULT_BOLD
        }

    fun show(message: Int) {
        text = view.context.getString(message)
        view.removeCallbacks(hide)
        view.postDelayed(hide, SHOWN_MILLIS)
        view.invalidate()
    }

    fun clear() {
        view.removeCallbacks(hide)
        if (text == null) return
        text = null
        view.invalidate()
    }

    /** Draws the message centered at [centerX] and returns whether there was one. */
    fun draw(
        canvas: Canvas,
        centerX: Float,
        room: Float,
        rowHeight: Float,
    ): Boolean {
        val message = text ?: return false
        val shown = TextUtils.ellipsize(message, paint, room, TextUtils.TruncateAt.END)
        val metrics = paint.fontMetrics
        val baseline = (rowHeight - metrics.ascent - metrics.descent) / 2f
        canvas.drawText(shown, 0, shown.length, centerX, baseline, paint)
        return true
    }

    private companion object {
        const val SHOWN_MILLIS = 4_000L
        const val COLOR_LIGHT = 0xFFD93025.toInt()
        const val COLOR_DARK = 0xFFFF6B60.toInt()
    }
}
