package ru.tinyops.android.vboard.core.gesture

import ru.tinyops.android.vboard.core.layout.KeyboardGeometry
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout

/** Converts on-screen key geometry into the decoder's [KeyboardLayout]. */
object GestureLayoutFactory {
    /**
     * Letters that have no key of their own but share the geometry of another key, so words
     * containing them can still be swiped (`ё` lives on `е`, `ъ` on `ь`).
     */
    private val ALIASES: Map<Language, Map<Char, Char>> =
        mapOf(
            Language.RUSSIAN to mapOf('ё' to 'е', 'ъ' to 'ь'),
        )

    fun create(
        geometry: KeyboardGeometry,
        xdpi: Float,
        ydpi: Float,
    ): KeyboardLayout {
        val keys = ArrayList<Key>()
        val byCharacter = HashMap<Char, Key>()
        for (placed in geometry.keys) {
            if (!placed.spec.isLetter) continue
            val character =
                placed.spec.output
                    .lowercase(geometry.spec.language.locale)
                    .single()
            val b = placed.bounds
            val key = Key(keys.size + 1, character.toString(), b.left, b.top, b.right, b.bottom)
            keys += key
            byCharacter[character] = key
        }
        for ((alias, target) in ALIASES[geometry.spec.language].orEmpty()) {
            val base = byCharacter[target] ?: continue
            keys += base.copy(code = keys.size + 1, label = alias.toString())
        }
        return KeyboardLayout(
            width = geometry.width,
            height = geometry.height,
            xdpi = xdpi,
            ydpi = ydpi,
            keys = keys,
        )
    }
}
