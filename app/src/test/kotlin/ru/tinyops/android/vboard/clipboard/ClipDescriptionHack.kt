package ru.tinyops.android.vboard.clipboard

import android.content.ClipDescription

/** The copy time is set by the system; the hidden setter lets a test choose it. */
object ClipDescriptionHack {
    fun setTimestamp(
        description: ClipDescription,
        at: Long,
    ) {
        ClipDescription::class.java.getMethod("setTimestamp", Long::class.javaPrimitiveType).invoke(description, at)
    }
}
