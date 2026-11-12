package ru.tinyops.android.vboard.core.emoji

/** The emoji used last, newest first. Immutable: [use] returns a new list. */
data class EmojiRecents(
    val items: List<String> = emptyList(),
    val capacity: Int = DEFAULT_CAPACITY,
) {
    fun use(emoji: String): EmojiRecents = copy(items = (listOf(emoji) + items.filter { it != emoji }).take(capacity))

    /** One line for the preferences: emoji never contain a space, so it separates them. */
    fun encode(): String = items.joinToString(SEPARATOR)

    companion object {
        const val DEFAULT_CAPACITY = 36
        private const val SEPARATOR = " "

        fun decode(
            stored: String?,
            capacity: Int = DEFAULT_CAPACITY,
        ): EmojiRecents =
            EmojiRecents(
                stored
                    .orEmpty()
                    .split(SEPARATOR)
                    .filter(String::isNotEmpty)
                    .take(capacity),
                capacity,
            )
    }
}
