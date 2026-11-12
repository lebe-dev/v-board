package ru.tinyops.android.vboard.stt

import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import kotlin.math.hypot

/** A compact staggered QWERTY layout with 100x100 keys. */
fun qwertyLayout(): KeyboardLayout {
    val rows =
        listOf(
            0f to "qwertyuiop",
            110f to "asdfghjkl",
            220f to "zxcvbnm",
        )
    val keys = ArrayList<Key>()
    var code = 1
    for ((y, characters) in rows) {
        val xStart =
            when (characters.first()) {
                'a' -> 50f
                'z' -> 150f
                else -> 0f
            }
        characters.forEachIndexed { index, c ->
            val left = xStart + index * 100f
            keys +=
                Key(
                    code = code++,
                    label = c.toString(),
                    left = left,
                    top = y,
                    right = left + 100f,
                    bottom = y + 100f,
                )
        }
    }
    return KeyboardLayout(
        width = 1000f,
        height = 330f,
        xdpi = 160f,
        ydpi = 160f,
        keys = keys,
    )
}

/**
 * Builds a gesture that travels in straight lines between the centers of the
 * keys of [word]. Produces [pointsPerSegment] points per travelled segment and
 * ends exactly on the last key's center.
 */
fun gestureForWord(
    layout: KeyboardLayout,
    word: String,
    pointsPerSegment: Int = 4,
    timeStepMillis: Double = 12.0,
): List<TouchPoint> {
    require(word.isNotEmpty()) { "word must not be empty" }
    val centers =
        word.map { c ->
            val key = requireNotNull(layout.keyForCharacter(c)) { "no key for '$c'" }
            key.centerX.toDouble() to key.centerY.toDouble()
        }

    val points = ArrayList<TouchPoint>()
    var time = 0.0

    fun add(
        x: Double,
        y: Double,
    ) {
        points += TouchPoint(TouchAction.MOVE, pointerId = 0, x = x.toFloat(), y = y.toFloat(), timeMillis = time)
        time += timeStepMillis
    }

    for (segment in centers.indices) {
        val from = if (segment == 0) centers[0] else centers[segment - 1]
        val to = centers[segment]
        val steps = if (segment == 0) 1 else pointsPerSegment
        for (step in 0 until steps) {
            val fraction = if (steps == 1) 1.0 else (step + 1).toDouble() / steps
            add(
                from.first + (to.first - from.first) * fraction,
                from.second + (to.second - from.second) * fraction,
            )
        }
    }

    points[0] = points[0].copy(action = TouchAction.DOWN)
    points[points.lastIndex] = points[points.lastIndex].copy(action = TouchAction.UP)
    return points
}

/** Adds a small perpendicular wobble to a gesture, emulating a human finger. */
fun List<TouchPoint>.withWobble(
    amplitude: Float = 6f,
    period: Int = 3,
): List<TouchPoint> =
    mapIndexed { index, point ->
        val offset = if (index % period == 0) amplitude else 0f
        point.copy(y = point.y + offset)
    }

/** Word frequencies used by decoder tests. */
val TEST_FREQUENCIES: Map<String, Double> =
    mapOf(
        "hello" to 100.0,
        "hell" to 20.0,
        "help" to 40.0,
        "held" to 10.0,
        "hero" to 15.0,
        "world" to 80.0,
        "word" to 60.0,
        "would" to 50.0,
        "wood" to 30.0,
        "he" to 90.0,
        "we" to 70.0,
        "to" to 200.0,
        "the" to 300.0,
        "test" to 120.0,
    )

/** Euclidean distance helper for tests. */
fun distance(
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Double = hypot((ax - bx).toDouble(), (ay - by).toDouble())
