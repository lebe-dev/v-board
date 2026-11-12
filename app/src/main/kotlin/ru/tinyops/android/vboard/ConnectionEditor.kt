package ru.tinyops.android.vboard

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import ru.tinyops.android.vboard.core.input.ArrowKey
import ru.tinyops.android.vboard.core.input.CursorEditor
import ru.tinyops.android.vboard.core.input.CursorWindow
import ru.tinyops.android.vboard.core.input.TextEditor

/** [TextEditor] and [CursorEditor] over the focused editor's [InputConnection]. */
internal class ConnectionEditor(
    private val ic: InputConnection,
    private val service: InputMethodService,
) : TextEditor,
    CursorEditor {
    override fun textBeforeCursor(length: Int): CharSequence? = ic.getTextBeforeCursor(length, 0)

    override fun textAfterCursor(length: Int): CharSequence? = ic.getTextAfterCursor(length, 0)

    override fun replaceAroundCursor(
        before: Int,
        after: Int,
        text: CharSequence,
    ) {
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before, after)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    override fun commitText(text: CharSequence) {
        ic.commitText(text, 1)
    }

    override fun setComposingText(text: CharSequence) {
        ic.setComposingText(text, 1)
    }

    override fun finishComposingText() {
        ic.finishComposingText()
    }

    override fun sendBackspace() = service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)

    // sendKeyChar('\n') performs the editor action when there is one, otherwise sends enter.
    override fun sendEnter() = service.sendKeyChar('\n')

    override fun beginBatchEdit() {
        ic.beginBatchEdit()
    }

    override fun endBatchEdit() {
        ic.endBatchEdit()
    }

    override fun cursorWindow(length: Int): CursorWindow? =
        ic.getSurroundingText(length, length, 0)?.let {
            CursorWindow(it.text, it.selectionStart, it.selectionEnd, it.offset)
        }

    override fun setCursor(offset: Int) {
        ic.setSelection(offset, offset)
    }

    override fun sendArrow(key: ArrowKey) =
        service.sendDownUpKeyEvents(
            when (key) {
                ArrowKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                ArrowKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                ArrowKey.UP -> KeyEvent.KEYCODE_DPAD_UP
                ArrowKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            },
        )
}
