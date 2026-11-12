package ru.tinyops.android.vboard.voice

/** What the strip can do to the text of the field; all of it follows the same events of the keyboard. */
internal class TextActions(
    val proofreader: ProofreadControl,
    val customButtons: CustomButtonsControl,
) {
    fun onStartInput(
        textField: Boolean,
        selectionEnd: Int,
    ) {
        proofreader.onStartInput(textField, selectionEnd)
        customButtons.onStartInput(textField, selectionEnd)
    }

    fun onSelectionChanged(selectionEnd: Int) {
        proofreader.onSelectionChanged(selectionEnd)
        customButtons.onSelectionChanged(selectionEnd)
    }

    /** The user went on typing or left the field: requests and the chance to undo them are over. */
    fun reset() {
        proofreader.reset()
        customButtons.reset()
    }

    fun destroy() {
        proofreader.destroy()
        customButtons.destroy()
    }
}
