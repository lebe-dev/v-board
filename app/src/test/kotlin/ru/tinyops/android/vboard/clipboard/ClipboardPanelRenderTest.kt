package ru.tinyops.android.vboard.clipboard

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.tinyops.android.vboard.core.voice.DictationResults
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome
import ru.tinyops.android.vboard.core.voice.PromptTag
import ru.tinyops.android.vboard.ui.ClipboardPanelView
import ru.tinyops.android.vboard.ui.KeyboardTheme
import ru.tinyops.android.vboard.voice.PromptResultStore
import ru.tinyops.android.vboard.voice.TextEnhancer
import java.io.File

/** Renders the clipboard history panel to `app/build/screenshots/` for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-night-420dpi")
class ClipboardPanelRenderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = ClipboardPrefs(context)

    private fun store(): ClipboardHistoryStore {
        File(context.filesDir, "clipboard").deleteRecursively()
        return ClipboardHistoryStore(context, executor = { it.run() }, notify = { it.run() })
    }

    private fun stamped(
        text: String,
        at: Long,
    ): ClipData {
        val clip = ClipData.newPlainText("t", text)
        ClipDescriptionHack.setTimestamp(clip.description, at)
        return clip
    }

    private fun results(): PromptResultStore {
        File(context.filesDir, "prompt_results").deleteRecursively()
        return PromptResultStore(context, { true }, executor = { it.run() }, notify = { it.run() })
    }

    private fun panel(
        store: ClipboardHistoryStore,
        results: PromptResultStore? = null,
        resultsShown: Boolean = false,
    ): ClipboardPanelView {
        val panel = ClipboardPanelView(context, KeyboardTheme(context), store, prefs, results) { resultsShown }
        val width = context.resources.displayMetrics.widthPixels
        val height = (context.resources.displayMetrics.density * 330).toInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, width, height)
        return panel
    }

    private fun render(
        view: View,
        name: String,
    ) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.width, view.height)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#FF1B1B1B"))
        view.draw(canvas)
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `history renders text and link cards in two columns`() {
        prefs.keepHistory = true
        val store = store()
        store.record(
            stamped(
                "Когда мне нужно было пакетно обработать большой массив данных, " +
                    "я арендовал виртуалку на vast.ai, в итоге за 12 часов управился",
                1,
            ),
        )
        store.record(stamped("vast.ai", 2))
        store.record(stamped("Короткий текст", 3))
        val panel = panel(store)
        render(panel, "clipboard-panel-dark")
        assertEquals(3, store.history.entries.size)
    }

    @Test
    fun `the panel says so when the history is off`() {
        prefs.keepHistory = false
        val panel = panel(store())
        render(panel, "clipboard-panel-off-dark")
        prefs.keepHistory = true
    }

    private fun View.texts(): List<String> =
        when (this) {
            is TextView -> listOf(text.toString())
            is android.view.ViewGroup -> (0 until childCount).flatMap { getChildAt(it).texts() }
            else -> emptyList()
        }

    private fun View.clickText(text: String): Boolean {
        if (visibility != View.VISIBLE) return false
        if (this is TextView && this.text.toString() == text) return performClick()
        if (this !is android.view.ViewGroup) return false
        return (0 until childCount).any { getChildAt(it).clickText(text) }
    }

    @Test
    fun `without the setting the panel has no prompts tab`() {
        val panel = panel(store(), results(), resultsShown = false)
        assertFalse(panel.clickText("Prompts"))
    }

    @Test
    fun `the prompts tab shows dictation pairs and filters them by prompt`() {
        val polish = PromptTag("p1", "Polish")
        val formal = PromptTag("p2", "Formal tone")
        val results = results()
        results.recordAnswer(
            TextEnhancer.Result("Dear colleagues, the meeting is moved to Friday.", EnhancementOutcome.SUCCESS),
            listOf(formal),
        )
        results.recordDictation(
            DictationResults.Texts("ну короче встреча в пятницу в три", "Встреча в пятницу в три."),
            listOf(polish),
        )
        val panel = panel(store(), results, resultsShown = true)
        assertTrue(panel.clickText("Prompts"))
        render(panel, "prompt-results-dark")
        val all = panel.texts()
        assertTrue(all.containsAll(listOf("All", "Transcript", "Polish", "Formal tone", "Встреча в пятницу в три.")))

        assertTrue(panel.clickText("Formal tone"))
        render(panel, "prompt-results-filtered-dark")
        val filtered = panel.texts()
        assertTrue("Dear colleagues, the meeting is moved to Friday." in filtered)
        assertFalse("Встреча в пятницу в три." in filtered)
    }

    private fun View.withDescription(description: String): List<View> {
        val own = if (contentDescription?.toString() == description) listOf(this) else emptyList()
        if (this !is android.view.ViewGroup) return own
        return own + (0 until childCount).flatMap { getChildAt(it).withDescription(description) }
    }

    private fun View.touch(
        action: Int,
        y: Float,
    ) {
        val event = MotionEvent.obtain(0, 0, action, width / 2f, y, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `in the delete mode a result is dragged by its handle into a new order`() {
        val results = results()
        for ((index, text) in listOf("first", "second", "third").withIndex()) {
            results.recordAnswer(
                TextEnhancer.Result(text, EnhancementOutcome.SUCCESS),
                listOf(PromptTag("p$index", "Prompt $index")),
            )
        }
        assertEquals(listOf("third", "second", "first"), results.history.entries.map { it.text })
        val panel = panel(store(), results, resultsShown = true)
        assertTrue(panel.clickText("Prompts"))
        panel.withDescription("Delete items").single().performClick()
        render(panel, "prompt-results-reorder-dark")
        val handles = panel.withDescription("Drag to reorder")
        assertEquals(3, handles.size)

        val handle = handles.first()
        val row = handle.parent as View
        handle.touch(MotionEvent.ACTION_DOWN, 1f)
        handle.touch(MotionEvent.ACTION_MOVE, 1f + row.height * 1.6f)
        handle.touch(MotionEvent.ACTION_UP, 1f + row.height * 1.6f)
        assertEquals(listOf("second", "third", "first"), results.history.entries.map { it.text })
    }

    private fun View.longClickText(text: String): Boolean {
        if (this is TextView && this.text.toString() == text) {
            var card: View = this
            while (!card.isLongClickable) card = card.parent as View
            return card.performLongClick()
        }
        if (this !is android.view.ViewGroup) return false
        return (0 until childCount).any { getChildAt(it).longClickText(text) }
    }

    private fun ClipboardPanelView.deleteFromMenu() {
        assertTrue(requireNotNull(heldMenu).performClick())
    }

    @Test
    fun `holding a clipboard card opens a menu that deletes it`() {
        prefs.keepHistory = true
        val store = store()
        store.record(stamped("first", 1))
        store.record(stamped("second", 2))
        val panel = panel(store)
        assertTrue(panel.longClickText("first"))
        panel.deleteFromMenu()
        assertEquals(
            listOf("second"),
            store.history.entries.map {
                (it.payload as ru.tinyops.android.vboard.core.clipboard.ClipPayload.Text).text
            },
        )
    }

    @Test
    fun `holding a prompt result opens a menu that deletes it`() {
        val results = results()
        results.recordAnswer(TextEnhancer.Result("keep", EnhancementOutcome.SUCCESS), listOf(PromptTag("p1", "Polish")))
        results.recordAnswer(TextEnhancer.Result("drop", EnhancementOutcome.SUCCESS), listOf(PromptTag("p1", "Polish")))
        val panel = panel(store(), results, resultsShown = true)
        assertTrue(panel.clickText("Prompts"))
        assertTrue(panel.longClickText("drop"))
        panel.deleteFromMenu()
        assertEquals(listOf("keep"), results.history.entries.map { it.text })
    }
}
