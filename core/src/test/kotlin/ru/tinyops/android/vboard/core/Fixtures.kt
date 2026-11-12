package ru.tinyops.android.vboard.core

import ru.tinyops.android.vboard.core.dict.Dictionary
import ru.tinyops.android.vboard.core.layout.GeometryMetrics
import ru.tinyops.android.vboard.core.layout.KeyboardGeometry
import ru.tinyops.android.vboard.core.layout.KeyboardLayouts
import ru.tinyops.android.vboard.core.layout.KeyboardMode
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.nio.ByteBuffer

/** The generated image of the dictionary bundled for [language]. */
fun bundledImage(language: Language): ByteArray {
    val path = "dict/${language.code}.dict"
    val stream = requireNotNull(Language::class.java.classLoader.getResourceAsStream(path)) { "missing $path" }
    return stream.use { it.readBytes() }
}

fun bundledDictionary(language: Language): Dictionary = Dictionary.read(ByteBuffer.wrap(bundledImage(language)))

/** Phone-like metrics: 1080 px wide, ~2.6 px per dp. */
val PHONE_METRICS =
    GeometryMetrics(
        horizontalGap = 14f,
        verticalGap = 26f,
        horizontalPadding = 10f,
        topPadding = 10f,
        bottomPadding = 10f,
    )

fun phoneGeometry(
    language: Language,
    mode: KeyboardMode = KeyboardMode.LETTERS,
): KeyboardGeometry = KeyboardGeometry(KeyboardLayouts.spec(language, mode), 1080f, 640f, PHONE_METRICS)

/**
 * A gesture travelling in straight lines through the centers of the keys of [word], sampled
 * densely like a real touch screen (about every 8 px, 8 ms apart).
 */
fun swipe(
    geometry: KeyboardGeometry,
    word: String,
): List<TouchPoint> {
    val centers =
        word.map { c ->
            val key = geometry.keys.first { it.spec.isLetter && it.spec.output == c.toString() }
            key.bounds.centerX to key.bounds.centerY
        }
    val points = ArrayList<TouchPoint>()
    var time = 0.0

    fun add(
        x: Float,
        y: Float,
    ) {
        points += TouchPoint(TouchAction.MOVE, 0, x, y, time)
        time += 8.0
    }
    add(centers[0].first, centers[0].second)
    for (i in 1 until centers.size) {
        val (fx, fy) = centers[i - 1]
        val (tx, ty) = centers[i]
        val length = kotlin.math.hypot(tx - fx, ty - fy)
        val steps = maxOf(1, (length / 8f).toInt())
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            add(fx + (tx - fx) * t, fy + (ty - fy) * t)
        }
    }
    points[0] = points[0].copy(action = TouchAction.DOWN)
    points[points.lastIndex] = points.last().copy(action = TouchAction.UP)
    return points
}
