package ru.tinyops.android.vboard.clipboard

import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.util.readText
import androidx.core.util.writeBytes
import androidx.core.util.writeText
import org.json.JSONArray
import org.json.JSONObject
import ru.tinyops.android.vboard.DiskQueue
import ru.tinyops.android.vboard.core.clipboard.ClipEntry
import ru.tinyops.android.vboard.core.clipboard.ClipHistory
import ru.tinyops.android.vboard.core.clipboard.ClipPayload
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The clipboard history on disk: a JSON index and the copies of the images (the source app's uri
 * stops working once the clip is replaced). All disk work runs on [executor], reading the index
 * too: the store is made while the keyboard starts, and the index holds up to megabytes of text.
 * Files are written atomically. [history] is replaced as a whole and may be read from any thread,
 * [onChanged] runs through [notify], also once the stored history is read.
 */
class ClipboardHistoryStore(
    private val context: Context,
    executor: Executor = Executors.newSingleThreadExecutor(),
    private val notify: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) {
    private val directory = File(context.filesDir, DIRECTORY)
    private val index = AtomicFile(File(directory, INDEX))
    private val disk = DiskQueue(executor)

    @Volatile
    var history: ClipHistory = ClipHistory()
        private set

    /** The newest clip seen so far; touched only on the disk thread. */
    private var lastSeen = 0L

    @Volatile
    var onChanged: (() -> Unit)? = null

    init {
        disk.execute {
            load()
            if (history.entries.isNotEmpty()) notify(Runnable { onChanged?.invoke() })
        }
    }

    /** Remembers the clip unless it is older than what was already seen, which is known once the index is read. */
    fun record(clip: ClipData?) {
        if (clip == null || ClipboardContent.isSensitive(clip)) return
        val timestamp = clip.description.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        val typeOf = { uri: Uri -> context.contentResolver.getType(uri) }
        disk.execute {
            if (timestamp <= lastSeen) return@execute
            lastSeen = timestamp
            val payload = ClipboardContent.from(clip, typeOf)?.let(::stored) ?: return@execute
            apply(history.add(ClipEntry(timestamp, payload)))
        }
    }

    fun remove(id: Long) = disk.execute { apply(history.remove(id)) }

    fun clear() = disk.execute { apply(history.clear()) }

    /** The keyboard is gone: what is queued is still written, nothing later is. */
    fun close() = disk.close()

    fun imageFile(source: String): File = File(File(directory, IMAGES), source)

    /** A uri the target app may read once the keyboard grants it. */
    fun imageUri(source: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.clips", imageFile(source))

    // The text is kept as is, an image is copied into the app; null when it cannot be kept.
    private fun stored(payload: ClipPayload): ClipPayload? =
        when (payload) {
            is ClipPayload.Text -> payload.takeIf { it.text.length <= MAX_TEXT }
            is ClipPayload.Image -> copyImage(payload)
        }

    private fun copyImage(image: ClipPayload.Image): ClipPayload? {
        val bytes =
            runCatching {
                context.contentResolver.openInputStream(image.source.toUri())?.use { it.readNBytes(MAX_IMAGE + 1) }
            }.getOrNull()
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_IMAGE) return null
        val name = sha1(bytes) + "." + extension(image.mimeType)
        val file = imageFile(name)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            // The name is the hash of the content: a copy cut short would stand for the image for good.
            AtomicFile(file).writeBytes(bytes)
        }
        return ClipPayload.Image(name, image.mimeType)
    }

    private fun apply(change: ClipHistory.Change) {
        history = change.history
        val used = history.entries.mapNotNull { (it.payload as? ClipPayload.Image)?.source }.toSet()
        for (entry in change.dropped) {
            val image = entry.payload as? ClipPayload.Image ?: continue
            if (image.source !in used) imageFile(image.source).delete()
        }
        save()
        notify(Runnable { onChanged?.invoke() })
    }

    private fun save() {
        val entries = JSONArray()
        for (entry in history.entries) entries.put(toJson(entry))
        val json = JSONObject().put(KEY_SEEN, lastSeen).put(KEY_ENTRIES, entries)
        directory.mkdirs()
        index.writeText(json.toString())
    }

    /** What cannot be read is left out: a broken entry must not keep the keyboard from starting. */
    private fun load() {
        if (!index.baseFile.exists()) return
        val json = runCatching { JSONObject(index.readText()) }.getOrNull() ?: return
        lastSeen = maxOf(lastSeen, json.optLong(KEY_SEEN))
        val entries = json.optJSONArray(KEY_ENTRIES) ?: return
        val loaded = (0 until entries.length()).mapNotNull { fromJson(entries.optJSONObject(it)) }
        history = ClipHistory(loaded.filter { entry -> hasFile(entry) })
    }

    private fun hasFile(entry: ClipEntry): Boolean {
        val image = entry.payload as? ClipPayload.Image ?: return true
        return imageFile(image.source).isFile
    }

    private fun toJson(entry: ClipEntry): JSONObject {
        val json = JSONObject().put("id", entry.id)
        when (val payload = entry.payload) {
            is ClipPayload.Text -> json.put("text", payload.text)
            is ClipPayload.Image -> json.put("image", payload.source).put("mime", payload.mimeType)
        }
        return json
    }

    private fun fromJson(json: JSONObject?): ClipEntry? {
        if (json == null) return null
        val id = json.optLong("id", -1)
        if (id < 0) return null
        val payload =
            when {
                json.has("text") -> ClipPayload.Text(json.optString("text"))
                json.has("image") ->
                    ClipPayload.Image(
                        json.optString("image").ifBlank { return null },
                        json.optString("mime", "image/*"),
                    )
                else -> return null
            }
        return ClipEntry(id, payload)
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun extension(mimeType: String): String =
        when (mimeType.substringAfter('/').substringBefore(';').lowercase()) {
            "jpeg", "jpg" -> "jpg"
            "gif" -> "gif"
            "webp" -> "webp"
            else -> "png"
        }

    private companion object {
        const val DIRECTORY = "clipboard"
        const val IMAGES = "images"
        const val INDEX = "history.json"
        const val KEY_SEEN = "seen"
        const val KEY_ENTRIES = "entries"
        const val MAX_TEXT = 100_000
        const val MAX_IMAGE = 10 * 1024 * 1024
    }
}
