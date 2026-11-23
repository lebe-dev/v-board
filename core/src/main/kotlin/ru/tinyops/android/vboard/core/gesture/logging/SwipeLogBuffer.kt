package ru.tinyops.android.vboard.core.gesture.logging

/**
 * Fixed-size ring buffer of the most recent [SwipeLogEntry] records. Oldest entries are dropped
 * once the buffer reaches [capacity].
 */
class SwipeLogBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val entries = ArrayDeque<SwipeLogEntry>(capacity.coerceAtMost(INITIAL_ALLOC))

    val size: Int get() = entries.size

    fun add(entry: SwipeLogEntry) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    fun replaceAll(items: List<SwipeLogEntry>) {
        entries.clear()
        val start = (items.size - capacity).coerceAtLeast(0)
        for (index in start until items.size) {
            entries.addLast(items[index])
        }
    }

    fun updateOutcome(
        id: Long,
        outcome: SwipeOutcome,
        finalWord: String,
    ): Boolean {
        for (index in entries.lastIndex downTo 0) {
            val current = entries[index]
            if (current.id == id) {
                entries[index] = current.copy(outcome = outcome, finalWord = finalWord)
                return true
            }
        }
        return false
    }

    fun snapshot(): List<SwipeLogEntry> = entries.toList()

    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
        private const val INITIAL_ALLOC = 64
    }
}

/**
 * Tracks what the user does immediately after a swiped word is inserted: keeps it, picks another
 * suggestion from the strip, or deletes and retypes it.
 */
class SwipeLogTracker(
    private val onOutcome: (id: Long, outcome: SwipeOutcome, finalWord: String) -> Unit,
) {
    private var activeId: Long? = null
    private var activeInserted: String? = null
    private var awaitingRetypeId: Long? = null

    fun onSwipeCommitted(
        id: Long,
        inserted: String,
    ) {
        val pendingRetype = awaitingRetypeId
        if (pendingRetype != null) {
            awaitingRetypeId = null
            onOutcome(pendingRetype, SwipeOutcome.RETYPED, inserted)
        }
        activeId = id
        activeInserted = inserted
    }

    fun onSuggestionPicked(word: String) {
        val active = activeId
        if (active != null) {
            val same = word.equals(activeInserted, ignoreCase = true)
            val outcome = if (same) SwipeOutcome.KEPT else SwipeOutcome.PICKED_SUGGESTION
            onOutcome(active, outcome, word)
            return
        }
        val pendingRetype = awaitingRetypeId ?: return
        awaitingRetypeId = null
        onOutcome(pendingRetype, SwipeOutcome.RETYPED, word)
    }

    fun onSwipeWordDeleted() {
        val active = activeId ?: return
        activeId = null
        activeInserted = null
        awaitingRetypeId = active
        onOutcome(active, SwipeOutcome.DELETED, "")
    }

    fun onTypedWordCommitted(word: String) {
        activeId = null
        activeInserted = null
        val pendingRetype = awaitingRetypeId ?: return
        if (word.isBlank()) return
        awaitingRetypeId = null
        onOutcome(pendingRetype, SwipeOutcome.RETYPED, word)
    }

    fun onWordSettled() {
        activeId = null
        activeInserted = null
    }

    fun reset() {
        activeId = null
        activeInserted = null
        awaitingRetypeId = null
    }
}
