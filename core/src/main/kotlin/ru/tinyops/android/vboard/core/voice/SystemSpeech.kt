package ru.tinyops.android.vboard.core.voice

/** Who turns speech into text when "Распознавание на сервере" is off. */
enum class RecognitionEngine(
    val id: String,
) {
    /** The phone's default `SpeechRecognizer`, usually Google's. Needs no setup. */
    SYSTEM("system"),

    /** The separately installed Govorun app, connected in the settings. */
    GOVORUN("govorun"),
    ;

    companion object {
        val DEFAULT = SYSTEM

        fun fromId(id: String?): RecognitionEngine = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** The rules for `SpeechRecognizer` error codes, apart from Android types. */
object SystemSpeech {
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_BUSY = 8

    private const val ERROR_NETWORK_TIMEOUT = 1
    private const val ERROR_NETWORK = 2
    private const val ERROR_SERVER = 4
    private const val ERROR_SERVER_DISCONNECTED = 11
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13

    /** The failure behind an error code; null when the person simply said nothing. */
    fun failure(code: Int): DictationFailure? =
        when (code) {
            ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH -> null
            ERROR_NETWORK_TIMEOUT, ERROR_NETWORK, ERROR_SERVER, ERROR_SERVER_DISCONNECTED ->
                DictationFailure.SYSTEM_NETWORK
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> DictationFailure.SYSTEM_LANGUAGE
            ERROR_BUSY -> DictationFailure.SYSTEM_UNAVAILABLE
            else -> DictationFailure.SYSTEM_FAILED
        }
}
