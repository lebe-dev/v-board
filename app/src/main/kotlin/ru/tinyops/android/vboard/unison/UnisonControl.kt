package ru.tinyops.android.vboard.unison

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import app.unison.bridge.BridgeContract
import app.unison.bridge.ClipSummary
import ru.tinyops.android.vboard.EditorProfile
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.clipboard.ClipboardContent
import ru.tinyops.android.vboard.clipboard.RemoteClips
import ru.tinyops.android.vboard.core.clipboard.ClipEcho
import ru.tinyops.android.vboard.core.clipboard.RemoteClip
import ru.tinyops.android.vboard.ui.ClipboardPanelView
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * What the sync needs from the keyboard: the field it types into, and what to do once the newest item from another
 * device or the pause changed ([onChanged]).
 */
internal class UnisonHost(
    val editorInfo: () -> EditorInfo?,
    val onChanged: () -> Unit,
    val insertText: (String) -> Unit,
    val pasteImage: (Uri, String) -> Unit,
    val showMessage: (Int) -> Unit,
)

/**
 * The clipboard sync through Unison, while it is turned on in the settings: a copy made in a text field (or
 * copied a moment before the keyboard opened) is handed to Unison, and the newest item copied on another device
 * is offered on the strip. Passwords, clips marked sensitive, private fields and what came from Unison itself are
 * never sent. Main thread only.
 */
