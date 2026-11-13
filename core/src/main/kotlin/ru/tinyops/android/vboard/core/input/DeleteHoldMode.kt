package ru.tinyops.android.vboard.core.input

/** What holding the delete key does after the first character is gone. */
enum class DeleteHoldMode(
    val code: String,
) {
    CHARACTERS("characters"),
    WORDS("words"),
    ALL("all"),
    NONE("none"),
    ;

    companion object {
        fun fromCode(code: String?): DeleteHoldMode = entries.firstOrNull { it.code == code } ?: CHARACTERS
    }
}
