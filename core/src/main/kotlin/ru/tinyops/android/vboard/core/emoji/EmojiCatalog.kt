package ru.tinyops.android.vboard.core.emoji

/** The tabs of the emoji picker, in display order. [id] is the name used in the catalog file. */
enum class EmojiCategory(
    val id: String,
) {
    SMILEYS("smileys"),
    PEOPLE("people"),
    NATURE("nature"),
    FOOD("food"),
    ACTIVITIES("activities"),
    TRAVEL("travel"),
    OBJECTS("objects"),
    SYMBOLS("symbols"),
    FLAGS("flags"),
    ;

    companion object {
        fun byId(id: String): EmojiCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One emoji of the picker.
 *
 * @param text what is committed to the editor
 * @param image file name of its picture, without extension
 * @param keywords words the emoji can be found by
 * @param subcategory the group inside the category (sports, games...); empty if the catalog has none
 */
data class Emoji(
    val category: EmojiCategory,
    val text: String,
    val image: String,
    val keywords: List<String>,
    val subcategory: String = "",
)

/** Every emoji of the picker, grouped by category. */
class EmojiCatalog private constructor(
    val all: List<Emoji>,
) {
    private val byCategory = all.groupBy { it.category }
    private val byText = all.associateBy { it.text }

    /** Categories that have emoji, in tab order. */
    val categories: List<EmojiCategory> = EmojiCategory.entries.filter { it in byCategory }

    fun of(category: EmojiCategory): List<Emoji> = byCategory[category].orEmpty()

    fun find(text: String): Emoji? = byText[text]

    fun of(
        category: EmojiCategory,
        subcategory: String,
    ): List<Emoji> = of(category).filter { it.subcategory == subcategory }

    /** The subcategories of [category] in the order they first appear. */
    fun subcategories(category: EmojiCategory): List<String> = of(category).map { it.subcategory }.distinct()

    companion object {
        /**
         * Reads tab-separated lines `category, emoji, image, keywords[, subcategory]`. Blank lines
         * and lines of an unknown category are skipped.
         */
        fun parse(lines: Sequence<String>): EmojiCatalog {
            val emoji =
                lines.mapNotNull { line ->
                    val fields = line.split('\t')
                    if (fields.size < FIELDS) return@mapNotNull null
                    val category = EmojiCategory.byId(fields[CATEGORY]) ?: return@mapNotNull null
                    val keywords = fields[KEYWORDS].split(' ').filter(String::isNotEmpty)
                    Emoji(category, fields[TEXT], fields[IMAGE], keywords, fields.getOrElse(SUBCATEGORY) { "" })
                }
            return EmojiCatalog(emoji.sortedBy { it.category.ordinal }.toList())
        }

        private const val CATEGORY = 0
        private const val TEXT = 1
        private const val IMAGE = 2
        private const val KEYWORDS = 3
        private const val SUBCATEGORY = 4
        private const val FIELDS = 4
    }
}
