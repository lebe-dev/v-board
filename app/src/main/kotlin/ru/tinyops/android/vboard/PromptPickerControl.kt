package ru.tinyops.android.vboard

import android.content.Context
import ru.tinyops.android.vboard.core.voice.SavedPrompt
import ru.tinyops.android.vboard.ui.KeyboardTheme
import ru.tinyops.android.vboard.ui.KeyboardThemes
import ru.tinyops.android.vboard.ui.PromptPickerPanelView

/**
 * The list of prompts a custom button offers: it takes the place of the strip and the keys until a prompt
 * is picked or the list is left. Main thread only.
 *
 * @param beforeOpen closes the other panels that take the place of the keys
 */
internal class PromptPickerControl(
    private val context: Context,
    private val cover: KeysCover,
    private val beforeOpen: () -> Unit,
) : PromptPickerPanelView.Listener {
    private var panel: PromptPickerPanelView? = null

    /** What to do with the prompt picked in the open list. */
    private var onChosen: ((String) -> Unit)? = null

    fun open(
        prompts: List<SavedPrompt>,
        onChosen: (String) -> Unit,
    ) {
        if (!cover.ready) return
        beforeOpen()
        val panel =
            panel ?: PromptPickerPanelView(
                context,
                KeyboardTheme(context, KeyboardThemes.selected(context).palette(context)),
            ).also {
                it.listener = this
                panel = it
            }
        panel.open(prompts)
        this.onChosen = onChosen
        cover.show(panel)
    }

    /** Back to the keys without a prompt: from the list's own button, and whenever the keyboard starts or stops. */
    fun close() {
        onChosen = null
        cover.hide(panel)
    }

    /** A new input view was made: the list is built again in it when needed. */
    fun detach() {
        onChosen = null
        panel = null
    }

    override fun onPromptPickerBack() = close()

    override fun onPromptPicked(id: String) {
        val chosen = onChosen
        close()
        chosen?.invoke(id)
    }
}
