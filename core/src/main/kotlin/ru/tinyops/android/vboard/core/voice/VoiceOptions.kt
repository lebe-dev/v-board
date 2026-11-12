package ru.tinyops.android.vboard.core.voice

/**
 * "Пауза после речи": how long the silence lasts before the VAD ends a phrase. The values and
 * the auto rule are Govorun's: auto gives a longer pause when the text is inserted only after
 * the recording ends, because there is no reason to cut a thought into pieces then.
 */
enum class PauseLength(
    val id: String,
) {
    AUTO("auto"),
    SHORT("short"),
    MEDIUM("medium"),
    LONG("long"),
    ;

    /** Seconds of silence that end a phrase; [atEnd] when the text is inserted after the recording. */
    fun seconds(atEnd: Boolean): Float =
        when (this) {
            SHORT -> SHORT_SECONDS
            MEDIUM -> MEDIUM_SECONDS
            LONG -> LONG_SECONDS
            AUTO -> if (atEnd) LONG_SECONDS else MEDIUM_SECONDS
        }

    companion object {
        val DEFAULT = AUTO
        private const val SHORT_SECONDS = 0.5f
        private const val MEDIUM_SECONDS = 1.0f
        private const val LONG_SECONDS = 2.0f

        fun fromId(id: String?): PauseLength = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * "Завершение диктовки".
 *  - [AFTER_PAUSE]: every phrase is inserted as soon as it is recognized, the recording goes on
 *    until the microphone is tapped again.
 *  - [REPEAT_TAP]: phrases are collected, the second tap inserts them all at once and × discards
 *    them.
 */
enum class RecordingMode(
    val id: String,
) {
    AFTER_PAUSE("after_pause"),
    REPEAT_TAP("repeat_tap"),
    ;

    /** Whether recognized text waits for the end of the recording. */
    val deferred: Boolean get() = this == REPEAT_TAP

    companion object {
        val DEFAULT = AFTER_PAUSE

        fun read(stored: String?): RecordingMode = entries.firstOrNull { it.id == stored } ?: DEFAULT
    }
}

/**
 * "Минимальная длина" of text improvement: shorter text goes straight to the editor, because a brief
 * reply has little to improve and is not worth waiting for the server. The default and the way of
 * counting are Govorun's: characters of the trimmed text, spaces inside it included.
 */
enum class EnhancementThreshold(
    val characters: Int,
) {
    ALWAYS(0),
    CHARS_30(30),
    CHARS_60(60),
    CHARS_120(120),
    CHARS_250(250),
    CHARS_500(500),
    ;

    /** Whether [text] is long enough to go to the text improvement server. */
    fun applies(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.codePointCount(0, trimmed.length) >= characters
    }

    companion object {
        val DEFAULT = CHARS_60

        fun fromCharacters(characters: Int): EnhancementThreshold =
            entries.firstOrNull { it.characters == characters } ?: DEFAULT
    }
}

/**
 * "Уровень усилий" of text improvement: how much the model may think before it answers, sent as the
 * OpenAI `reasoning_effort` of the chat completion. Medium unless the user chose another level.
 */
enum class EnhancementEffort(
    val id: String,
) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    ;

    companion object {
        val DEFAULT = MEDIUM

        fun fromId(id: String?): EnhancementEffort = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** "Остановить запись через": a whole-session limit, applied only while the screen is kept on. */
enum class AutoStop(
    val minutes: Int,
) {
    MINUTES_15(15),
    HOUR_1(60),
    HOURS_3(180),
    OFF(0),
    ;

    companion object {
        val DEFAULT = HOUR_1

        fun fromMinutes(minutes: Int): AutoStop = entries.firstOrNull { it.minutes == minutes } ?: DEFAULT

        /** Milliseconds after which a recording stops by itself, 0 for no limit. */
        fun limitMillis(
            keepScreenOn: Boolean,
            autoStop: AutoStop,
        ): Long = if (keepScreenOn) autoStop.minutes * MILLIS_PER_MINUTE else 0L

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
