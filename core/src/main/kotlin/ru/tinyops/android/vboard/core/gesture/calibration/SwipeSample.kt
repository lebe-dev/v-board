package ru.tinyops.android.vboard.core.gesture.calibration

import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.util.Locale

/**
 * One gesture recorded during calibration: the [word] the user was asked to swipe, the [points] in the
 * coordinates of [layout], and the [candidates] the [engine] gave for it, best first.
 */
data class SwipeSample(
    val word: String,
    val points: List<TouchPoint>,
    val layout: KeyboardLayout,
    val candidates: List<String>,
    val engine: String,
)

/** How many of [total] gestures gave the asked word as the [first] candidate, and as any [shown] one. */
data class CalibrationScore(
    val total: Int,
    val first: Int,
    val shown: Int,
) {
    companion object {
        fun of(samples: List<SwipeSample>): CalibrationScore {
            var first = 0
            var shown = 0
            for (sample in samples) {
                val word = fold(sample.word)
                val candidates = sample.candidates.map(::fold)
                if (candidates.firstOrNull() == word) first++
                if (word in candidates) shown++
            }
            return CalibrationScore(samples.size, first, shown)
        }

        private fun fold(word: String): String = word.lowercase(Locale.ROOT).replace('ё', 'е')
    }
}
