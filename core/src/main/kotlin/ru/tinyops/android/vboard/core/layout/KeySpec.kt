package ru.tinyops.android.vboard.core.layout

/** What a key does when pressed. */
enum class KeyType {
    /** Commits [KeySpec.output]. */
    CHARACTER,
    SHIFT,
    BACKSPACE,

    /** Switches to the first symbols page. */
    SYMBOLS,

    /** Switches back to letters. */
    ALPHABET,

    /** Switches to the second symbols page. */
    MORE_SYMBOLS,

    /** Switches between the supported languages. */
    LANGUAGE,
    SPACE,
    ENTER,
}

/** Visual weight of a key; mapped to colors and shapes by the renderer. */
enum class KeyStyle {
    /** Regular character key (light gray). */
    LETTER,

    /** Auxiliary key such as shift, backspace or punctuation (dark gray). */
    FUNCTION,

    /** Layout switch key rendered as a pill (`?123`, `ABC`). */
    MODE,

    /** The editor action key rendered as an accent pill. */
    ACTION,
}

/**
 * A key of a keyboard layout, independent of screen geometry.
 *
 * @param label text drawn on the key (lowercase for letters; the renderer applies shift)
 * @param output text committed by a [KeyType.CHARACTER] key
 * @param hint small secondary label drawn in the top-right corner
 * @param alternates characters offered on long press; the first one is preselected
 * @param width width in key units (a regular letter key is 1)
 */
data class KeySpec(
    val type: KeyType,
    val label: String = "",
    val output: String = label,
    val hint: String? = null,
    val alternates: List<String> = emptyList(),
    val width: Float = 1f,
    val style: KeyStyle = KeyStyle.LETTER,
) {
    /** The key shows the emoji icon in its corner: a long press on it can open the emoji picker. */
    val showsEmojiHint: Boolean
        get() = EMOJI_ALTERNATE in alternates

    /** True for a key that types a single letter and can therefore take part in a gesture. */
    val isLetter: Boolean
        get() = type == KeyType.CHARACTER && output.length == 1 && output[0].isLetter()

    companion object {
        /**
         * An entry of [alternates] that is not a character: the popup draws the emoji icon and
         * choosing it opens the emoji picker. A control character cannot be typed by accident.
         */
        const val EMOJI_ALTERNATE = "\u0001emoji"
    }
}

/**
 * A row of keys. [leftPadding] and [rightPadding] are empty space in key units, used for the
 * half-key indent of the middle QWERTY row.
 */
data class KeyRow(
    val keys: List<KeySpec>,
    val leftPadding: Float = 0f,
    val rightPadding: Float = 0f,
) {
    val totalWidth: Float get() = leftPadding + keys.sumOf { it.width.toDouble() }.toFloat() + rightPadding
}

/** Which page of the keyboard is shown. */
enum class KeyboardMode {
    LETTERS,
    SYMBOLS,
    MORE_SYMBOLS,
}

/** A complete keyboard page. */
data class KeyboardSpec(
    val language: Language,
    val mode: KeyboardMode,
    val rows: List<KeyRow>,
) {
    /** Width of the widest row in key units. */
    val columns: Float get() = rows.maxOf { it.totalWidth }
}
