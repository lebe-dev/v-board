package ru.tinyops.android.vboard.stt.lm

private const val MIN_GROWTH = 16

private fun grown(size: Int): Int = maxOf(size + size / 2, size + MIN_GROWTH)

/** A read-only view of one node of a [WordTrie], created on demand. */
class TrieNode internal constructor(
    private val trie: WordTrie,
    private val index: Int,
) {
    val word: String? get() = trie.wordOf(index)

    /** The word's log-probability, or negative infinity when the node is not a word. */
    val logProb: Double get() = trie.logProbOf(index)

    /** The word's frequency, or zero when the node is not a word. */
    val frequency: Double get() = trie.frequencyOf(index)

    val isWord: Boolean get() = trie.isWord(index)

    val childCount: Int get() = trie.childCountOf(index)

    fun child(c: Char): TrieNode? {
        val child = trie.childOf(index, c)
        return if (child == WordTrie.NONE) null else TrieNode(trie, child)
    }
}

/** Supplies the text of a word by its index, for a trie that does not hold word strings itself. */
fun interface WordSource {
    fun wordAt(index: Int): String
}

/**
 * The storage of a [WordTrie], for keeping a built trie in a file. The node arrays have one entry
 * per node, the word arrays one per word; [wordIndex] maps a node to its word or to `-1`.
 */
@Suppress("ArrayInDataClass") // A transfer object: compared by nobody.
data class TrieArrays(
    val labels: CharArray,
    val firstChild: IntArray,
    val nextSibling: IntArray,
    val wordIndex: IntArray,
    val logProbs: DoubleArray,
    val frequencies: DoubleArray,
)

/**
 * A character trie over a vocabulary, used to prune the beam search: only
 * vocabulary prefixes are ever expanded.
 *
 * The trie has hundreds of thousands of nodes on a real vocabulary and lives in
 * the keyboard's heap for as long as the process does, so nodes are integers
 * into parallel primitive arrays (first-child / next-sibling links) rather than
 * objects: a node costs 14 bytes, plus 20 bytes for each word. A trie restored
 * by [fromArrays] holds no word strings at all, so the collector has nothing in
 * it to walk.
 */
class WordTrie {
    private var labels = CharArray(INITIAL_NODES)
    private var firstChild = IntArray(INITIAL_NODES)
    private var nextSibling = IntArray(INITIAL_NODES)

    /** Index into the word arrays below, or [NONE] for a node that is not a word. */
    private var wordIndex = IntArray(INITIAL_NODES)
    private var nodeTotal = 0

    private var words = arrayOfNulls<String>(INITIAL_WORDS)
    private var logProbs = DoubleArray(INITIAL_WORDS)
    private var frequencies = DoubleArray(INITIAL_WORDS)

    /** Replaces [words] in a trie restored by [fromArrays]. */
    private var wordSource: WordSource? = null

    var wordCount: Int = 0
        private set

    val root: TrieNode

    val isEmpty: Boolean get() = wordCount == 0

    init {
        newNode('\u0000')
        root = TrieNode(this, ROOT)
    }

    fun insert(
        word: String,
        logProb: Double,
        frequency: Double = 0.0,
    ) {
        require(word.isNotEmpty()) { "word must not be empty" }
        check(wordSource == null) { "a trie restored from arrays is read-only" }
        var node = ROOT
        for (c in word) node = childOrNew(node, c)
        var slot = wordIndex[node]
        if (slot == NONE) {
            slot = wordCount++
            wordIndex[node] = slot
            ensureWordCapacity()
        }
        words[slot] = word
        logProbs[slot] = logProb
        frequencies[slot] = frequency
    }

    fun contains(word: String): Boolean {
        val node = find(word)
        return node != NONE && isWord(node)
    }

    /** Number of nodes, the root included. Intended for diagnostics/tests. */
    fun nodeCount(): Int = nodeTotal

    /** Releases the spare capacity left by [insert]; call after loading a vocabulary. */
    fun compact() {
        labels = labels.copyOf(nodeTotal)
        firstChild = firstChild.copyOf(nodeTotal)
        nextSibling = nextSibling.copyOf(nodeTotal)
        wordIndex = wordIndex.copyOf(nodeTotal)
        words = words.copyOf(wordCount)
        logProbs = logProbs.copyOf(wordCount)
        frequencies = frequencies.copyOf(wordCount)
    }

