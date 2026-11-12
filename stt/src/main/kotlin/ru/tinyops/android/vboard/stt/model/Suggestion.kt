package ru.tinyops.android.vboard.stt.model

/**
 * A decoded candidate for a gesture.
 *
 * @param word the suggested word
 * @param score overall score; higher is better
 * @param spatialScore spatial-model score contribution (higher is better)
 * @param languageScore language-model score contribution (higher is better)
 */
data class Suggestion(
    val word: String,
    val score: Double,
    val spatialScore: Double = 0.0,
    val languageScore: Double = 0.0,
)
