package ru.tinyops.android.vboard.core.gesture.logging

import ru.tinyops.android.vboard.core.gesture.neural.ScoredCandidate
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.util.Locale

/** What happened to a word inserted by a swipe gesture. */
enum class SwipeOutcome(
    val code: String,
) {
    KEPT("kept"),
    PICKED_SUGGESTION("picked_suggestion"),
    RETYPED("retyped"),
    DELETED("deleted"),
    ;

    companion object {
        fun fromCode(code: String?): SwipeOutcome = entries.firstOrNull { it.code == code } ?: KEPT
    }
}

/**
 * One completed swipe gesture recorded by the opt-in free typing log, together with the layout it
 * was drawn on, the decoder's candidates and score breakdown, and what the user did next.
 */
data class SwipeLogEntry(
    val id: Long,
    val timestampMillis: Long,
    val points: List<TouchPoint>,
    val layout: KeyboardLayout,
    val previousWord: String?,
    val engine: String,
    val candidates: List<String>,
    val candidateScores: List<ScoredCandidate>,
    val inserted: String,
    val outcome: SwipeOutcome = SwipeOutcome.KEPT,
    val finalWord: String = inserted,
) {
    /** Best-known target word for `valid.ref` so evaluation scripts can read the archive directly. */
    fun referenceWord(): String = finalWord.ifBlank { inserted }.lowercase(Locale.ROOT)
}
