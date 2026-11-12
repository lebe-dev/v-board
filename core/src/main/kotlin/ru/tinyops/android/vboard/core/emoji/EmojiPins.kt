package ru.tinyops.android.vboard.core.emoji

/** The emoji the user pinned, the one pinned last first. Immutable. */
data class EmojiPins(
    val items: List<String> = emptyList(),
) {
    operator fun contains(emoji: String): Boolean = emoji in items

    /** Unpins a pinned emoji and pins any other one, to the front. There is no limit. */
    fun toggle(emoji: String): EmojiPins =
        if (emoji in items) copy(items = items - emoji) else copy(items = listOf(emoji) + items)

    /**
     * Puts the pins of [shown] in that order. Pins missing from [shown] (hidden ones, which the
     * picker does not list) keep their positions, and anything in [shown] that is not pinned is ignored.
     */
    fun reordered(shown: List<String>): EmojiPins {
        val moved = shown.filter { it in items }.distinct()
        val next = moved.iterator()
        return copy(items = items.map { if (it in moved && next.hasNext()) next.next() else it })
    }

    /** [emoji] without the pinned ones, in the same order. */
    fun leftOutOf(emoji: List<String>): List<String> = emoji.filterNot { it in items }

    /** One line for the preferences: emoji never contain a space, so it separates them. */
    fun encode(): String = items.joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = " "

        fun decode(stored: String?): EmojiPins = EmojiPins(stored.orEmpty().split(SEPARATOR).filter(String::isNotEmpty))
    }
}
