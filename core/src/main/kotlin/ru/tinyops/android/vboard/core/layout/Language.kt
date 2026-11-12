package ru.tinyops.android.vboard.core.layout

import java.util.Locale

/** Languages the keyboard can type in. */
enum class Language(
    val code: String,
    val displayName: String,
    val locale: Locale,
) {
    ENGLISH("en", "English", Locale.ENGLISH),
    RUSSIAN("ru", "Русский", Locale.forLanguageTag("ru")),
    ;

    /** The language the globe key switches to. */
    fun next(): Language = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromCode(code: String?): Language = entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}
