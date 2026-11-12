package ru.tinyops.android.vboard.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Where a finger came down on a view of small targets. A finger rolls a little as it lifts, often onto a narrow
 * neighbour or past the edge of the view: within the touch slop of where it came down the tap stays with the
 * target it came down on, as long as that target is still under that place.
 */
internal class TapSlop(
    context: Context,
) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f

    fun down(event: MotionEvent) {
        downX = event.x
        downY = event.y
    }

    /** Whether [target], pressed when the finger came down, is still the one the finger means at [event]. */
    fun <T> keeps(
        event: MotionEvent,
        target: T,
        under: T,
        at: (x: Float, y: Float) -> T,
    ): Boolean = target == under || hypot(event.x - downX, event.y - downY) <= slop && at(downX, downY) == target
}
