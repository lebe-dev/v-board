package ru.tinyops.android.vboard.core.emoji

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiPinsTest {
    @Test
    fun `toggling pins an emoji and toggling again unpins it`() {
        val pinned = EmojiPins().toggle("😀")
        assertTrue("😀" in pinned)
        assertFalse("😀" in pinned.toggle("😀"))
    }

    @Test
    fun `the emoji pinned last goes first`() {
        assertEquals(
            listOf("c", "b", "a"),
            EmojiPins()
                .toggle("a")
                .toggle("b")
                .toggle("c")
                .items,
        )
    }

    @Test
    fun `unpinning keeps the order of the others`() {
        assertEquals(
            listOf("c", "a"),
            EmojiPins()
                .toggle("a")
                .toggle("b")
                .toggle("c")
                .toggle("b")
                .items,
        )
    }

    @Test
    fun `there is no limit to the number of pins`() {
        var pins = EmojiPins()
        for (i in 1..500) pins = pins.toggle("e$i")
        assertEquals(500, pins.items.size)
        assertEquals(500, EmojiPins.decode(pins.encode()).items.size)
    }

    @Test
    fun `reordering takes the order that was shown`() {
        val pins = EmojiPins(listOf("a", "b", "c"))
        assertEquals(listOf("c", "a", "b"), pins.reordered(listOf("c", "a", "b")).items)
    }

    @Test
    fun `reordering keeps the place of pins that were not shown`() {
        val pins = EmojiPins(listOf("a", "hidden", "b", "c"))
        assertEquals(listOf("c", "hidden", "a", "b"), pins.reordered(listOf("c", "a", "b")).items)
    }

    @Test
    fun `reordering ignores emoji that are not pinned and loses none that are`() {
        val pins = EmojiPins(listOf("a", "b", "c"))
        assertEquals(listOf("b", "a", "c"), pins.reordered(listOf("x", "b", "a")).items)
    }

    @Test
    fun `pinned emoji are left out of other lists, which keep their order`() {
        val pins = EmojiPins(listOf("b", "d"))
        assertEquals(listOf("a", "c"), pins.leftOutOf(listOf("a", "b", "c", "d")))
    }

    @Test
    fun `pins survive being written to text and read back`() {
        val pins = EmojiPins().toggle("😀").toggle("👨‍👩‍👧").toggle("🇷🇺")
        assertEquals(pins.items, EmojiPins.decode(pins.encode()).items)
        assertEquals(emptyList(), EmojiPins.decode(null).items)
    }
}
