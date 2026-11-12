package ru.tinyops.android.vboard.core.emoji

import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiRecentsTest {
    @Test
    fun `the last used emoji goes first`() {
        val recents = EmojiRecents().use("😀").use("🐸")
        assertEquals(listOf("🐸", "😀"), recents.items)
    }

    @Test
    fun `using an emoji again moves it up without a duplicate`() {
        val recents = EmojiRecents().use("😀").use("🐸").use("😀")
        assertEquals(listOf("😀", "🐸"), recents.items)
    }

    @Test
    fun `the oldest emoji leave when the capacity is exceeded`() {
        var recents = EmojiRecents(capacity = 3)
        for (emoji in listOf("a", "b", "c", "d")) recents = recents.use(emoji)
        assertEquals(listOf("d", "c", "b"), recents.items)
    }

    @Test
    fun `recents survive being written to text and read back`() {
        val recents = EmojiRecents().use("😀").use("👨‍👩‍👧").use("🇷🇺")
        assertEquals(recents.items, EmojiRecents.decode(recents.encode()).items)
    }

    @Test
    fun `broken stored text gives empty recents`() {
        assertEquals(emptyList(), EmojiRecents.decode(null).items)
        assertEquals(emptyList(), EmojiRecents.decode("").items)
    }
}
