package ru.tinyops.android.vboard.core.emoji

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiHiddenTest {
    private val catalog =
        EmojiCatalog.parse(
            """
            smileys	😀	1f600	grinning	faces
            smileys	😍	1f60d	heart eyes	faces
            smileys	❤️	2764-fe0f	heart	hearts
            nature	🐸	1f438	frog	animals
            flags	🇷🇺	1f1f7-1f1fa	ru	countries
            """.trimIndent().lineSequence(),
        )

    @Test
    fun `nothing is hidden at first`() {
        val hidden = EmojiHidden()
        assertEquals(catalog.categories, hidden.categoriesIn(catalog))
        assertEquals(3, hidden.emojiIn(catalog, EmojiCategory.SMILEYS).size)
        assertFalse(hidden.hides(catalog, "😀"))
    }

    @Test
    fun `a hidden category disappears with all its emoji`() {
        val hidden = EmojiHidden().toggleCategory(EmojiCategory.FLAGS)
        assertEquals(listOf(EmojiCategory.SMILEYS, EmojiCategory.NATURE), hidden.categoriesIn(catalog))
        assertTrue(hidden.hides(catalog, "🇷🇺"))
        assertEquals(emptyList(), hidden.emojiIn(catalog, EmojiCategory.FLAGS))
    }

    @Test
    fun `a hidden emoji disappears from its category and can be shown again`() {
        val hidden = EmojiHidden().toggleEmoji("😍")
        assertEquals(listOf("😀", "❤️"), hidden.emojiIn(catalog, EmojiCategory.SMILEYS).map { it.text })
        assertTrue(hidden.hides(catalog, "😍"))
        assertFalse(hidden.toggleEmoji("😍").hides(catalog, "😍"))
    }

    @Test
    fun `a category with every emoji hidden has no tab`() {
        val hidden = EmojiHidden().toggleEmoji("🐸")
        assertEquals(listOf(EmojiCategory.SMILEYS, EmojiCategory.FLAGS), hidden.categoriesIn(catalog))
    }

    @Test
    fun `the last visible category cannot be hidden`() {
        var hidden = EmojiHidden()
        for (category in catalog.categories) hidden = hidden.toggleCategory(category, catalog)
        assertEquals(1, hidden.categoriesIn(catalog).size)
    }

    @Test
    fun `an emoji outside the catalog is not hidden`() {
        assertFalse(EmojiHidden().toggleCategory(EmojiCategory.FLAGS).hides(catalog, "🦖"))
    }

    @Test
    fun `what is hidden survives being written to text and read back`() {
        val hidden = EmojiHidden().toggleCategory(EmojiCategory.FLAGS).toggleEmoji("😀").toggleEmoji("👨‍👩‍👧")
        assertEquals(hidden, EmojiHidden.decode(hidden.encode()))
        assertEquals(EmojiHidden(), EmojiHidden.decode(null))
        assertEquals(EmojiHidden(), EmojiHidden.decode(""))
    }

    @Test
    fun `unknown category names in the stored text are ignored`() {
        assertEquals(EmojiHidden(), EmojiHidden.decode("bogus\n"))
    }

    @Test
    fun `showing everything again empties the sets`() {
        val hidden = EmojiHidden().toggleCategory(EmojiCategory.FLAGS).toggleEmoji("😀")
        assertTrue(hidden.any)
        assertFalse(EmojiHidden().any)
    }

    @Test
    fun `a hidden subcategory takes its emoji out and leaves the others`() {
        val hidden = EmojiHidden().toggleSubcategory("hearts", catalog)
        assertEquals(listOf("😀", "😍"), hidden.emojiIn(catalog, EmojiCategory.SMILEYS).map { it.text })
        assertTrue(hidden.hides(catalog, "❤️"))
        assertFalse(hidden.hides(catalog, "😀"))
        assertFalse(hidden.toggleSubcategory("hearts", catalog).hides(catalog, "❤️"))
    }

    @Test
    fun `a category without a shown subcategory has no tab`() {
        val hidden = EmojiHidden().toggleSubcategory("animals", catalog)
        assertEquals(listOf(EmojiCategory.SMILEYS, EmojiCategory.FLAGS), hidden.categoriesIn(catalog))
        assertFalse(hidden.isShown(catalog, EmojiCategory.NATURE))
    }

    @Test
    fun `the last visible subcategory cannot be hidden`() {
        var hidden = EmojiHidden()
        for (sub in listOf("faces", "hearts", "animals", "countries")) hidden = hidden.toggleSubcategory(sub, catalog)
        assertEquals(1, hidden.categoriesIn(catalog).size)
    }

    @Test
    fun `showing a category brings back its subcategories and emoji`() {
        val hidden =
            EmojiHidden()
                .toggleSubcategory("hearts", catalog)
                .toggleEmoji("😀")
                .toggleCategory(EmojiCategory.SMILEYS, catalog)
        assertFalse(hidden.isShown(catalog, EmojiCategory.SMILEYS))
        val shown = hidden.toggleCategory(EmojiCategory.SMILEYS, catalog)
        assertEquals(3, shown.emojiIn(catalog, EmojiCategory.SMILEYS).size)
        assertFalse(shown.hides(catalog, "❤️"))
    }

    @Test
    fun `showing a category whose emoji were all hidden one by one works`() {
        val hidden = EmojiHidden().toggleEmoji("🐸")
        assertFalse(hidden.isShown(catalog, EmojiCategory.NATURE))
        assertTrue(hidden.toggleCategory(EmojiCategory.NATURE, catalog).isShown(catalog, EmojiCategory.NATURE))
    }

    @Test
    fun `hidden subcategories survive being written to text and read back`() {
        val hidden = EmojiHidden().toggleSubcategory("hearts", catalog).toggleEmoji("😀")
        assertEquals(hidden, EmojiHidden.decode(hidden.encode()))
        assertEquals(EmojiHidden(emoji = setOf("😀")), EmojiHidden.decode("\n😀"))
    }
}
