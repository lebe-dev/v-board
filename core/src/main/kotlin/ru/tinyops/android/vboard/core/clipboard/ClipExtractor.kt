package ru.tinyops.android.vboard.core.clipboard

enum class ClipKind { TEXT, LINK, EMAIL, PHONE, IMAGE }

/** A link, an address or a phone number found in a copied text. */
data class ClipItem(
    val kind: ClipKind,
    val value: String,
)

/** Finds the links, e-mail addresses and phone numbers of a text, in the order of appearance. */
object ClipExtractor {
    private const val TOP_LEVEL_DOMAINS =
        "com|org|net|io|ai|ru|su|dev|app|me|co|info|biz|xyz|tv|gg|ly|by|ua|kz|de|uk|fr|us|ca|eu|cc|to|sh|so|" +
            "tech|online|site|cloud|рф"
    private const val MIN_PHONE_DIGITS = 10
    private const val MAX_PHONE_DIGITS = 15
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}»\"'"

    private val pattern =
        Regex(
            "(?<url>(?i:https?://|www\\.)[^\\s<>\"']+)" +
                "|(?<email>[\\p{L}\\d._%+-]+@[\\p{L}\\d-]+(?:\\.[\\p{L}\\d-]+)*\\.[\\p{L}]{2,})" +
                "|(?<domain>(?<![\\p{L}\\d@._/-])" +
                "(?:[\\p{L}\\d](?:[\\p{L}\\d-]*[\\p{L}\\d])?\\.)+(?i:$TOP_LEVEL_DOMAINS)" +
                "(?![\\p{L}\\d-])(?:/[^\\s<>\"']*)?)" +
                "|(?<phone>(?<![\\d+])\\+?\\d[\\d\\s().-]{8,18}\\d)",
        )

    fun extract(text: String): List<ClipItem> {
        val found = LinkedHashSet<ClipItem>()
        for (match in pattern.findAll(text)) {
            val groups = match.groups as MatchNamedGroupCollection
            val item =
                when {
                    groups["url"] != null -> link(match.value)
                    groups["email"] != null -> ClipItem(ClipKind.EMAIL, match.value)
                    groups["domain"] != null -> link(match.value)
                    else -> phone(match.value)
                }
            if (item != null) found += item
        }
        return found.toList()
    }

    private fun link(raw: String): ClipItem? {
        val value = raw.trimEnd { it in TRAILING_PUNCTUATION }
        return value.takeIf { it.isNotEmpty() }?.let { ClipItem(ClipKind.LINK, it) }
    }

    private fun phone(raw: String): ClipItem? {
        val value = raw.trim()
        val digits = value.count { it.isDigit() }
        if (digits !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) return null
        return ClipItem(ClipKind.PHONE, value)
    }
}
