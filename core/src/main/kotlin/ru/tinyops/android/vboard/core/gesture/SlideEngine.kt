package ru.tinyops.android.vboard.core.gesture

import ru.tinyops.android.vboard.core.layout.Language

/** The implementation that turns a finished gesture into words. */
enum class SlideEngine(
    val code: String,
) {
    /** The beam search decoder of the stt module. */
    DEFAULT("default"),

    /** A neural model; it knows only the Russian layout, other languages keep the default decoder. */
    EXPERIMENTAL("experimental"),
    ;

    fun usesNeuralModel(language: Language): Boolean = this == EXPERIMENTAL && language == Language.RUSSIAN

    companion object {
        fun fromCode(code: String?): SlideEngine = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
