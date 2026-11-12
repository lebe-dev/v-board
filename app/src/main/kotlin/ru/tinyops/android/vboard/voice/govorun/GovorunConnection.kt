package ru.tinyops.android.vboard.voice.govorun

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.govorun.api.v1.IRecognitionService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import ru.tinyops.android.vboard.core.voice.GovorunApi

/** The binding to Govorun's recognition service. Main thread only. */
internal class GovorunConnection(
    context: Context,
) {
    private val context = context.applicationContext
    private var connection: ServiceConnection? = null

    @Volatile private var service: IRecognitionService? = null

    /** The bound service, binding first when needed; null when Govorun cannot be reached. */
    suspend fun connect(): IRecognitionService? {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        release()
        val bound = CompletableDeferred<IRecognitionService?>()
        val next =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    service = IRecognitionService.Stub.asInterface(binder)
                    bound.complete(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }

                override fun onBindingDied(name: ComponentName?) {
                    service = null
                    bound.complete(null)
                }

                override fun onNullBinding(name: ComponentName?) {
                    bound.complete(null)
                }
            }
        val started = runCatching { context.bindService(serviceIntent(), next, Context.BIND_AUTO_CREATE) }
        connection = next
        if (!started.getOrDefault(false)) {
            Log.w(TAG, "Govorun service could not be bound")
            release()
            return null
        }
        // A cold start of Govorun's process comes before the answer.
        return withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { bound.await() }
    }

    fun release() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
    }

    companion object {
        private const val TAG = "GovorunConnection"
        private const val BIND_TIMEOUT_MILLIS = 10_000L

        fun serviceIntent(): Intent = Intent(GovorunApi.SERVICE_ACTION).setPackage(GovorunApi.PACKAGE)

        fun isInstalled(context: Context): Boolean = context.packageManager.resolveService(serviceIntent(), 0) != null
    }
}
