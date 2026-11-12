package ru.tinyops.android.vboard.core.input

/** How long word suggestions stay on the strip after the last key, as they cover the buttons there. */
enum class SuggestionTimeout(
    val seconds: Int,
) {
    SECONDS_3(3),
    SECONDS_5(5),
    SECONDS_10(10),
    OFF(0),
    ;

    /** Milliseconds until the suggestions go away, 0 for never. */
    val millis: Long get() = seconds * MILLIS_PER_SECOND

    companion object {
        /** Enough to think over three words after a pause without them going away under the finger. */
        val DEFAULT = SECONDS_10

        fun fromSeconds(seconds: Int): SuggestionTimeout = entries.firstOrNull { it.seconds == seconds } ?: DEFAULT

        private const val MILLIS_PER_SECOND = 1_000L
    }
}
