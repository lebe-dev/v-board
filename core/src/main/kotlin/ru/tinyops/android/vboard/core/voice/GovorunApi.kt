package ru.tinyops.android.vboard.core.voice

/** Why a dictation ended early, in terms of what the user can do about it. */
enum class DictationFailure {
    /** Silero VAD could not be loaded (recognition on the user's server). */
    VAD,

    /** The Govorun app is missing, too old or its service could not be reached. */
    UNAVAILABLE,

    /** The user has not allowed this keyboard in Govorun, or revoked it. */
    UNAUTHORIZED,

    /** Govorun has not finished unpacking its model. */
    MODEL_NOT_READY,

    /** Govorun's recognition is taken, for example by its own dictation. */
    BUSY,

    RECOGNITION,

    /** The phone has no default recognizer, or it refused to start. */
    SYSTEM_UNAVAILABLE,

    /** The system recognizer needs a connection it does not have. */
    SYSTEM_NETWORK,

    /** The system recognizer has no model for the keyboard's language. */
    SYSTEM_LANGUAGE,

    SYSTEM_FAILED,
}

/** The contract of Govorun's Recognition API (`com.govorun.api.v1`), apart from Android types. */
object GovorunApi {
    const val PACKAGE = "com.govorun.lite"
    const val SERVICE_ACTION = "com.govorun.api.RECOGNITION_SERVICE"

    const val AUTHORIZATION_GRANTED = 1

    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16

    const val PROFILE_DEFAULT = 0
    const val PROFILE_TASK_FLOW = 1

    const val ERROR_CANCELED = 7

    private const val PROTOCOL_VERSION = 1
    private const val CAPABILITY_CONTINUOUS_PROFILES = 1
    private const val ERROR_UNAUTHORIZED = 1
    private const val ERROR_BUSY = 2
    private const val ERROR_MODEL_UNAVAILABLE = 3
    private const val ERROR_PREEMPTED = 9

    /** The API takes no pause length: only 0.5 s or the value chosen in Govorun itself. */
    fun profile(pause: PauseLength): Int = if (pause == PauseLength.SHORT) PROFILE_TASK_FLOW else PROFILE_DEFAULT

    /** What the settings show for a stored pause; [PauseLength.AUTO] stands for "as in Govorun". */
    fun pauseChoice(pause: PauseLength): PauseLength = if (pause == PauseLength.SHORT) pause else PauseLength.AUTO

    /** The failure behind an `onError` code; null for a session that was simply canceled. */
    fun failure(code: Int): DictationFailure? =
        when (code) {
            ERROR_CANCELED -> null
            ERROR_UNAUTHORIZED -> DictationFailure.UNAUTHORIZED
            ERROR_MODEL_UNAVAILABLE -> DictationFailure.MODEL_NOT_READY
            ERROR_BUSY, ERROR_PREEMPTED -> DictationFailure.BUSY
            else -> DictationFailure.RECOGNITION
        }

    /** Whether this keyboard can talk to a Govorun that reports these values. */
    fun supports(
        protocol: Int,
        capabilities: Int,
    ): Boolean = protocol == PROTOCOL_VERSION && capabilities and CAPABILITY_CONTINUOUS_PROFILES != 0
}
