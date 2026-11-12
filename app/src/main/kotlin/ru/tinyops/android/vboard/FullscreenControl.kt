package ru.tinyops.android.vboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import ru.tinyops.android.vboard.core.voice.ButtonColor
import ru.tinyops.android.vboard.core.voice.ButtonPlacement
import ru.tinyops.android.vboard.ui.FullscreenEditorView
import ru.tinyops.android.vboard.ui.KeyboardTheme
import ru.tinyops.android.vboard.ui.KeyboardThemes
import ru.tinyops.android.vboard.ui.StripButton
import ru.tinyops.android.vboard.voice.CustomButtonsControl

/**
 * The fullscreen mode, in which the text of the field takes the screen above the keys. It is on only after
 * a tap on the strip, until the keyboard hides or the field changes. Main thread only.
 *
 * @param showEditor hands a new text area to the input method service
 * @param applyMode makes the service look at [active] again
 * @param finishWord ends the word being composed, which an action on the whole text must not split
 * @param onTextUndone tells that the text changed behind the keyboard's back
 * @param onButton what the strip shows: the place of the button, null without it, and the color of its icon
 * @param customButtons gives the panel of the text area its buttons and takes the gestures on them
 */
internal class FullscreenControl(
    private val context: Context,
    private val connection: () -> InputConnection?,
    private val showEditor: (View) -> Unit,
    private val applyMode: () -> Unit,
    private val finishWord: () -> Unit,
    private val onTextUndone: () -> Unit,
    private val onButton: (ButtonPlacement?, ButtonColor?) -> Unit,
    private val customButtons: CustomButtonsControl,
    private val onMessage: (Int) -> Unit,
) : FullscreenEditorView.Listener {
    private val prefs = KeyboardPrefs(context)
    private var fieldAllows = false
    private var editorView: FullscreenEditorView? = null

    var active = false
        private set

    fun createView(): View {
        val theme = KeyboardTheme(context, KeyboardThemes.selected(context).palette(context))
        return FullscreenEditorView(context, theme).also {
            it.listener = this
            it.customButtons = customButtons.panel
            it.undoPreview.source = { id ->
                if (id == FullscreenEditorView.UNDO_ID) textAfterUndo() else customButtons.undoPreview(id)
            }
            editorView = it
        }
    }

    /** The panel follows the buttons of a new field and the state of the one at work. */
    fun showCustomButtons(buttons: List<StripButton>) {
        editorView?.customButtons = buttons
    }

    fun onStartInput(allowed: Boolean) {
        fieldAllows = allowed
        syncButton()
    }

    /** While the mode is on the panel of the text area offers to leave it, so the strip has no button. */
    private fun syncButton() {
        val available = prefs.fullscreenEnabled && fieldAllows && !active
        onButton(if (available) prefs.fullscreenPlacement else null, prefs.fullscreenColor)
    }

    /** An editor that does not report its text would leave the area empty, so it is not even tried. */
    fun enter() {
        val ic = connection() ?: return
        if (ic.getExtractedText(ExtractedTextRequest(), 0) == null) {
            onMessage(R.string.fullscreen_unsupported)
            return
        }
        // A new view picks up a theme chosen since the last time.
        showEditor(createView())
        active = true
        applyMode()
        syncButton()
    }

    /** While the keyboard is hiding the window keeps its size, or it would visibly shrink before it goes. */
    fun leave(applyNow: Boolean) {
        if (!active) return
        active = false
        customButtons.onFullscreenLeft()
        if (applyNow) applyMode()
        syncButton()
    }

    override fun onEditorSelectAll() {
        finishWord()
        connection()?.performContextMenuAction(android.R.id.selectAll)
    }

    /** Ctrl+Z, because more editors take the shortcut than the undo action of the context menu. */
    override fun onEditorUndo() {
        finishWord()
        onTextUndone()
        val ic = connection() ?: return
        sendUndoKey(ic, redo = false)
    }

    /**
     * The text an undo would leave in the field, for the preview of a held undo button. An editor cannot tell it
     * beforehand, so the undo is made and taken back at once with Ctrl+Shift+Z; if the editor does not take the
     * redo back, the text and the selection are put back by hand. Null when there is nothing to undo.
     */
    private fun textAfterUndo(): String? {
        finishWord()
        val ic = connection() ?: return null
        val request = ExtractedTextRequest()
        val before = ic.getExtractedText(request, 0) ?: return null
        val original = before.text.toString()
        sendUndoKey(ic, redo = false)
        val undone = ic.getExtractedText(request, 0)?.text?.toString()
        sendUndoKey(ic, redo = true)
        val restored = ic.getExtractedText(request, 0)?.text?.toString()
        if (restored != original) {
            ic.beginBatchEdit()
            ic.setSelection(0, restored?.length ?: 0)
            ic.commitText(original, 1)
            ic.setSelection(before.selectionStart, before.selectionEnd)
            ic.endBatchEdit()
        }
        return undone?.takeIf { it != original }
    }

    private fun sendUndoKey(
        ic: InputConnection,
        redo: Boolean,
    ) {
        val now = SystemClock.uptimeMillis()
        val meta = if (redo) KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON else KeyEvent.META_CTRL_ON
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            ic.sendKeyEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_Z, 0, meta))
        }
    }

    override fun onEditorCopy(text: CharSequence) {
        if (text.isEmpty()) return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    override fun onEditorClose() = leave(applyNow = true)

    override fun onEditorButton(
        id: String,
        held: Boolean,
    ) {
        finishWord()
        customButtons.onGesture(id, held)
    }
}
