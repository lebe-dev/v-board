package ru.tinyops.android.vboard.stt.model

/**
 * Action carried by a [TouchPoint], matching the action codes used by the
 * reference implementation's touch-point protocol.
 */
enum class TouchAction(
    val code: Int,
) {
    DOWN(0),
    UP(1),
    MOVE(2),
    CANCEL(3),
    POINTER_DOWN(4),
    POINTER_UP(5),
}

/**
 * A single sampled touch during a gesture.
 *
 * @param action the motion action this sample was derived from
 * @param pointerId the pointer that produced the sample
 * @param x horizontal coordinate in keyboard space
 * @param y vertical coordinate in keyboard space
 * @param timeMillis time since gesture start, in milliseconds
 */
data class TouchPoint(
    val action: TouchAction,
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val timeMillis: Double,
) {
    fun distanceSqTo(other: TouchPoint): Double {
        val dx = (x - other.x).toDouble()
        val dy = (y - other.y).toDouble()
        return dx * dx + dy * dy
    }

    fun timeDeltaTo(other: TouchPoint): Double = timeMillis - other.timeMillis
}
