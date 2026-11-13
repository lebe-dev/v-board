package ru.tinyops.android.vboard.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.tinyops.android.vboard.BuildConfig
import ru.tinyops.android.vboard.diagnostics.AppLog
import java.io.IOException

/** Looks for a newer release on GitHub and remembers what it found. One check at a time, for the whole process. */
internal object UpdateCheck {
    private const val TAG = "UpdateCheck"

    sealed interface State {
        data object Idle : State

        data object Checking : State

        data object UpToDate : State

        data class Available(
            val release: AvailableRelease,
        ) : State

        data object Failed : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private var job: Job? = null

    /** The result of the check the user started, for the updates page. */
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** The stored release newer than this build, or null when there is none. */
    fun available(
        context: Context,
        current: String = BuildConfig.VERSION_NAME,
    ): AvailableRelease? = UpdatePrefs(context).latest?.takeIf { it.isNewerThan(current) }

    /** The release the banner on the settings root offers: newer than this build and not closed by the user. */
    fun announced(
        context: Context,
        current: String = BuildConfig.VERSION_NAME,
    ): AvailableRelease? = available(context, current)?.takeIf { it.version != UpdatePrefs(context).dismissedVersion }

    @Synchronized
    fun start(
        context: Context,
        releases: GithubReleases = GithubReleases(),
        current: String = BuildConfig.VERSION_NAME,
    ) {
        if (job?.isActive == true) return
        mutableState.value = State.Checking
        val app = context.applicationContext
        job =
            scope.launch {
                mutableState.value =
                    try {
                        check(app, releases, current)?.let(State::Available) ?: State.UpToDate
                    } catch (failure: IOException) {
                        AppLog.error(TAG, "Could not check for updates", failure)
                        State.Failed
                    }
            }
    }

    /** Fetches the latest release and stores it; returns it when it is newer than [current]. Blocks. */
    @Throws(IOException::class)
    fun check(
        context: Context,
        releases: GithubReleases = GithubReleases(),
        current: String = BuildConfig.VERSION_NAME,
    ): AvailableRelease? {
        val latest = releases.latest()
        val prefs = UpdatePrefs(context)
        prefs.latest = latest
        prefs.lastCheck = System.currentTimeMillis()
        val newer = latest?.takeIf { it.isNewerThan(current) }
        Log.i(TAG, if (newer == null) "No newer release" else "Release ${newer.version} is available")
        return newer
    }
}
