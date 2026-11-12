package ru.tinyops.android.vboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.clipboard.ClipPayload
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClipboardHistoryStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private var changes = 0

    @Before
    fun cleanUp() {
        File(context.filesDir, "clipboard").deleteRecursively()
    }

    private fun newStore() =
        ClipboardHistoryStore(context, executor = { it.run() }, notify = { it.run() }).also {
            it.onChanged = { changes++ }
        }

    private fun textClip(
        text: String,
        at: Long,
    ): ClipData {
        val clip = ClipData.newPlainText("label", text)
        ClipDescriptionHack.setTimestamp(clip.description, at)
        return clip
    }

    private fun imageClip(
        file: File,
        at: Long,
    ): ClipData {
        val clip = ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(Uri.fromFile(file)))
        ClipDescriptionHack.setTimestamp(clip.description, at)
        return clip
    }

    private fun texts(store: ClipboardHistoryStore) =
        store.history.entries.map { (it.payload as ClipPayload.Text).text }

    @Test
    fun `text clips are remembered newest first and survive a restart`() {
        val store = newStore()
        store.record(textClip("first", 10))
        store.record(textClip("second", 20))
        assertEquals(listOf("second", "first"), texts(store))
        assertEquals(listOf("second", "first"), texts(newStore()))
        assertEquals(2, changes)
    }

    @Test
    fun `a clip that was already seen is not added again after removal`() {
        val store = newStore()
        store.record(textClip("first", 10))
        store.remove(10)
        store.record(textClip("first", 10))
        assertEquals(emptyList<String>(), texts(store))
    }

    @Test
    fun `sensitive clips are never kept`() {
        val clip = textClip("secret", 10)
        clip.description.extras =
            android.os.PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        val store = newStore()
        store.record(clip)
        assertEquals(emptyList<String>(), texts(store))
    }

    @Test
    fun `an image is copied into the app and removed with its entry`() {
        val source = File(context.cacheDir, "shot.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val store = newStore()
        store.record(imageClip(source, 10))
        val image =
            store.history.entries
                .single()
                .payload as ClipPayload.Image
        assertEquals("image/png", image.mimeType)
        assertTrue(store.imageFile(image.source).exists())
        source.delete()
        assertTrue(store.imageFile(image.source).exists())
        store.remove(10)
        assertFalse(store.imageFile(image.source).exists())
    }

    @Test
    fun `entries past the capacity leave with their files`() {
        val store = newStore()
        for (index in 1..31) store.record(textClip("clip $index", index.toLong()))
        assertEquals(30, store.history.entries.size)
        assertEquals("clip 31", texts(store).first())
        store.clear()
        assertEquals(emptyList<String>(), texts(store))
    }

    @Test
    fun `the stored history is read on the executor, before the clips recorded meanwhile`() {
        newStore().record(textClip("first", 10))
        val queued = ArrayList<Runnable>()
        var notified = 0
        val store =
            ClipboardHistoryStore(
                context,
                executor = { queued += it },
                notify = {
                    notified++
                    it.run()
                },
            )
        assertEquals(emptyList<String>(), texts(store))
        // The keyboard catches up with the clipboard as it starts, while the index is still unread.
        store.record(textClip("first", 10))
        store.record(textClip("second", 20))
        queued.toList().forEach(Runnable::run)
        assertEquals(listOf("second", "first"), texts(store))
        assertEquals(2, notified)
    }

    @Test
    fun `an unreadable entry of the index is skipped instead of failing the keyboard start`() {
        File(context.filesDir, "clipboard").mkdirs()
        File(context.filesDir, "clipboard/history.json").writeText(
            """{"seen": 20, "entries": [1, {"id": 20, "text": "kept"}, {"id": "x"}, {"id": 10}]}""",
        )
        assertEquals(listOf("kept"), texts(newStore()))
    }

    @Test
    fun `writes leave no temporary files next to the index and the images`() {
        val source = File(context.cacheDir, "shot.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val store = newStore()
        store.record(textClip("first", 10))
        store.record(imageClip(source, 20))
        val directory = File(context.filesDir, "clipboard")
        assertEquals(setOf("history.json", "images"), directory.list().orEmpty().toSet())
        assertTrue(File(directory, "images").list().orEmpty().all { it.endsWith(".png") })
    }

    @Test
    fun `a closed store finishes its writes and ignores what comes after`() {
        val executor = Executors.newSingleThreadExecutor()
        val store = ClipboardHistoryStore(context, executor = executor, notify = { it.run() })
        store.record(textClip("first", 10))
        store.close()
        store.record(textClip("second", 20))
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("first"), texts(newStore()))
    }
}
