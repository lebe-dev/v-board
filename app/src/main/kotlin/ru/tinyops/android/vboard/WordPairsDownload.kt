package ru.tinyops.android.vboard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.tinyops.android.vboard.core.dict.WordPairsInstaller
import ru.tinyops.android.vboard.core.dict.WordPairsNoSpace
import ru.tinyops.android.vboard.core.dict.WordPairsRelease
import ru.tinyops.android.vboard.diagnostics.AppLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Downloads the word pair table of a release build. One download at a time, for the whole process. */
internal object WordPairsDownload {
    private const val TAG = "WordPairsDownload"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    sealed interface State {
        data object Idle : State

        data class Running(
            val done: Long,
            val total: Long,
        ) : State

        /** [missing] means the release has no table: trying again will not help. */
        data class Failed(
            val noSpace: Boolean,
            val missing: Boolean = false,
        ) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private var job: Job? = null

    val state: StateFlow<State> = mutableState.asStateFlow()

    @Synchronized
    fun start(
        context: Context,
        release: WordPairsRelease = WordPairs.release,
        directory: File = WordPairs.directory(context),
    ) {
        if (job?.isActive == true) return
        mutableState.value = State.Running(0, release.size)
        // Started only once it is the current job, so that its updates are not taken for a stale one's.
        val download =
            scope.launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                val result =
                    try {
                        download(release, directory) { publish(self, it) }
                        Log.i(TAG, "Word pair table downloaded")
                        State.Idle
                    } catch (missing: WordPairsMissing) {
                        AppLog.error(TAG, "The release has no word pair table", missing)
                        State.Failed(noSpace = false, missing = true)
                    } catch (full: WordPairsNoSpace) {
                        AppLog.error(TAG, "No room for the word pair table", full)
                        State.Failed(noSpace = true)
                    } catch (failure: IOException) {
                        // A cancelled download fails its read as well; that is not an error to show.
                        ensureActive()
                        AppLog.error(TAG, "The word pair table could not be downloaded", failure)
                        State.Failed(noSpace = false)
                    }
                publish(self, result)
            }
        job = download
        download.start()
    }

    /** Only the current download changes the state: a cancelled one may still be finishing a read. */
    @Synchronized
    private fun publish(
        owner: Job?,
        value: State,
    ) {
        if (owner != null && owner === job) mutableState.value = value
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = State.Idle
    }

    private class WordPairsMissing : IOException("the release has no word pair table")

    private suspend fun download(
        release: WordPairsRelease,
        directory: File,
        onState: (State) -> Unit,
    ) {
        val connection = URI(release.url).toURL().openConnection() as HttpURLConnection
        val job = currentCoroutineContext()[Job]
        // A cancelled download must not wait for a stalled read to time out.
        val disconnect = job?.invokeOnCompletion { connection.disconnect() }
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw WordPairsMissing()
            if (code != HttpURLConnection.HTTP_OK) throw IOException("word pair table download answered HTTP $code")
            connection.inputStream.use { stream ->
                WordPairsInstaller.install(stream, release, directory) { done ->
                    job?.ensureActive()
                    onState(State.Running(done, release.size))
                }
            }
        } finally {
            disconnect?.dispose()
            connection.disconnect()
        }
    }
}
