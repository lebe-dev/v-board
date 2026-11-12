package ru.tinyops.android.vboard.voice.govorun

import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellationException

/** Calls into Govorun's process, which can fail there in any way. */
internal object GovorunCalls {
    private const val TAG = "GovorunCalls"

    /**
     * The result of [call], or null when it failed. Besides [RemoteException] the binder rethrows the runtime
     * exceptions of the other process (`SecurityException`, `IllegalStateException` and others): they say
     * nothing about the keyboard and must not end its process.
     */
    inline fun <T> guard(
        name: String,
        call: () -> T,
    ): T? =
        try {
            call()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RemoteException) {
            Log.w(TAG, "Govorun call $name failed: ${failure.javaClass.simpleName}")
            null
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            Log.w(TAG, "Govorun call $name failed in its process: ${failure.javaClass.simpleName}")
            null
        }
}
