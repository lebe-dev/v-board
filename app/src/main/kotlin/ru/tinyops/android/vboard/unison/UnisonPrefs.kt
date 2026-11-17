package ru.tinyops.android.vboard.unison

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Settings of the clipboard sync through Unison, shared by the keyboard and the settings screen (same process). */
class UnisonPrefs(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Off by default: nothing is sent until the user turns the sync on. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** The copy time of the last clip handed to Unison or skipped, so a clip is looked at once. */
    var lastClipAt: Long
        get() = prefs.getLong(KEY_LAST_CLIP, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_CLIP, value) }

    companion object {
        private const val FILE = "unison_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_CLIP = "last_clip_at"

        /** Keys that describe this phone rather than a choice: left out of the settings backup. */
        val LOCAL_KEYS: Set<String> = setOf(KEY_LAST_CLIP)
    }
}
