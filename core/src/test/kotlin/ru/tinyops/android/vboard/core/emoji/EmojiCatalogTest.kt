package ru.tinyops.android.vboard.core.emoji

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmojiCatalogTest {
    private val tsv =
        """
        smileys	😀	1f600	grinning	faces
        smileys	😍	1f60d	heart eyes smiling face with heart-eyes	faces
        smileys	❤️	2764-fe0f	red heart	hearts
        nature	🐸	1f438	frog
        bogus	X	x	unknown category is dropped
        
        flags	🇷🇺	1f1f7-1f1fa	ru flag-ru
        """.trimIndent()

    private val catalog = EmojiCatalog.parse(tsv.lineSequence())

    @Test
    fun `emoji are grouped by category in the order of the tabs`() {
        assertEquals(listOf(EmojiCategory.SMILEYS, EmojiCategory.NATURE, EmojiCategory.FLAGS), catalog.categories)
        assertEquals(listOf("😀", "😍", "❤️"), catalog.of(EmojiCategory.SMILEYS).map { it.text })
        assertEquals(emptyList(), catalog.of(EmojiCategory.FOOD))
    }

    @Test
    fun `lines of unknown categories and blank lines are skipped`() {
        assertEquals(5, catalog.all.size)
    }

    @Test
    fun `an emoji knows its picture name and keywords`() {
        val flag = catalog.of(EmojiCategory.FLAGS).single()
        assertEquals("1f1f7-1f1fa", flag.image)
        assertEquals(listOf("ru", "flag-ru"), flag.keywords)
    }

    @Test
    fun `a line without the subcategory column still reads`() {
        val old = EmojiCatalog.parse(sequenceOf("food\t🍎\t1f34e\tapple"))
        assertEquals("", old.all.single().subcategory)
    }

    @Test
    fun `subcategories of a category come in the order they first appear`() {
        assertEquals(listOf("faces", "hearts"), catalog.subcategories(EmojiCategory.SMILEYS))
        assertEquals(listOf(""), catalog.subcategories(EmojiCategory.NATURE))
        assertEquals(listOf("❤️"), catalog.of(EmojiCategory.SMILEYS, "hearts").map { it.text })
    }

    @Test
    fun `find returns the entry of an emoji text`() {
        assertEquals("1f438", catalog.find("🐸")?.image)
        assertNull(catalog.find("🦖"))
    }
}
