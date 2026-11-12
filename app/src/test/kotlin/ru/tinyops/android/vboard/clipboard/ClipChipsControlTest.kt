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

    @Before
    fun setUp() {
        context.getSharedPreferences("clipboard_prefs", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        File(context.filesDir, "clipboard").deleteRecursively()
        store = ClipboardHistoryStore(context, executor = { it.run() }, notify = { it.run() })
        val strip = SuggestionStripView(context, KeyboardTheme(context))
        control =
            ClipChipsControl(context, prefs, store, { strip }, { now }) {
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

    private companion object {
        const val NOW = 1_000_000L
    }
}
