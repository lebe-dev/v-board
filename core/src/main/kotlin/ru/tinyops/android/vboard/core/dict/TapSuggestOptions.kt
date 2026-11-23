package ru.tinyops.android.vboard.core.dict

/**
 * Switches controlling manual-typing suggestions and typo corrections.
 *
 * @param corrections whether typo corrections are offered while typing a word
 * @param fuzzyPrefix whether completions are offered when a prefix contains a typo
 * @param twoEdits whether two-edit corrections are searched for longer words
 * @param spatialModel whether exact touch coordinates weigh letter substitutions
 */
data class TapSuggestOptions(
    val corrections: Boolean = true,
    val fuzzyPrefix: Boolean = true,
    val twoEdits: Boolean = true,
    val spatialModel: Boolean = true,
)

/**
 * Suggestions for a word being typed manually.
 *
 * @param words lowercase candidates to display, best first
 * @param autocorrect confident lowercase correction to apply on space or punctuation, or null
 */
data class TapSuggestions(
    val words: List<String>,
    val autocorrect: String? = null,
)
