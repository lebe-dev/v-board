package ru.tinyops.android.vboard.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import ru.tinyops.android.vboard.clipboard.ClipboardContent
import ru.tinyops.android.vboard.core.clipboard.ClipChip
import ru.tinyops.android.vboard.core.clipboard.ClipChips
import ru.tinyops.android.vboard.core.clipboard.ClipPayload
import ru.tinyops.android.vboard.core.clipboard.RemoteClip
import ru.tinyops.android.vboard.ui.SuggestionStripView
import java.util.concurrent.Executors

/** The items copied on the other devices of the user. */
internal interface RemoteClips {
    /** The newest item that was neither pasted nor replaced by a later copy. */
    fun latest(): RemoteClip?

    /** A copy made on this phone came after the item [id]: it is not offered anymore. */
    fun supersede(id: String)

    /** Pastes the item [id] into the field. */
    fun paste(id: String)

    /** The keyboard opened in a field: catches up with what changed meanwhile. */
    fun sync()

    /** True for a clip the sync app put into the clipboard: a copy of an item from another device. */
    fun mirrors(clip: ClipData): Boolean
}

/**
 * Keeps the clipboard history up to date and shows a clip that was just copied as chips on the strip, until
 * it is pasted or gets too old. An item copied on another device takes the strip while it is newer than the
 * local clip. Main thread only.
 *
 * @param strip the strip of the current input view, if there is one
 * @param clock the time the age of a clip is measured against
 * @param remote the items copied on the other devices of the user
 * @param primaryClip reads the clipboard: a call into the system, so it is made once per update
 */

internal class ClipChipsControl(
    private val context: Context,
    private val prefs: ClipboardPrefs,
    private val store: ClipboardHistoryStore,
    private val strip: () -> SuggestionStripView?,
    private val clock: () -> Long,
    private val remote: RemoteClips? = null,
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

    /**
     * Catches up with what was copied, also while the keyboard was not running and on the other devices, then
     * updates the chips.
     */
    fun sync() {
        remote?.sync()
        val clip = if (prefs.recording || chipsWanted) primaryClip() else null
        if (prefs.recording) store.record(clip)
        show(clip)
    }

    /** A chip was tapped: its clip is not offered again. Returns what the chips stand for. */
    fun consume(): ClipPayload? {
        consumedClipAt = clipAt
        return payload
    }

    /**
     * Pastes the item from another device behind [chip]; false for a chip of a local clip. The copy of the item the
     * sync app put into the clipboard is not offered afterwards either.
     */
    fun pasteRemote(chip: ClipChip): Boolean {
        val id = chip.remote ?: return false
        remote?.paste(id)
        primaryClip()?.takeIf { remote?.mirrors(it) == true }?.let { consumedClipAt = it.description.timestamp }
        refresh()
        return true
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
        val local = clip?.takeUnless { remote?.mirrors(it) == true }
        val other = freshRemote(local?.let { copiedAt })
        if (other != null) {
            payload = null
            strip.setClipChips(listOf(ClipChips.of(other)))
            val remoteAge = (clock() - other.createdAt).coerceAtLeast(0L)
            handler.postDelayed(expire, REMOTE_LIFETIME_MILLIS - remoteAge)
            return
        }
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

    /**
     * The item from another device to show, unless it is too old or a local copy made at [localAt] came after it;
     * such an item is not offered again.
     */
    private fun freshRemote(localAt: Long?): RemoteClip? {
        val clip = remote?.latest()?.takeIf { prefs.chipsShown } ?: return null
        if (clock() - clip.createdAt >= REMOTE_LIFETIME_MILLIS) return null
        if (localAt != null && localAt >= clip.createdAt) {
            remote.supersede(clip.id)
            return null
        }
        return clip
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

        // Copied on a computer, the item is often pasted on the phone a few minutes later.
        const val REMOTE_LIFETIME_MILLIS = 5 * 60_000L
        const val THUMBNAIL_SIDE = 96
    }
}
