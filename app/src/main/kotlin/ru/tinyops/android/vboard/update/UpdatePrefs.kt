package ru.tinyops.android.vboard.update

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** How often the background check runs while it is on. */
enum class CheckInterval(
    val code: String,
    val hours: Long,
) {
    DAILY("daily", 24),
    WEEKLY("weekly", 24 * 7),
    ;

    companion object {
        fun fromCode(code: String?): CheckInterval = entries.firstOrNull { it.code == code } ?: DAILY
    }
}

/** The update settings and the last release the check found. */
class UpdatePrefs(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the app looks for a new release in the background; off by default. */
    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CHECK, value) }

    var interval: CheckInterval
        get() = CheckInterval.fromCode(prefs.getString(KEY_INTERVAL, null))
        set(value) = prefs.edit { putString(KEY_INTERVAL, value.code) }

    /** Whether beta releases are offered too; off by default. */
    var betaReleases: Boolean
        get() = prefs.getBoolean(KEY_BETA, false)
        set(value) = prefs.edit { putBoolean(KEY_BETA, value) }

    /** The newest release found by the last successful check, or null when there was none. */
    var latest: AvailableRelease?
        get() = prefs.getString(KEY_LATEST, null)?.let(AvailableRelease::decode)
        set(value) = prefs.edit { putString(KEY_LATEST, value?.encode()) }

    /** When the last check succeeded, in milliseconds since the epoch; 0 before the first one. */
    var lastCheck: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_CHECK, value) }

    /** The version whose banner the user has closed; a newer one shows the banner again. */
    var dismissedVersion: String?
        get() = prefs.getString(KEY_DISMISSED, null)
        set(value) = prefs.edit { putString(KEY_DISMISSED, value) }

    companion object {
        private const val FILE = "updates"
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_INTERVAL = "check_interval"
        private const val KEY_BETA = "beta_releases"
        private const val KEY_LATEST = "latest_release"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_DISMISSED = "dismissed_version"

        /** The keys that describe this phone rather than a choice of the user. */
        val LOCAL_KEYS = setOf(KEY_LATEST, KEY_LAST_CHECK, KEY_DISMISSED)
    }
}
