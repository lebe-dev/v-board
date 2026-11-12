package ru.tinyops.android.vboard.core.clipboard

/** The remembered clipboard items, newest first. Immutable: every change returns a new history. */
data class ClipHistory(
    val entries: List<ClipEntry> = emptyList(),
    val capacity: Int = DEFAULT_CAPACITY,
) {
    /** The new history and the entries that left it, so the app can release what they held. */
    data class Change(
        val history: ClipHistory,
        val dropped: List<ClipEntry>,
    )

    /** Puts [entry] on top. An entry with the same content is replaced, not duplicated. */
    fun add(entry: ClipEntry): Change {
        val rest = entries.filter { it.payload != entry.payload }
        val next = listOf(entry) + rest
        val kept = next.take(capacity)
        // An image file shared with the new entry is still in use.
        val dropped = next.drop(capacity)
        return Change(copy(entries = kept), dropped)
    }

    fun remove(id: Long): Change {
        val (gone, kept) = entries.partition { it.id == id }
        return Change(copy(entries = kept), gone)
    }

    fun clear(): Change = Change(copy(entries = emptyList()), entries)

    companion object {
        const val DEFAULT_CAPACITY = 30
    }
}