internal class UnisonControl(
    private val context: Context,
    private val host: UnisonHost,
    private val prefs: UnisonPrefs = UnisonPrefs(context),
    private val clock: () -> Long = System::currentTimeMillis,
) : ClipboardPanelView.SyncPause,
    RemoteClips {
    private val client = UnisonBridgeClient(context)
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val files = Executors.newSingleThreadExecutor()
    private val echo = ClipEcho(BridgeContract::preview)
    private val keyboardPrefs = KeyboardPrefs(context)
    private val capture = Runnable { capture(live = true) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    private val packages =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.data?.schemeSpecificPart != BridgeContract.PACKAGE || !prefs.enabled) return
                client.bind()
            }
        }

    /** The items from the other devices by id, the newest few, and those already pasted or replaced. */
    private val items = LinkedHashMap<String, ClipSummary>()
    private val consumed = HashSet<String>()

    /** True while the sync is paused in Unison. */
    override var paused = false
        private set

    /** True while the sync is on and Unison is connected: the pause can be switched. */
    override val active: Boolean get() = prefs.enabled && client.connected

    init {
        client.onConnection = { connected ->
            if (connected) {
                loadRecent()
                capture(live = false)
            } else {
                paused = false
            }
            host.onChanged()
        }
        client.onItem = ::onItem
        client.onState = { state ->
            if (state.paused != paused) {
                paused = state.paused
                host.onChanged()
            }
        }
        client.onPublishResult = { result ->
            if (result.code != BridgeContract.PUBLISH_ACCEPTED) {
                Log.w(TAG, "Unison did not take a copy of ${result.mime}: code ${result.code}")
            }
            if (result.code == BridgeContract.PUBLISH_PAUSED && !paused) {
                paused = true
                host.onChanged()
            }
        }
        manager?.addPrimaryClipChangedListener(clipListener)
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        context.registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED)
        files.execute(::deleteOldFiles)
        if (prefs.enabled) client.bind()
    }

    fun destroy() {
        handler.removeCallbacks(capture)
        manager?.removePrimaryClipChangedListener(clipListener)
        context.unregisterReceiver(packages)
        client.destroy()
        files.shutdown()
    }

    /** Follows the switch in the settings and catches up with a fresh copy. */
    override fun sync() {
        if (!prefs.enabled) {
            if (client.connected || items.isNotEmpty()) {
                client.unbind()
                items.clear()
                host.onChanged()
            }
            return
        }
        if (!client.connected) {
            client.bind()
            return
        }
        loadRecent()
        capture(live = false)
    }

    override fun latest(): RemoteClip? {
        if (!prefs.enabled || paused) return null
        return items.values
            .filter { it.id !in consumed }
            .maxByOrNull { it.createdAtMs }
            ?.let(::remoteClip)
    }

    override fun supersede(id: String) {
        consumed += id
    }

    /** Text goes in at the cursor, an image as rich content. */
    override fun paste(id: String) {
        val item = items[id] ?: return
        consumed += id
        if (item.kind == BridgeContract.KIND_IMAGE) pasteImage(item) else pasteText(item)
    }

    /** Pauses the sync until it is resumed, or resumes it. */
    override fun togglePause() {
        // Shown at once; the state Unison reports afterwards has the last word.
        paused = !paused
        client.setPaused(if (paused) PAUSE_UNTIL_RESUMED else RESUME)
    }

    private fun onClipChanged() {
        handler.removeCallbacks(capture)
        if (prefs.enabled) handler.postDelayed(capture, DEBOUNCE_MILLIS)
    }

    private fun onItem(summary: ClipSummary) {
        if (!remember(summary)) return
        host.onChanged()
    }

    private fun loadRecent() {
        client.recentItems(RECENT) { recent ->
            val changed = recent.map(::remember).any { it }
            if (changed) host.onChanged()
        }
    }

    /** Keeps a text or an image item; files are not offered. True when it is new. */
    private fun remember(summary: ClipSummary): Boolean {
        val usable =
            summary.kind == BridgeContract.KIND_IMAGE ||
                (summary.kind == BridgeContract.KIND_TEXT && summary.preview.isNotBlank())
        if (!usable || summary.id.isNullOrEmpty()) return false
        echo.arrived(summary.preview.orEmpty(), clock())
        if (items.containsKey(summary.id)) return false
        items[summary.id] = summary
        while (items.size > RECENT) {
            val oldest = items.values.minBy { it.createdAtMs }
            items.remove(oldest.id)
            consumed.remove(oldest.id)
        }
        return true
    }

    private fun remoteClip(summary: ClipSummary): RemoteClip =
        RemoteClip(
            id = summary.id,
            image = summary.kind == BridgeContract.KIND_IMAGE,
            preview = summary.preview.orEmpty(),
            device = summary.deviceName.orEmpty().ifBlank { context.getString(R.string.unison_other_device) },
            createdAt = summary.createdAtMs,
        )

    private fun pasteText(item: ClipSummary) {
        client.openItem(item.id, MIME_TEXT) { bytes ->
            val text = bytes?.toString(Charsets.UTF_8)
            when {
                bytes == null -> itemGone(item.id)
                text.isNullOrEmpty() -> notYetAvailable(item.id)
                else -> {
                    echo.pasted(text, clock())
                    host.insertText(keyboardPrefs.cleanedLinks(text))
                }
            }
        }
    }

    private fun pasteImage(item: ClipSummary) {
        val mime = item.mimeTypes?.firstOrNull { it.startsWith(IMAGE_PREFIX) } ?: DEFAULT_IMAGE
        val info = host.editorInfo()
        val accepted: Array<String> = if (info == null) emptyArray() else EditorInfoCompat.getContentMimeTypes(info)
        if (ClipboardContent.acceptedImageType(mime, accepted) == null) {
            consumed -= item.id
            host.showMessage(R.string.paste_image_unsupported)
            return
        }
        client.openItem(item.id, mime) { bytes ->
            when {
                bytes == null -> itemGone(item.id)
                bytes.isEmpty() -> notYetAvailable(item.id)
                else -> storeImage(bytes, mime)
            }
        }
    }

    /** Writes the image where the field can read it, then pastes it. */
    private fun storeImage(
        bytes: ByteArray,
        mime: String,
    ) {
        if (files.isShutdown) return
        files.execute {
            val uri =
                try {
                    val file = File(directory(), UUID.randomUUID().toString() + extension(mime))
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    FileProvider.getUriForFile(context, "${context.packageName}.clips", file)
                } catch (e: IOException) {
                    Log.w(TAG, "Keeping an image from Unison failed: ${e.message}")
                    null
                }
            handler.post {
                if (uri ==
                    null
                ) {
                    host.showMessage(R.string.paste_image_failed)
                } else {
                    host.pasteImage(uri, mime)
                }
            }
        }
    }

    private fun itemGone(id: String) {
        items.remove(id)
        host.showMessage(R.string.unison_item_gone)
        host.onChanged()
    }

    private fun notYetAvailable(id: String) {
        consumed -= id
        host.showMessage(R.string.unison_item_unavailable)
        host.onChanged()
    }

    /**
     * Hands the current clip to Unison, once. A live copy is taken right after it happens; on [live] false only a
     * copy of the last moments is, so an old clip is not sent when the keyboard opens.
     */
    private fun capture(live: Boolean) {
        val clip = newClip(live) ?: return
        prefs.lastClipAt = clip.description.timestamp
        if (clip.itemCount == 0 || ClipboardContent.isSensitive(clip) || mirrors(clip)) return
        val item = clip.getItemAt(0)
        val html = item.htmlText
        val text = item.text?.toString() ?: html?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
        if (!text.isNullOrEmpty()) {
            publishText(text, html)
            return
        }
        item.uri?.let { publishImage(clip.description, it) }
    }

    /** The clip not looked at yet, when the sync is on and running and the field lets a copy leave the phone. */
    private fun newClip(live: Boolean): ClipData? {
        val ready = prefs.enabled && client.connected && !paused
        if (!ready || !EditorProfile.from(host.editorInfo()).clipboardSyncAllowed) return null
        val clip = manager?.primaryClip ?: return null
        val copiedAt = clip.description.timestamp
        val fresh = live || clock() - copiedAt <= CATCH_UP_MILLIS
        return clip.takeIf { copiedAt > prefs.lastClipAt && fresh }
    }

    private fun publishText(
        text: String,
        html: String?,
    ) {
        val now = clock()
        if (echo.isEcho(text, now) || echo.isRepeat(text, now)) return
        echo.sent(text, now)
        val bytes = text.toByteArray()
        when {
            bytes.size > MAX_BINARY -> Log.i(TAG, "A copy of ${bytes.size} bytes is too large for Unison")
            bytes.size + (html?.length ?: 0) * 2 <= MAX_IN_CALL -> client.publishText(text, html)
            else -> client.publishBinary(MIME_TEXT) { ByteArrayInputStream(bytes) }
        }
    }

    private fun publishImage(
        description: ClipDescription,
        uri: Uri,
    ) {
        val mime =
            (0 until description.mimeTypeCount)
                .map(description::getMimeType)
                .firstOrNull { it.startsWith(IMAGE_PREFIX) }
                ?: context.contentResolver.getType(uri)
                ?: return
        if (!mime.startsWith(IMAGE_PREFIX) || !BridgeContract.isSupportedMime(mime)) return
        client.publishBinary(mime) { context.contentResolver.openInputStream(uri) }
    }

    /**
     * Unison marks what it puts into the clipboard with the id of the item and its own label; versions before the
     * id are recognized by the label and by the uri of an image.
     */
    override fun mirrors(clip: ClipData): Boolean {
        val description = clip.description
        if (description.extras?.getString(BridgeContract.EXTRA_ITEM_ID) != null) return true
        if (description.label?.toString() == BridgeContract.CLIP_LABEL) return true
        val uri = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        return uri?.authority?.startsWith(BridgeContract.PACKAGE) == true
    }

    private fun directory() = File(context.cacheDir, DIRECTORY)

    private fun deleteOldFiles() {
        val limit = clock() - FILE_LIFETIME_MILLIS
        directory().listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    private fun extension(mime: String): String =
        when (mime.substringAfter('/').substringBefore(';').lowercase()) {
            "jpeg", "jpg" -> ".jpg"
            "gif" -> ".gif"
            "webp" -> ".webp"
            else -> ".png"
        }

    private companion object {
        const val TAG = "UnisonSync"
        const val MIME_TEXT = "text/plain"
        const val IMAGE_PREFIX = "image/"
        const val DEFAULT_IMAGE = "image/png"
        const val DIRECTORY = "unison"
        const val RECENT = 3
        const val DEBOUNCE_MILLIS = 250L
        const val CATCH_UP_MILLIS = 60_000L
        const val FILE_LIFETIME_MILLIS = 24 * 60 * 60_000L
        const val RESUME = 0L
        const val PAUSE_UNTIL_RESUMED = -1L

        // A binder call carries about a megabyte for both apps together; larger text goes through a pipe.
        const val MAX_IN_CALL = 256 * 1024
        const val MAX_BINARY = 16 * 1024 * 1024
    }
}
