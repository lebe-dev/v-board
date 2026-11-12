package ru.tinyops.android.vboard

import android.content.ClipboardManager
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.core.voice.ButtonVisibility
import ru.tinyops.android.vboard.ui.SuggestionStripView
import ru.tinyops.android.vboard.voice.VoicePrefs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class KeyboardServiceFullscreenTest {
    private val service = Robolectric.buildService(KeyboardService::class.java).create().get()

    /** A field whose connection reports text, or none at all when [reportsText] is false. */
    private inner class Connection(
        private val reportsText: Boolean,
    ) : BaseInputConnection(EditText(service), true) {
        val actions = ArrayList<Int>()

        override fun getExtractedText(
            request: ExtractedTextRequest?,
            flags: Int,
        ): ExtractedText? = if (reportsText) ExtractedText().apply { text = "текст" } else null

        override fun performContextMenuAction(id: Int): Boolean {
            actions += id
            return true
        }
    }

    private fun start(reportsText: Boolean = true): Connection {
        val connection = Connection(reportsText)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
        service.onCreateInputView()
        service.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        return connection
    }

    @Test
    fun `the keyboard is not fullscreen until asked`() {
        start()
        assertFalse(service.onEvaluateFullscreenMode())
    }

    @Test
    fun `the button opens the editor`() {
        start()
        service.onFullscreenClicked()
        assertTrue(service.onEvaluateFullscreenMode())
    }

    @Test
    fun `the strip has no button while the panel of the editor offers to leave`() {
        KeyboardPrefs(service).fullscreenEnabled = true
        ReflectionHelpers.setField(service, "mStartedInputConnection", Connection(reportsText = true))
        val strip = (service.onCreateInputView() as ViewGroup).getChildAt(0) as SuggestionStripView
        service.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        assertEquals(ButtonPlacement.LEFT, strip.fullscreenPlacement)
        service.onFullscreenClicked()
        assertNull(strip.fullscreenPlacement)
        service.fullscreen.onEditorClose()
        assertEquals(ButtonPlacement.LEFT, strip.fullscreenPlacement)
    }

    @Test
    fun `the strip takes the chosen color of the button`() {
        KeyboardPrefs(service).fullscreenColor = ButtonColor.RED
        val strip = (service.onCreateInputView() as ViewGroup).getChildAt(0) as SuggestionStripView
        service.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        assertEquals(ButtonColor.RED, strip.fullscreenColor)
    }

    private fun addPanelButton(name: String = "Панельная") {
        val store = VoicePrefs(service).customButtons
        store.save(
            store.add().copy(
                tap = ButtonCommand(ButtonAction.COPY),
                visibility = ButtonVisibility.FULLSCREEN,
                name = name,
            ),
        )
    }

    private fun panelButtons(
        view: View,
        name: String,
    ): List<View> =
        ArrayList<View>().also { view.findViewsWithText(it, name, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION) }

    @Test
    fun `the panel shows the fullscreen buttons and leaves out the others`() {
        addPanelButton()
        val store = VoicePrefs(service).customButtons
        store.save(store.add().copy(tap = ButtonCommand(ButtonAction.COPY), name = "Обычная"))
        start()
        val editor = service.fullscreen.createView()
        assertEquals(1, panelButtons(editor, "Панельная").size)
        assertEquals(0, panelButtons(editor, "Обычная").size)
    }

    @Test
    fun `the panel shows a button without an action and leaves out a turned off one`() {
        val store = VoicePrefs(service).customButtons
        store.save(store.add(ButtonVisibility.FULLSCREEN).copy(name = "Пустая"))
        store.save(
            store.add(ButtonVisibility.FULLSCREEN).copy(
                tap = ButtonCommand(ButtonAction.COPY),
                name = "Выключенная",
                enabled = false,
            ),
        )
        start()
        val editor = service.fullscreen.createView()
        assertEquals(1, panelButtons(editor, "Пустая").size)
        assertEquals(0, panelButtons(editor, "Выключенная").size)
    }

    @Test
    fun `a tap on a panel button acts on the field`() {
        addPanelButton()
        val connection = start()
        connection.commitText("текст", 1)
        val editor = service.fullscreen.createView()
        panelButtons(editor, "Панельная").single().performClick()
        val clip = service.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals("текст", clip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `a field without buttons leaves the panel to its own`() {
        addPanelButton()
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        service.onCreateInputView()
        service.onStartInputView(EditorInfo().apply { inputType = password }, false)
        assertEquals(0, panelButtons(service.fullscreen.createView(), "Панельная").size)
    }

    @Test
    fun `a field that reports no text stays as it is`() {
        start(reportsText = false)
        service.onFullscreenClicked()
        assertFalse(service.onEvaluateFullscreenMode())
    }

    private fun textHiddenIn(info: EditorInfo): Boolean {
        service.onUpdateExtractingVisibility(info)
        return ReflectionHelpers.getField(service, "mExtractViewHidden")
    }

    @Test
    fun `the text stays visible in a field that forbids the extract view`() {
        val info =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
        start()
        assertTrue(textHiddenIn(info))
        service.onFullscreenClicked()
        assertFalse(textHiddenIn(info))
    }

    @Test
    fun `hiding the keyboard closes the editor`() {
        start()
        service.onFullscreenClicked()
        service.onFinishInputView(false)
        assertFalse(service.onEvaluateFullscreenMode())
    }

    @Test
    fun `leaving the field closes the editor`() {
        start()
        service.onFullscreenClicked()
        service.onFinishInputView(true)
        assertFalse(service.onEvaluateFullscreenMode())
    }

    @Test
    fun `the panel closes the editor`() {
        start()
        service.onFullscreenClicked()
        service.fullscreen.onEditorClose()
        assertFalse(service.onEvaluateFullscreenMode())
    }

    @Test
    fun `the panel selects all through the field`() {
        val connection = start()
        service.fullscreen.onEditorSelectAll()
        assertEquals(listOf(android.R.id.selectAll), connection.actions)
    }

    @Test
    fun `the panel copies what it is given and nothing when it is empty`() {
        start()
        val clipboard = service.getSystemService(ClipboardManager::class.java)
        service.fullscreen.onEditorCopy("")
        assertFalse(clipboard.hasPrimaryClip())
        service.fullscreen.onEditorCopy("кусок")
        val clip = clipboard.primaryClip?.getItemAt(0)
        assertEquals("кусок", clip?.text.toString())
    }
}
