package ru.tinyops.android.vboard.core.emoji

/**
 * What the user removed from the picker: whole categories, subcategories and single emoji.
 * Immutable.
 */
data class EmojiHidden(
    val categories: Set<EmojiCategory> = emptySet(),
    val emoji: Set<String> = emptySet(),
    val subcategories: Set<String> = emptySet(),
) {
    val any: Boolean get() = categories.isNotEmpty() || emoji.isNotEmpty() || subcategories.isNotEmpty()

    /** True if the category has something left to show. */
    fun isShown(
        catalog: EmojiCatalog,
        category: EmojiCategory,
    ): Boolean = emojiIn(catalog, category).isNotEmpty()

    /**
     * Hides a shown category or brings back a category that is not shown, together with its
     * subcategories and emoji that were hidden. With [catalog] given, the last category that is
     * still shown cannot be hidden: the picker would be empty.
     */
    fun toggleCategory(
        category: EmojiCategory,
        catalog: EmojiCatalog? = null,
    ): EmojiHidden {
        if (catalog == null) {
            return if (category in
                categories
            ) {
                copy(categories = categories - category)
            } else {
                copy(categories = categories + category)
            }
        }
        if (!isShown(catalog, category)) return show(catalog, category)
        if (categoriesIn(catalog) == listOf(category)) return this
        return copy(categories = categories + category)
    }

    /** Hides or shows a subcategory; the last one that is still shown cannot be hidden. */
    fun toggleSubcategory(
        subcategory: String,
        catalog: EmojiCatalog,
    ): EmojiHidden {
        if (subcategory in subcategories) return copy(subcategories = subcategories - subcategory)
        val next = copy(subcategories = subcategories + subcategory)
        return if (next.categoriesIn(catalog).isEmpty()) this else next
    }

    fun toggleEmoji(text: String): EmojiHidden =
        if (text in emoji) copy(emoji = emoji - text) else copy(emoji = emoji + text)

    /** True for an emoji that is hidden itself or through its subcategory or category. */
    fun hides(
        catalog: EmojiCatalog,
        text: String,
    ): Boolean {
        if (text in emoji) return true
        val found = catalog.find(text) ?: return false
        return found.category in categories || found.subcategory in subcategories
    }

    /** The emoji of [category] that are shown, in catalog order. */
    fun emojiIn(
        catalog: EmojiCatalog,
        category: EmojiCategory,
    ): List<Emoji> =
        if (category in categories) {
            emptyList()
        } else {
            catalog.of(category).filter { it.text !in emoji && it.subcategory !in subcategories }
        }

    /** The categories that have something to show, in tab order. */
    fun categoriesIn(catalog: EmojiCatalog): List<EmojiCategory> =
        catalog.categories.filter { emojiIn(catalog, it).isNotEmpty() }

    /** Three lines for the preferences: category ids, hidden emoji and subcategory ids, each separated by spaces. */
    fun encode(): String =
        listOf(categories.map { it.id }, emoji.toList(), subcategories.toList())
            .joinToString("\n") { it.joinToString(SEPARATOR) }

    private fun show(
        catalog: EmojiCatalog,
        category: EmojiCategory,
    ): EmojiHidden {
        val own = catalog.of(category)
        return EmojiHidden(
            categories = categories - category,
            emoji = emoji - own.map { it.text }.toSet(),
            subcategories = subcategories - own.map { it.subcategory }.toSet(),
        )
    }

    companion object {
        private const val SEPARATOR = " "

        fun decode(stored: String?): EmojiHidden {
            val lines = stored.orEmpty().lines()

            fun words(index: Int) =
                lines
                    .getOrNull(index)
                    .orEmpty()
                    .split(SEPARATOR)
                    .filter(String::isNotEmpty)
            return EmojiHidden(
                categories = words(0).mapNotNull(EmojiCategory::byId).toSet(),
                emoji = words(1).toSet(),
                subcategories = words(2).toSet(),
            )
        }
    }
}
