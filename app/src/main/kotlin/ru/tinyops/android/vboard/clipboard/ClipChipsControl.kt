package ru.tinyops.android.vboard.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import ru.tinyops.android.vboard.clipboard.ClipboardContent
import ru.tinyops.android.vboard.core.clipboard.ClipChips
import ru.tinyops.android.vboard.core.clipboard.ClipPayload
import ru.tinyops.android.vboard.ui.SuggestionStripView
import java.util.concurrent.Executors

/**
 * Keeps the clipboard history up to date and shows a clip that was just copied as chips on the strip, until
 * it is pasted or gets too old. Main thread only.
 *
 * @param strip the strip of the current input view, if there is one
 * @param clock the time the age of a clip is measured against
 * @param primaryClip reads the clipboard: a call into the system, so it is made once per update
 */
internal class ClipChipsControl(
    private val context: Context,
    private val prefs: ClipboardPrefs,
    private val store: ClipboardHistoryStore,
    private val strip: () -> SuggestionStripView?,
    private val clock: () -> Long,
    private val primaryClip: () -> ClipData? = {
        context.getSystemService(ClipboardManager::class.java)?.primaryClip
    },
) {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val thumbnailLoader = Executors.newSingleThreadExecutor()
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { sync() }
    private val expire = Runnable { refresh() }

    /** The clip behind the chips, its copy time (to hide it after a paste) and the thumbnail of an image. */
    private var payload: ClipPayload? = null
    private var clipAt = 0L
    private var consumedClipAt = 0L
    private var thumbnail: Pair<String, Bitmap>? = null

    fun start() {
        manager?.addPrimaryClipChangedListener(listener)
    }

    fun destroy() {
        manager?.removePrimaryClipChangedListener(listener)
        handler.removeCallbacks(expire)
        thumbnailLoader.shutdownNow()
    }

    /** Catches up with what was copied, also while the keyboard was not running, then updates the chips. */
    fun sync() {
        val clip = if (prefs.recording || chipsWanted) primaryClip() else null
        if (prefs.recording) store.record(clip)
        show(clip)
    }

    /** A chip was tapped: its clip is not offered again. Returns what the chips stand for. */
    fun consume(): ClipPayload? {
        consumedClipAt = clipAt
        return payload
    }

    /** Shows the recent clip, if it is fresh and not yet pasted, as chips on the strip. */
    fun refresh() = show(if (chipsWanted) primaryClip() else null)

    /** Without a strip, or with the chips turned off, the clipboard is not read for them. */
    private val chipsWanted: Boolean get() = prefs.chipsShown && strip() != null

    /**
     * The age is checked before the content: usually the clip is long past its minute, and the type of an
     * image would be one more call into another process.
     */
    private fun show(primary: ClipData?) {
        handler.removeCallbacks(expire)
        val strip = strip() ?: return
        val clip = primary?.takeIf { prefs.chipsShown }
        val copiedAt = clip?.description?.timestamp ?: 0L
        val age = (clock() - copiedAt).coerceAtLeast(0L)
        val current =
            clip
                ?.takeUnless { copiedAt == consumedClipAt || age >= LIFETIME_MILLIS }
                ?.takeUnless(ClipboardContent::isSensitive)
                ?.let { ClipboardContent.from(it) { uri -> context.contentResolver.getType(uri) } }
        if (current == null) {
            payload = null
            strip.setClipChips(emptyList())
            return
        }
        payload = current
        clipAt = copiedAt
        strip.setClipChips(ClipChips.of(current, prefs.showExtracted), thumbnailOf(current))
        handler.postDelayed(expire, LIFETIME_MILLIS - age)
    }

    /** The thumbnail of an image clip: decoded off the main thread, the chips are refreshed once it is ready. */
    private fun thumbnailOf(clip: ClipPayload): Bitmap? {
        if (clip !is ClipPayload.Image) return null
        thumbnail?.takeIf { it.first == clip.source }?.let { return it.second }
        thumbnailLoader.execute {
            val bitmap = Thumbnails.load(context, clip.source.toUri(), THUMBNAIL_SIDE) ?: return@execute
            handler.post {
                thumbnail = clip.source to bitmap
                if ((payload as? ClipPayload.Image)?.source == clip.source) refresh()
            }
        }
        return null
    }

    private companion object {
        const val LIFETIME_MILLIS = 60_000L
        const val THUMBNAIL_SIDE = 96
    }
}
