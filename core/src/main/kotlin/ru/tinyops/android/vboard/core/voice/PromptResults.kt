package ru.tinyops.android.vboard.core.voice

/** A prompt as it was when a result was kept: the id to group results by and the title to show. */
data class PromptTag(
    val id: String,
    val title: String,
)

/**
 * One kept text: the raw [Kind.TRANSCRIPT] of a dictation, or the [Kind.ANSWER] a model gave for
 * [prompts]. [id] is the time it was kept in milliseconds.
 */
data class PromptResult(
    val id: Long,
    val text: String,
    val kind: Kind,
    val prompts: List<PromptTag> = emptyList(),
) {
    enum class Kind(
        val id: String,
    ) {
        TRANSCRIPT("transcript"),
        ANSWER("answer"),
        ;

        companion object {
            fun fromId(id: String?): Kind? = entries.firstOrNull { it.id == id }
        }
    }
}

/** What the list of prompt results shows. */
sealed interface PromptFilter {
    data object All : PromptFilter

    /** Only the raw texts of dictations. */
    data object Transcripts : PromptFilter

    /** Only the answers to the prompt [id]. */
    data class Prompt(
        val id: String,
        val title: String,
    ) : PromptFilter
}

/** The kept prompt results, newest first. Immutable: every change returns a new history. */
data class PromptResultHistory(
    val entries: List<PromptResult> = emptyList(),
    val capacity: Int = DEFAULT_CAPACITY,
) {
    /**
     * Puts [results] on top in the given order. A kept result with the same text and kind is
     * replaced, not repeated; blank texts are skipped.
     */
    fun add(results: List<PromptResult>): PromptResultHistory {
        val fresh = results.filter { it.text.isNotBlank() }
        if (fresh.isEmpty()) return this
        val rest = entries.filter { old -> fresh.none { it.text == old.text && it.kind == old.kind } }
        return copy(entries = (fresh + rest).take(capacity))
    }

    fun remove(id: Long): PromptResultHistory = copy(entries = entries.filterNot { it.id == id })

    fun clear(): PromptResultHistory = copy(entries = emptyList())

    /**
     * Puts the results [ids] in this order; ids it does not keep are skipped. The results not named stay
     * where they are, so a filtered part of the list is reordered among its own places.
     */
    fun reorder(ids: List<Long>): PromptResultHistory {
        val byId = entries.associateBy { it.id }
        val moved = ids.distinct().mapNotNull(byId::get)
        if (moved.isEmpty()) return this
        val movedIds = moved.mapTo(HashSet()) { it.id }
        val next = moved.iterator()
        return copy(entries = entries.map { if (it.id in movedIds) next.next() else it })
    }

    /** "All", then "Transcripts" when there are any, then every prompt once, the most recently used first. */
    fun filters(): List<PromptFilter> {
        val prompts =
            entries
                .flatMap { it.prompts }
                .distinctBy { it.id }
                .map { PromptFilter.Prompt(it.id, it.title) }
        val transcripts = entries.any { it.kind == PromptResult.Kind.TRANSCRIPT }
        return listOf(PromptFilter.All) + listOfNotNull(PromptFilter.Transcripts.takeIf { transcripts }) + prompts
    }

    /** [filter] as the history offers it now; one that matches nothing any more becomes [PromptFilter.All]. */
    fun available(filter: PromptFilter): PromptFilter {
        val offered = filters()
        return when (filter) {
            is PromptFilter.Prompt -> offered.firstOrNull { it is PromptFilter.Prompt && it.id == filter.id }
            else -> offered.firstOrNull { it == filter }
        } ?: PromptFilter.All
    }

    fun matching(filter: PromptFilter): List<PromptResult> =
        when (filter) {
            PromptFilter.All -> entries
            PromptFilter.Transcripts -> entries.filter { it.kind == PromptResult.Kind.TRANSCRIPT }
            is PromptFilter.Prompt -> entries.filter { result -> result.prompts.any { it.id == filter.id } }
        }

    companion object {
        const val DEFAULT_CAPACITY = 50
    }
}

/**
 * The pieces of one dictation as they reach the field: what was recognized and what was inserted
 * in its place. Kept only when the model changed at least one of them.
 */
class DictationResults {
    data class Texts(
        val transcript: String,
        val improved: String,
    )

    private val transcript = ArrayList<String>()
    private val improved = ArrayList<String>()
    private var changed = false

    fun add(
        recognized: String,
        inserted: String,
        improved: Boolean,
    ) {
        transcript += recognized
        this.improved += inserted
        if (improved && recognized.trim() != inserted.trim()) changed = true
    }

    /** The raw and the improved text of the whole dictation, or null when the model changed nothing. */
    fun texts(): Texts? {
        if (!changed) return null
        return Texts(joined(transcript), joined(improved))
    }

    private fun joined(pieces: List<String>) = pieces.map(String::trim).filter(String::isNotEmpty).joinToString(" ")
}
