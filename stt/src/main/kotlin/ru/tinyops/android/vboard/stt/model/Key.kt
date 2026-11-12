package ru.tinyops.android.vboard.stt.model

/**
 * A single key of a [KeyboardLayout] with an axis-aligned hitbox in keyboard
 * coordinate space.
 */
data class Key(
    val code: Int,
    val label: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The label's single character, lowercased, or null for non-character keys. */
    val character: Char?
        get() = label.singleOrNull()?.takeIf { it.isLetter() }?.lowercaseChar()

    val isCharacter: Boolean get() = character != null

    fun contains(
        x: Float,
        y: Float,
    ): Boolean = x in left..right && y in top..bottom
}
