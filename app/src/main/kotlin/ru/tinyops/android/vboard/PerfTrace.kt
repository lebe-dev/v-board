package ru.tinyops.android.vboard

import android.os.Trace

/**
 * Sections of the keyboard's hot paths in system traces. The benchmark module measures them by
 * these names. A section costs next to nothing while no trace is being recorded.
 */
internal object PerfTrace {
    const val DICTIONARY_LOAD = "kbd.dict.load"
    const val DECODE_LIVE = "kbd.decode.live"
    const val DECODE_FINAL = "kbd.decode.final"
    const val DECODE_NEURAL = "kbd.decode.neural"
    const val KEY_PRESS = "kbd.key"
    const val DRAW = "kbd.draw"
    const val CREATE_VIEW = "kbd.createView"
    const val START_INPUT = "kbd.startInput"

    inline fun <T> section(
        name: String,
        block: () -> T,
    ): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }
}