    /** A trimmed copy of the storage, for [fromArrays]. */
    fun toArrays(): TrieArrays =
        TrieArrays(
            labels = labels.copyOf(nodeTotal),
            firstChild = firstChild.copyOf(nodeTotal),
            nextSibling = nextSibling.copyOf(nodeTotal),
            wordIndex = wordIndex.copyOf(nodeTotal),
            logProbs = logProbs.copyOf(wordCount),
            frequencies = frequencies.copyOf(wordCount),
        )

    /** The log-probability of [word], or [default] when it is not in the vocabulary. */
    internal fun logProbOrDefault(
        word: String,
        default: Double,
    ): Double {
        val node = find(word)
        return if (node != NONE && isWord(node)) logProbOf(node) else default
    }

    internal fun firstChildOf(node: Int): Int = firstChild[node]

    internal fun nextSiblingOf(node: Int): Int = nextSibling[node]

    internal fun labelOf(node: Int): Char = labels[node]

    internal fun isWord(node: Int): Boolean = wordIndex[node] != NONE

    internal fun wordOf(node: Int): String? {
        if (!isWord(node)) return null
        val slot = wordIndex[node]
        return wordSource?.wordAt(slot) ?: words[slot]
    }

    internal fun logProbOf(node: Int): Double =
        if (isWord(node)) logProbs[wordIndex[node]] else Double.NEGATIVE_INFINITY

    internal fun frequencyOf(node: Int): Double = if (isWord(node)) frequencies[wordIndex[node]] else 0.0

    internal fun childCountOf(node: Int): Int {
        var count = 0
        var child = firstChild[node]
        while (child != NONE) {
            count++
            child = nextSibling[child]
        }
        return count
    }

    internal fun childOf(
        node: Int,
        c: Char,
    ): Int {
        var child = firstChild[node]
        while (child != NONE && labels[child] != c) child = nextSibling[child]
        return child
    }

    private fun find(word: String): Int {
        var node = ROOT
        for (c in word) {
            node = childOf(node, c)
            if (node == NONE) return NONE
        }
        return node
    }

    private fun childOrNew(
        node: Int,
        c: Char,
    ): Int {
        var child = firstChild[node]
        if (child == NONE) {
            val created = newNode(c)
            firstChild[node] = created
            return created
        }
        while (labels[child] != c) {
            val next = nextSibling[child]
            if (next == NONE) {
                val created = newNode(c)
                nextSibling[child] = created
                return created
            }
            child = next
        }
        return child
    }

    private fun newNode(label: Char): Int {
        if (nodeTotal == labels.size) {
            val capacity = grown(nodeTotal)
            labels = labels.copyOf(capacity)
            firstChild = firstChild.copyOf(capacity)
            nextSibling = nextSibling.copyOf(capacity)
            wordIndex = wordIndex.copyOf(capacity)
        }
        val node = nodeTotal++
        labels[node] = label
        firstChild[node] = NONE
        nextSibling[node] = NONE
        wordIndex[node] = NONE
        return node
    }

    private fun ensureWordCapacity() {
        if (wordCount <= words.size) return
        val capacity = grown(words.size)
        words = words.copyOf(capacity)
        logProbs = logProbs.copyOf(capacity)
        frequencies = frequencies.copyOf(capacity)
    }

    companion object {
        internal const val NONE = -1
        internal const val ROOT = 0
        private const val INITIAL_NODES = 64
        private const val INITIAL_WORDS = 16

        /**
         * Restores a trie from its storage without rebuilding it. The arrays are adopted, not
         * copied; [words] supplies word text on demand, and the trie cannot grow.
         */
        fun fromArrays(
            arrays: TrieArrays,
            words: WordSource,
        ): WordTrie {
            val nodes = arrays.labels.size
            require(nodes > 0) { "a trie has at least a root node" }
            require(arrays.firstChild.size == nodes && arrays.nextSibling.size == nodes) {
                "node arrays must have the same size"
            }
            require(arrays.wordIndex.size == nodes) { "node arrays must have the same size" }
            require(arrays.logProbs.size == arrays.frequencies.size) { "word arrays must have the same size" }
            return WordTrie().apply {
                labels = arrays.labels
                firstChild = arrays.firstChild
                nextSibling = arrays.nextSibling
                wordIndex = arrays.wordIndex
                nodeTotal = nodes
                logProbs = arrays.logProbs
                frequencies = arrays.frequencies
                this.words = arrayOfNulls(0)
                wordCount = arrays.logProbs.size
                wordSource = words
            }
        }
    }
}
