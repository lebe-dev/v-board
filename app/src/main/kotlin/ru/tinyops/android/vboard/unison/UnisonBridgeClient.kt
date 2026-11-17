package ru.tinyops.android.vboard.unison

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import app.unison.bridge.BridgeContract
import app.unison.bridge.BridgeState
import app.unison.bridge.ClipPayload
import app.unison.bridge.ClipSummary
import app.unison.bridge.IPublishCallback
import app.unison.bridge.IUnisonBridge
import app.unison.bridge.IUnisonBridgeListener
import app.unison.bridge.PublishResult
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The connection to the bridge service of Unison. Calls into the other app run on a background thread, the
 * callbacks arrive on the main thread. Never logs content.
 */
internal class UnisonBridgeClient(
    private val context: Context,
) {
    /** An item copied on another device. */
    var onItem: ((ClipSummary) -> Unit)? = null

    /** The pause and the number of online devices. */
    var onState: ((BridgeState) -> Unit)? = null

    /** The connection is ready, or it is gone. */
    var onConnection: ((Boolean) -> Unit)? = null

    /** The outcome of a publish, when the app reports it. */
    var onPublishResult: ((PublishResult) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var bridge: IUnisonBridge? = null
    private var bound = false
    private var reportsResults = false

    val connected: Boolean get() = bridge != null

    private val listener =
        object : IUnisonBridgeListener.Stub() {
            override fun onRemoteItem(summary: ClipSummary?) {
                summary ?: return
                main.post { if (bound) onItem?.invoke(summary) }
            }

            override fun onStateChanged(state: BridgeState?) {
                state ?: return
                main.post { if (bound) onState?.invoke(state) }
            }
        }

    private val publishCallback =
        object : IPublishCallback.Stub() {
            override fun onPublishResult(result: PublishResult?) {
                result ?: return
                main.post { onPublishResult?.invoke(result) }
            }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                val service = binder?.let { IUnisonBridge.Stub.asInterface(it) }
                if (service == null || !register(service)) {
                    // The connection is of no use, so the next attempt asks for a fresh one.
                    unbind()
                    return
                }
                bridge = service
                onConnection?.invoke(true)
            }

            override fun onServiceDisconnected(name: ComponentName?) = drop()

            override fun onBindingDied(name: ComponentName?) {
                unbind()
                bind()
            }

            override fun onNullBinding(name: ComponentName?) = unbind()
        }

    /** Binds to Unison when it is installed; false when it is not or refuses this app. */
    fun bind(): Boolean {
        if (bound) return true
        if (!UnisonAvailability.installed(context)) return false
        val intent =
            Intent(BridgeContract.ACTION_BIND)
                .setComponent(ComponentName(BridgeContract.PACKAGE, BridgeContract.SERVICE_CLASS))
        bound =
            try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                Log.w(TAG, "Unison does not allow the keyboard to connect: ${e.message}")
                false
            }
        return bound
    }

    fun unbind() {
        if (!bound) return
        bound = false
        val service = bridge
        if (service != null) {
            try {
                service.unregisterListener(listener)
            } catch (e: RemoteException) {
                Log.w(TAG, "Removing the listener from Unison failed: ${e.message}")
            }
        }
        drop()
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "The connection to Unison is already gone: ${e.message}")
        }
    }

    /** Drops the current connection and binds again: the app was installed, updated or removed. */
    fun rebind() {
        unbind()
        bind()
    }

    /** The keyboard is gone: the connection is closed and the threads stop. */
    fun destroy() {
        unbind()
        io.shutdown()
        writer.shutdown()
    }

    fun publishText(
        text: String,
        html: String?,
    ) = call { service ->
        val payload = ClipPayload(text, html, MIME_TEXT, text.toByteArray().size.toLong())
        if (reportsResults) service.publishTextWithResult(payload, publishCallback) else service.publishText(payload)
    }

    /** Streams what [open] gives through a pipe; [open] runs on a background thread. */
    fun publishBinary(
        mime: String,
        open: () -> InputStream?,
    ) = call { service ->
        val pipe = ParcelFileDescriptor.createPipe()
        writer.execute { write(pipe[1], open) }
        val meta = ClipPayload(null, null, mime, -1L)
        try {
            if (reportsResults) {
                service.publishBinaryWithResult(meta, pipe[0], publishCallback)
            } else {
                service.publishBinary(meta, pipe[0])
            }
        } finally {
            closeQuietly(pipe[0])
        }
    }

    /** The newest items from the other devices, delivered on the main thread; empty when not connected. */
    fun recentItems(
        limit: Int,
        onItems: (List<ClipSummary>) -> Unit,
    ) = call { service ->
        val items = service.recentRemoteItems(limit).orEmpty()
        main.post { onItems(items) }
    }

    /**
     * Reads the content of an item and delivers it on the main thread: null when the item is gone, empty when
     * its content cannot be fetched now.
     */
    fun openItem(
        id: String,
        mime: String,
        onContent: (ByteArray?) -> Unit,
    ) {
        val service = bridge
        if (service == null || io.isShutdown) {
            onContent(null)
            return
        }
        io.execute {
            val content =
                try {
                    service.openItem(id, mime)?.let { descriptor ->
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readNBytes(MAX_CONTENT) }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Reading an item from Unison failed: ${e.message}")
                    ByteArray(0)
                } catch (e: RemoteException) {
                    Log.w(TAG, "Unison stopped while an item was read: ${e.message}")
                    null
                } catch (e: SecurityException) {
                    Log.w(TAG, "Unison refused to give the item: ${e.message}")
                    null
                }
            main.post { onContent(content) }
        }
    }

    /** Above zero pauses until that time, zero resumes, below zero pauses until resumed. */
    fun setPaused(untilEpochMs: Long) = call { it.setPaused(untilEpochMs) }

    /** Checks the version and subscribes; false when this connection cannot be used. */
    private fun register(service: IUnisonBridge): Boolean {
        val version =
            try {
                service.protocolVersion()
            } catch (e: RemoteException) {
                Log.w(TAG, "Unison did not report its version: ${e.message}")
                return false
            } catch (e: SecurityException) {
                Log.w(TAG, "Unison refuses the keyboard, the apps are signed with different keys: ${e.message}")
                return false
            }
        if (version < BridgeContract.FIRST_PROTOCOL_VERSION) {
            Log.w(TAG, "This version of Unison does not serve the keyboard")
            return false
        }
        reportsResults = version >= BridgeContract.PUBLISH_RESULTS_VERSION
        return try {
            service.registerListener(listener)
            true
        } catch (e: RemoteException) {
            Log.w(TAG, "Subscribing to Unison failed: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Unison refuses the keyboard: ${e.message}")
            false
        }
    }

    /** Runs [block] on the background thread with the service; a failed call drops the connection. */
    private fun call(block: (IUnisonBridge) -> Unit) {
        val service = bridge ?: return
        if (io.isShutdown) return
        io.execute {
            try {
                block(service)
            } catch (e: RemoteException) {
                Log.w(TAG, "A call into Unison failed: ${e.message}")
                main.post { if (bridge === service) drop() }
            } catch (e: SecurityException) {
                Log.w(TAG, "Unison refused a call: ${e.message}")
            }
        }
    }

    private fun drop() {
        if (bridge == null) return
        bridge = null
        onConnection?.invoke(false)
    }

    private fun write(
        target: ParcelFileDescriptor,
        open: () -> InputStream?,
    ) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(target).use { output ->
                open()?.use { input -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Streaming the content to Unison failed: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "The copied content cannot be read anymore: ${e.message}")
        }
    }

    private fun closeQuietly(descriptor: ParcelFileDescriptor) {
        try {
            descriptor.close()
        } catch (e: IOException) {
            Log.w(TAG, "Closing the pipe failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "UnisonBridge"
        const val MIME_TEXT = "text/plain"
        const val MAX_CONTENT = 16 * 1024 * 1024
    }
}
