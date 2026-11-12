package ru.tinyops.android.vboard.core.clipboard

/** A chip on the suggestion strip: what it shows and what a tap inserts (null for an image). */
data class ClipChip(
    val kind: ClipKind,
    val label: String,
    val insert: String?,
)

/** Builds the chips of the strip from the latest clipboard content. */
object ClipChips {
    const val MAX_CHIPS = 3

    private val whitespace = Regex("\\s+")
    private val scheme = Regex("^(?i:https?://)(?i:www\\.)?|^(?i:www\\.)")

    fun of(
        payload: ClipPayload,
        showExtracted: Boolean,
    ): List<ClipChip> =
        when (payload) {
            is ClipPayload.Image -> listOf(ClipChip(ClipKind.IMAGE, "", null))
            is ClipPayload.Text -> textChips(payload.text, showExtracted)
        }

    private fun textChips(
        text: String,
        showExtracted: Boolean,
    ): List<ClipChip> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val items = if (showExtracted) ClipExtractor.extract(text) else emptyList()
        // A copied link, address or number alone is shown as what it is, once.
        items.singleOrNull()?.takeIf { it.value == trimmed }?.let { return listOf(chip(it, insert = text)) }
        val primary = ClipChip(ClipKind.TEXT, whitespace.replace(trimmed, " "), text)
        return (listOf(primary) + items.map { chip(it, it.value) }).take(MAX_CHIPS)
    }

    private fun chip(
        item: ClipItem,
        insert: String,
    ): ClipChip {
        val label = if (item.kind == ClipKind.LINK) scheme.replace(item.value, "").trimEnd('/') else item.value
        return ClipChip(item.kind, label, insert)
    }
}
