package ru.tinyops.android.vboard.core.update

/**
 * A semantic version such as `1.2.0` or `1.2.0-beta.1`, as in the release tags (with the `v` prefix)
 * and in the version name of the app. A pre-release is older than the release it precedes.
 */
class AppVersion private constructor(
    private val text: String,
    private val numbers: List<Int>,
    private val preRelease: List<String>,
) : Comparable<AppVersion> {
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until NUMBERS) {
            val diff = numbers[i].compareTo(other.numbers[i])
            if (diff != 0) return diff
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return other.preRelease.size.coerceAtMost(1) - preRelease.size.coerceAtMost(1)
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val diff = comparePart(preRelease[i], other.preRelease[i])
            if (diff != 0) return diff
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0

    override fun hashCode(): Int = numbers.hashCode() * 31 + preRelease.hashCode()

    override fun toString(): String = text

    companion object {
        private const val NUMBERS = 3
        private const val PRE_RELEASE_GROUP = NUMBERS + 1
        private val PATTERN = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Parses [text], with or without the `v` prefix; null when it is not a version. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val groups = match.groupValues
            val numbers = (1..NUMBERS).map { groups[it].ifEmpty { "0" }.toIntOrNull() ?: return null }
            val preRelease = groups[PRE_RELEASE_GROUP].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            if (preRelease.any { it.isEmpty() }) return null
            return AppVersion(text.trim().removePrefix("v"), numbers, preRelease)
        }

        /** Numeric parts compare as numbers and come before the named ones. */
        private fun comparePart(
            a: String,
            b: String,
        ): Int {
            val x = a.toIntOrNull()
            val y = b.toIntOrNull()
            return when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
