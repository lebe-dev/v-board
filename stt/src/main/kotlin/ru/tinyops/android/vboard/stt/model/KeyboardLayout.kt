package ru.tinyops.android.vboard.stt.model

/**
 * Geometry of a soft keyboard: overall size, physical density and per-key
 * hitboxes. Mirrors the fields the reference decoder consumes for its spatial
 * model (size, xdpi/ydpi, origin offset, orientation, keys).
 */
data class KeyboardLayout(
    val width: Float,
    val height: Float,
    val xdpi: Float,
    val ydpi: Float,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val orientation: Int = 0,
    val keys: List<Key>,
) {
    private val keysByCharacter: Map<Char, Key> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildMap {
            for (k in this@KeyboardLayout.keys) {
                val c = k.character ?: continue
                putIfAbsent(c, k)
            }
        }
    }

    /** Character keys (letters), in layout order. */
    val characterKeys: List<Key> get() = keys.filter { it.isCharacter }

    /** Average key height, used to derive spatial/downsampling thresholds. */
    val averageKeyHeight: Float
        get() {
            val chars = characterKeys
            if (chars.isEmpty()) return height
            return chars.sumOf { it.height.toDouble() }.toFloat() / chars.size
        }

    /** Average key width, used to derive spatial/downsampling thresholds. */
    val averageKeyWidth: Float
        get() {
            val chars = characterKeys
            if (chars.isEmpty()) return width
            return chars.sumOf { it.width.toDouble() }.toFloat() / chars.size
        }

    /** The character key whose label is [c], case-insensitive. */
    fun keyForCharacter(c: Char): Key? = keysByCharacter[c.lowercaseChar()]

    /** The topmost key whose hitbox contains the point, or null. */
    fun keyAt(
        x: Float,
        y: Float,
    ): Key? = keys.firstOrNull { it.contains(x, y) }
}
