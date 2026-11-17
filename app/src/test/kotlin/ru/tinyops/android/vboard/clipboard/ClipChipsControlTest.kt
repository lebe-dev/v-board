package ru.tinyops.android.vboard.clipboard

import android.content.ClipData
import android.content.Context
import androidx.core.content.edit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.clipboard.ClipPayload
import ru.tinyops.android.vboard.core.clipboard.RemoteClip
import ru.tinyops.android.vboard.ui.KeyboardTheme
import ru.tinyops.android.vboard.ui.SuggestionStripView
import java.io.File

/** Opening the keyboard in a field reads the clipboard: a call into the system, made once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClipChipsControlTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = ClipboardPrefs(context)
    private var now = NOW
    private var clip: ClipData? = null
    private var reads = 0
    private lateinit var store: ClipboardHistoryStore
    private lateinit var control: ClipChipsControl
    private lateinit var strip: SuggestionStripView
    private var remote: RemoteClip? = null
    private val superseded = mutableListOf<String>()
    private val remotes =
        object : RemoteClips {
            override fun latest() = remote

            override fun supersede(id: String) {
                superseded += id
            }

            override fun paste(id: String) = Unit

            override fun sync() = Unit

            override fun mirrors(clip: ClipData) = clip.description.label == "Unison"
        }

    @Before
    fun setUp() {
        context.getSharedPreferences("clipboard_prefs", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        File(context.filesDir, "clipboard").deleteRecursively()
        store = ClipboardHistoryStore(context, executor = { it.run() }, notify = { it.run() })
        strip = SuggestionStripView(context, KeyboardTheme(context))
        control =
            ClipChipsControl(context, prefs, store, { strip }, { now }, remotes) {
                reads++
                clip
            }
    }

    @After
    fun tearDown() = control.destroy()

    private fun copied(
        text: String,
        at: Long,
    ) {
        clip = ClipData.newPlainText("t", text).also { ClipDescriptionHack.setTimestamp(it.description, at) }
    }

    private fun history() = store.history.entries.map { (it.payload as ClipPayload.Text).text }

    @Test
    fun `a fresh clip is read once for both the history and the chips`() {
        copied("привет", NOW - 1_000)
        control.sync()
        assertEquals(1, reads)
        assertEquals(listOf("привет"), history())
        assertEquals(ClipPayload.Text("привет"), control.consume())
    }

    @Test
    fun `a clip older than a minute goes to the history but not to the chips`() {
        copied("давно", NOW - 61_000)
        control.sync()
        assertEquals(1, reads)
        assertEquals(listOf("давно"), history())
        assertNull(control.consume())
    }

    @Test
    fun `a pasted clip is not offered again`() {
        copied("один раз", NOW - 1_000)
        control.sync()
        control.consume()
        control.refresh()
        assertNull(control.consume())
    }

    @Test
    fun `the clipboard is not read when neither the history nor the chips want it`() {
        prefs.keepHistory = false
        prefs.showOnStrip = false
        copied("секрет", NOW - 1_000)
        control.sync()
        control.refresh()
        assertEquals(0, reads)
        assertEquals(emptyList<String>(), history())
    }

    @Test
    fun `an item from another device newer than the local clip takes the strip`() {
        copied("здесь", NOW - 30_000)
        remote = RemoteClip("r1", image = false, "оттуда", "MacBook", NOW - 10_000)
        control.refresh()
        assertEquals(listOf("r1"), strip.shownClipChips.map { it.remote })
        assertEquals(listOf("оттуда"), strip.shownClipChips.map { it.label })
        assertNull(control.consume())
    }

    @Test
    fun `a later local copy replaces the item from another device`() {
        remote = RemoteClip("r1", image = false, "оттуда", "MacBook", NOW - 30_000)
        copied("здесь", NOW - 10_000)
        control.refresh()
        assertEquals(listOf("здесь"), strip.shownClipChips.map { it.label })
        assertEquals(listOf("r1"), superseded)
    }

    @Test
    fun `the copy the sync app put into the clipboard does not replace its item`() {
        remote = RemoteClip("r1", image = false, "оттуда", "MacBook", NOW - 2 * 60_000)
        clip =
            ClipData.newPlainText("Unison", "оттуда").also {
                ClipDescriptionHack.setTimestamp(it.description, NOW - 2 * 60_000 + 500)
            }
        control.refresh()
        assertEquals(listOf("r1"), strip.shownClipChips.map { it.remote })
        assertEquals(emptyList<String>(), superseded)
    }

    @Test
    fun `an item from another device is offered for a few minutes`() {
        remote = RemoteClip("r1", image = false, "оттуда", "MacBook", NOW - 4 * 60_000)
        control.refresh()
        assertEquals(1, strip.shownClipChips.size)
        now += 2 * 60_000
        control.refresh()
        assertEquals(emptyList<String>(), strip.shownClipChips.map { it.label })
    }

    @Test
    fun `an item from another device follows the switch of the chips`() {
        prefs.showOnStrip = false
        remote = RemoteClip("r1", image = false, "оттуда", "MacBook", NOW - 1_000)
        control.refresh()
        assertEquals(0, strip.shownClipChips.size)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
