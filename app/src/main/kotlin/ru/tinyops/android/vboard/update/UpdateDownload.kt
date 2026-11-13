package ru.tinyops.android.vboard.update

import android.content.Context
import android.content.Intent
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
import ru.tinyops.android.vboard.diagnostics.AppLog
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Downloads the APK of a release, checks it and hands it to the system installer. One download at a
 * time, for the whole process.
 */
internal object UpdateDownload {
    private const val TAG = "UpdateDownload"
    private const val DIRECTORY = "updates"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER = 64 * 1024

    enum class Failure {
        NETWORK,
        DAMAGED,
        FOREIGN,
        INSTALL,
    }

    sealed interface State {
        data object Idle : State

        data class Running(
            val done: Long,
            val total: Long,
        ) : State

        /** The APK is with the system installer. */
        data object Installing : State

        /** The installer waits for the user to confirm on its own screen. */
        data class Confirm(
            val intent: Intent,
        ) : State

        data class Failed(
            val reason: Failure,
        ) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private var job: Job? = null

    val state: StateFlow<State> = mutableState.asStateFlow()

    private fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    /** Deletes the downloaded APKs. */
    fun clear(context: Context) {
        directory(context).listFiles()?.forEach(File::delete)
    }

    /** Downloads [release], checks it with [verify] and installs it with [install]. */
    @Synchronized
    fun start(
        context: Context,
        release: AvailableRelease,
        verify: (Context, File) -> Boolean = ApkVerifier::isUpdateOf,
        install: (Context, File) -> Unit = UpdateInstaller::install,
    ) {
        val apk = release.apk ?: return
        if (job?.isActive == true) return
        val app = context.applicationContext
        mutableState.value = State.Running(0, apk.size)
        val download =
            scope.launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                val result = run(app, release.version, apk, verify, install) { publish(self, it) }
                publish(self, result)
            }
        job = download
        download.start()
    }

    private suspend fun run(
        context: Context,
        version: String,
        apk: ApkAsset,
        verify: (Context, File) -> Boolean,
        install: (Context, File) -> Unit,
        onState: (State) -> Unit,
    ): State {
        clear(context)
        val directory = directory(context).apply { mkdirs() }
        val file = File(directory, "vboard-$version.apk")
        return try {
            download(apk, file, onState)
            if (!verify(context, file)) {
                AppLog.error(TAG, "The downloaded APK is not an update of this app", null)
                file.delete()
                return State.Failed(Failure.FOREIGN)
            }
            onState(State.Installing)
            install(context, file)
            Log.i(TAG, "Release $version is handed to the installer")
            State.Installing
        } catch (damaged: DamagedApk) {
            AppLog.error(TAG, "The downloaded APK is damaged", damaged)
            file.delete()
            State.Failed(Failure.DAMAGED)
        } catch (failure: IOException) {
            // A cancelled download fails its read as well; that is not an error to show.
            currentCoroutineContext().ensureActive()
            AppLog.error(TAG, "The update could not be downloaded", failure)
            file.delete()
            State.Failed(Failure.NETWORK)
        }
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

    /** The confirmation of the installer not yet shown to the user. */
    private var unshown: Intent? = null

    /** The answer of the system installer. */
    @Synchronized
    fun installerAnswered(value: State) {
        unshown = (value as? State.Confirm)?.intent
        mutableState.value = value
    }

    /** Returns the confirmation of the installer once, for the page to open it by itself. */
    @Synchronized
    fun takeConfirmation(): Intent? = unshown.also { unshown = null }

    private suspend fun download(
        apk: ApkAsset,
        file: File,
        onState: (State) -> Unit,
    ) {
        val connection = URI(apk.url).toURL().openConnection() as HttpURLConnection
        val job = currentCoroutineContext()[Job]
        // A cancelled download must not wait for a stalled read to time out.
        val disconnect = job?.invokeOnCompletion { connection.disconnect() }
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("APK download answered HTTP $code")
            val hash =
                connection.inputStream.use { input ->
                    save(input, file, apk.size) { done ->
                        job?.ensureActive()
                        onState(State.Running(done, apk.size))
                    }
                }
            if (apk.sha256.isNotEmpty() && hash != apk.sha256) throw DamagedApk("the APK hash does not match")
        } finally {
            disconnect?.dispose()
            connection.disconnect()
        }
    }

    /** Writes exactly [size] bytes of [input] to [file] and returns their SHA-256. */
    private fun save(
        input: InputStream,
        file: File,
        size: Long,
        onProgress: (Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var done = 0L
        var read = input.read(buffer)
        file.outputStream().use { output ->
            while (read >= 0 && done + read <= size) {
                done += read
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                onProgress(done)
                read = input.read(buffer)
            }
        }
        if (read >= 0 || done != size) throw DamagedApk("the APK is not the $size bytes the release says")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class DamagedApk(
        message: String,
    ) : IOException(message)
}
