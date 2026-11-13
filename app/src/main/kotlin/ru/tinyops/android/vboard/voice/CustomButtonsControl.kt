package ru.tinyops.android.vboard.voice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.QuickReply
import ru.tinyops.android.vboard.core.voice.ReplyMember
import ru.tinyops.android.vboard.core.voice.SavedPrompt
import ru.tinyops.android.vboard.core.voice.membersOf
import ru.tinyops.android.vboard.core.voice.topLevel
import ru.tinyops.android.vboard.ui.ButtonIcons
import ru.tinyops.android.vboard.ui.StripButton

/**
 * Connects [ButtonActions] to the focused field and decides which custom buttons the strip shows: those
 * turned on, in a field that is not a password, and those that ask for text only where there is some. The
 * buttons meant for the fullscreen mode go to its panel instead, with or without text. The settings are
 * read once per field, so typing costs nothing without buttons. Along with the buttons the strip learns how
 * many it may get in this field, to keep room for them. A button that asks for its prompt shows the
 * collection through [onChoosePrompt]. Main thread only.
 */
internal class CustomButtonsControl(
    private val context: Context,
    private val connection: () -> InputConnection?,
    private val onButtons: (List<StripButton>, Int) -> Unit,
    private val onPanelButtons: (List<StripButton>) -> Unit,
    private val onChoosePrompt: (List<SavedPrompt>, (String) -> Unit) -> Unit,
    onMessage: (Int) -> Unit,
    private val results: PromptResultStore? = null,
) {
    private val prefs = VoicePrefs(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val enhancer = TextEnhancer()
    private var ready: List<CustomButton> = emptyList()
    private var replies: List<QuickReply> = emptyList()
    private var fieldHasText = false

    private val actions =
        ButtonActions(
            scope = scope,
            editor = ButtonField(connection),
            copy = ::copyToClipboard,
            paste = ::textFromClipboard,
            enhance = { command, text ->
                val (server, effort) = prefs.commandConnection(command)
                enhancer.enhance(server, text, effort).also { answer ->
                    results?.recordAnswer(answer, prefs.prompts.library.tags(command.promptIds))
                }
            },
            choose = { onChosen -> onChoosePrompt(prefs.prompts.library.pickable, onChosen) },
            onState = ::publish,
            onMessage = onMessage,
        )

    /** The buttons that stand on their own, groups without a member that works left out. */
    private val shown: List<CustomButton>
        get() = ready.topLevel().filter { !it.group || ready.membersOf(it.id).isNotEmpty() }

    /**
     * What the strip is given: every button, those that the field has no use for now marked as hidden, which
     * keeps them where the second row shows them anyway. A request or an undo outlives a field that became empty.
     */
    val visible: List<StripButton>
        get() =
            shown.filter { !it.fullscreenOnly }.map {
                stripButton(
                    it,
                    hidden =
                        !actions.busy && !it.shownFor(fieldHasText),
                )
            }

    /** How many buttons the strip of this field may show, with text in it or without. */
    private val planned: Int
        get() = shown.count { !it.fullscreenOnly }

    /** What the panel of the fullscreen mode shows. */
    val panel: List<StripButton>
        get() = shown.filter { it.fullscreenOnly }.map(::stripButton)

    private fun stripButton(
        button: CustomButton,
        hidden: Boolean = false,
    ): StripButton {
        val members =
            when {
                button.group -> ready.membersOf(button.id).map(::stripButton)
                // While a reply can be undone, the button undoes it instead of offering the list again.
                activeReplyOf(button.id) != null -> emptyList()
                button.tap?.action == ButtonAction.QUICK_REPLIES -> replyRows(button)
                else -> emptyList()
            }
        // A group shows the work of its members, so that a request is seen with the popup closed.
        val state =
            members.map { it.state }.firstOrNull { it != ProofreadState.IDLE }
                ?: actions.stateOf(activeReplyOf(button.id) ?: button.id)
        return StripButton(
            id = button.id,
            icon = ButtonIcons.of(button),
            state = state,
            color = button.color,
            placement = button.placement,
            // The undo of a member is held on its row: the button that opens the list has none of its own.
            holds =
                if (state ==
                    ProofreadState.UNDO
                ) {
                    members.isEmpty()
                } else {
                    button.hold != null && state == ProofreadState.IDLE
                },
            hidden = hidden,
            secondRow = button.secondRow,
            description = description(button),
            members = members,
        )
    }

    /** The rows of the replies a quick replies button offers, each with an undo of its own. */
    private fun replyRows(button: CustomButton): List<StripButton> {
        val command = button.tap ?: return emptyList()
        return replyChoice(command).map { reply ->
            val id = ReplyMember.id(button.id, reply.id)
            StripButton(
                id = id,
                icon = R.drawable.ic_chat,
                state = actions.stateOf(id),
                color = button.color,
                holds = actions.stateOf(id) == ProofreadState.UNDO,
                description = reply.text.replace(WHITESPACE, " "),
            )
        }
    }

    private fun replyChoice(command: ButtonCommand) = replies.choiceOf(command)

    private fun description(button: CustomButton): String =
        button.name.trim().ifEmpty {
            context.getString(
                when (button.main?.action) {
                    ButtonAction.CLEAR -> R.string.custom_button_action_clear
                    ButtonAction.COPY -> R.string.custom_button_action_copy
                    ButtonAction.PASTE -> R.string.custom_button_action_paste
                    ButtonAction.PROMPT -> R.string.custom_button_action_prompt
                    ButtonAction.QUICK_REPLIES -> R.string.custom_button_action_quick_replies
                    null -> if (button.group) R.string.custom_button_group_title else R.string.custom_button_title
                },
            )
        }

    private fun publish() {
        onButtons(visible, planned)
        onPanelButtons(panel)
    }

    fun onStartInput(
        textField: Boolean,
        selectionEnd: Int,
    ) {
        actions.reset()
        ready = if (textField) prefs.customButtons.buttons.mapNotNull(prefs::workingButton) else emptyList()
        replies = if (ready.isEmpty()) emptyList() else prefs.quickReplies.replies
        fieldHasText = false
        onSelectionChanged(selectionEnd)
        if (!fieldHasText) publish()
    }

    /** As in [ProofreadControl], only with the cursor at the very start the editor is asked about the text. */
    fun onSelectionChanged(selectionEnd: Int) {
        if (ready.isEmpty()) return
        val hasText = selectionEnd > 0 || !connection()?.getTextAfterCursor(1, 0).isNullOrEmpty()
        if (hasText == fieldHasText) return
        fieldHasText = hasText
        publish()
    }

    /** A tap on the button, or a hold when [held]. */
    fun onGesture(
        id: String,
        held: Boolean,
    ) {
        val reply = ReplyMember.parse(id)
        if (reply != null) return onReply(id, reply.first, reply.second)
        activeReplyOf(id)?.let { return actions.trigger(it, null) }
        val button = ready.firstOrNull { it.id == id } ?: return
        actions.trigger(id, if (held) button.hold else button.tap)
    }

    /** A tap on the row [id] of the reply [replyId] in the list of the button [buttonId]. */
    private fun onReply(
        id: String,
        buttonId: String,
        replyId: String,
    ) {
        val command = ready.firstOrNull { it.id == buttonId }?.tap ?: return
        val reply = replyChoice(command).firstOrNull { it.id == replyId } ?: return
        actions.insertReply(id, command.insert, reply.text)
    }

    /** What the field would hold after the undo of the button [id], for the preview of a held undo arrow. */
    fun undoPreview(id: String): String? = actions.undoPreview(activeReplyOf(id) ?: id)

    /** The row of the button [buttonId] whose reply is at work or can be undone, if any. */
    private fun activeReplyOf(buttonId: String): String? =
        actions.activeId?.takeIf { ReplyMember.parse(it)?.first == buttonId }

    fun reset() = actions.reset()

    /**
     * The fullscreen mode is over: a request or an undo of a button that only its panel shows would go on with
     * nothing to show it or to cancel it, and would keep the buttons of the strip waiting, so it ends here.
     */
    fun onFullscreenLeft() {
        val id = actions.activeId ?: return
        if (visible.none { it.holdsId(id) }) actions.reset()
    }

    fun destroy() = scope.cancel()

    private fun textFromClipboard(): String? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    private fun copyToClipboard(text: String) {
        context
            .getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(null, text))
    }
}

/** The focused field, reached through its [InputConnection]. */
internal class ButtonField(
    private val connection: () -> InputConnection?,
) : ButtonActions.Editor,
    Proofreader.Editor by ProofreadField(connection) {
    override fun clear() {
        val ic = connection() ?: return
        ic.beginBatchEdit()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        ic.endBatchEdit()
    }

    override fun commit(replacement: String) {
        connection()?.commitText(replacement, 1)
    }
}

private val WHITESPACE = Regex("\\s+")

/** Whether this button, or a row of a list it opens, is the button [id]. */
private fun StripButton.holdsId(id: String): Boolean = this.id == id || members.any { it.holdsId(id) }
