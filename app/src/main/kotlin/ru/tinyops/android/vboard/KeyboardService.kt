package ru.tinyops.android.vboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.inputmethod.EditorInfoCompat
import ru.tinyops.android.vboard.clipboard.ClipChipsControl
import ru.tinyops.android.vboard.clipboard.ClipboardContent
import ru.tinyops.android.vboard.clipboard.ClipboardHistoryStore
import ru.tinyops.android.vboard.clipboard.ClipboardPrefs
import ru.tinyops.android.vboard.core.clipboard.ClipChip
import ru.tinyops.android.vboard.core.clipboard.ClipEntry
import ru.tinyops.android.vboard.core.clipboard.ClipKind
import ru.tinyops.android.vboard.core.clipboard.ClipPayload
import ru.tinyops.android.vboard.core.gesture.GestureLayoutFactory
import ru.tinyops.android.vboard.core.input.CursorEditor
import ru.tinyops.android.vboard.core.input.CursorLines
import ru.tinyops.android.vboard.core.input.CursorMotion
import ru.tinyops.android.vboard.core.input.InputLogic
import ru.tinyops.android.vboard.core.input.LineSpan
import ru.tinyops.android.vboard.core.input.ShiftState
import ru.tinyops.android.vboard.core.input.SpaceDragMode
import ru.tinyops.android.vboard.core.input.VisualLineGuard
import ru.tinyops.android.vboard.core.layout.KeySpec
import ru.tinyops.android.vboard.core.layout.KeyType
import ru.tinyops.android.vboard.core.layout.KeyboardGeometry
import ru.tinyops.android.vboard.core.layout.KeyboardLayouts
import ru.tinyops.android.vboard.core.layout.KeyboardMode
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.emoji.EmojiAssets
import ru.tinyops.android.vboard.emoji.EmojiPrefs
import ru.tinyops.android.vboard.settings.SettingsActivity
import ru.tinyops.android.vboard.stt.model.TouchPoint
import ru.tinyops.android.vboard.ui.ClipboardPanelView
import ru.tinyops.android.vboard.ui.EmojiPanelView
import ru.tinyops.android.vboard.ui.KeyboardTheme
import ru.tinyops.android.vboard.ui.KeyboardThemes
import ru.tinyops.android.vboard.ui.KeyboardView
import ru.tinyops.android.vboard.ui.SuggestionStripView
import ru.tinyops.android.vboard.ui.UndoPeek
import ru.tinyops.android.vboard.voice.CustomButtonsControl
import ru.tinyops.android.vboard.voice.PromptResultStore
import ru.tinyops.android.vboard.voice.ProofreadControl
import ru.tinyops.android.vboard.voice.TextActions
import ru.tinyops.android.vboard.voice.VoiceInput
import ru.tinyops.android.vboard.voice.VoicePrefs
import ru.tinyops.android.vboard.voice.VoiceState
import java.util.Locale

/** What the settings say about the buttons of the strip. */
private fun SuggestionStripView.applyLook(
    clipboard: ClipboardPrefs,
    voice: VoicePrefs,
    keyboard: KeyboardPrefs,
) {
    clipboardPlacement = clipboard.iconPlacement.takeIf { clipboard.enabled }
    clipboardColor = clipboard.iconColor
    micColor = voice.micColor
    proofreadColor = voice.proofreadColor
    secondRow = keyboard.stripSecondRow
    buttonWidthScale = keyboard.stripButtonWidth / PERCENT
}

private const val PERCENT = 100f

/**
 * The input method: Russian/English layouts with symbols pages, tap typing with prefix
 * suggestions, slide-to-type through the stt decoder and dictation through [VoiceInput].
 * Editing rules live in [InputLogic]; this class wires views, the decoder, voice input and the
 * editor connection together.
 */
class KeyboardService :
    InputMethodService(),
    KeyboardView.Listener,
    SuggestionStripView.Listener,
    ClipboardPanelView.Listener,
    EmojiPanelView.Listener,
    VoiceInput.Host {
    private lateinit var decoder: GestureDecoder
    private lateinit var logic: InputLogic
    private lateinit var voice: VoiceInput
    private lateinit var textActions: TextActions
    private var keyboardView: KeyboardView? = null
    private var stripView: SuggestionStripView? = null
    private var inputRoot: LinearLayout? = null
    private var panelView: ClipboardPanelView? = null
    private var emojiPanel: EmojiPanelView? = null
    private val cover = KeysCover({ inputRoot }, { stripView }, { keyboardView })
    private lateinit var promptPicker: PromptPickerControl
    private lateinit var emojiAssets: EmojiAssets
    private lateinit var emojiPrefs: EmojiPrefs

    private lateinit var clipboardPrefs: ClipboardPrefs
    private lateinit var voicePrefs: VoicePrefs
    private lateinit var clipStore: ClipboardHistoryStore
    private lateinit var promptResults: PromptResultStore
    private lateinit var clipChips: ClipChipsControl

    /** The time the age of a copied clip is measured against; tests replace it. */
    internal var clock: () -> Long = System::currentTimeMillis

    private var language = Language.ENGLISH
    private var mode = KeyboardMode.LETTERS
    private var profile = EditorProfile.from(null)

    /** The theme the current input view was built with; settings may select another one. */
    private var themeId: String? = null

    private var gestureSequence = 0
    private var gestureInProgress = false
    private var preemptivePending = false
    private var lastPreemptiveAt = 0L

    /** Text before the cursor, read once per gesture: the text cannot change under the finger. */
    private var gestureContext = ""

    /** A finger on the space bar is moving the cursor. */
    private var cursorDragging = false
    private val cursorLines = CursorLines()
    private val visualLine = VisualLineGuard()

    /** The current drag stays on its line, and the editor is reporting where the cursor is. */
    private var cursorKeptOnLine = false

    private lateinit var keyboardPrefs: KeyboardPrefs

    internal lateinit var fullscreen: FullscreenControl
        private set

    private fun createInputLogic() =
        InputLogic(
            completions = { prefix -> decoder.completions(language, prefix, InputLogic.SUGGESTIONS) },
            onSuggestions = { words -> stripView?.setSuggestions(words) },
            corrections = { word -> decoder.corrections(language, word, InputLogic.SUGGESTIONS) },
        )

    override fun onCreate() {
        super.onCreate()
        keyboardPrefs = KeyboardPrefs(this)
        decoder =
            GestureDecoder(
                openDictionary = { language -> DictionaryAssets.open(assets, language) },
                openNeural = { openNeuralEngine() },
                openBigrams = { language -> DictionaryAssets.openBigrams(assets, language) },
            )
        decoder.setSlideEngine(keyboardPrefs.slideEngine)
        logic = createInputLogic()
        // Read only when a result comes, long after onCreate has set the settings up. A result is the text of the
        // field it came from, and a request ends with its field, so the field's own wish is the one asked.
        promptResults =
            PromptResultStore(this, enabled = { voicePrefs.promptResultsEnabled && !profile.incognito })
        promptResults.onChanged = { panelView?.takeIf { it.isShown }?.refresh() }
        voice = VoiceInput(this, this, promptResults)
        val proofreader =
            ProofreadControl(
                context = this,
                connection = { currentInputConnection },
                onButton = { state, holds -> stripView?.apply { proofreadHolds = holds }?.proofreadState = state },
                onMessage = ::showMessage,
                results = promptResults,
            )
        promptPicker =
            PromptPickerControl(this, cover) {
                closeClipboardPanel()
                onEmojiBack()
            }
        val customButtons =
            CustomButtonsControl(
                context = this,
                connection = { currentInputConnection },
                onButtons = {
                    buttons,
                    planned,
                    ->
                    stripView?.apply { plannedButtons = planned }?.customButtons = buttons
                },
                onPanelButtons = { fullscreen.showCustomButtons(it) },
                onChoosePrompt = { prompts, onChosen -> promptPicker.open(prompts, onChosen) },
                onMessage = ::showMessage,
                results = promptResults,
            )
        textActions = TextActions(proofreader, customButtons)
        fullscreen =
            FullscreenControl(
                context = this,
                connection = { currentInputConnection },
                showEditor = ::setExtractView,
                applyMode = ::updateFullscreenMode,
                finishWord = {
                    editor()?.let { logic.commitComposing(it) }
                    logic.reset()
                },
                onTextUndone = { textActions.reset() },
                onButton = { placement, color ->
                    stripView?.fullscreenPlacement = placement
                    stripView?.fullscreenColor = color
                },
                customButtons = customButtons,
                onMessage = ::showMessage,
            )
        clipboardPrefs = ClipboardPrefs(this)
        voicePrefs = VoicePrefs(this)
        emojiAssets = EmojiAssets(assets)
        emojiPrefs = EmojiPrefs(this)
        clipStore = ClipboardHistoryStore(this)
        clipStore.onChanged = { panelView?.takeIf { it.isShown }?.refresh() }
        clipChips = ClipChipsControl(this, clipboardPrefs, clipStore, { stripView }, { clock() })
        clipChips.start()
        language = Language.fromCode(prefs().getString(PREF_LANGUAGE, null))
        logic.locale = language.locale
        // Active language first, then the other one so that switching is instant.
        decoder.load(language) { onDictionaryLoaded() }
        decoder.load(language.next()) { onDictionaryLoaded() }
    }

    override fun onDestroy() {
        clipChips.destroy()
        textActions.destroy()
        voice.destroy()
        decoder.shutdown()
        // After the voice and the text actions, whose last results may still be on their way to the disk.
        promptResults.close()
        clipStore.close()
        super.onDestroy()
    }

    /** The microphone shows up in fields that allow dictation, unless the user turned voice input off. */
    private val micShown: Boolean get() = profile.voiceAllowed && voicePrefs.voiceEnabled

    override fun onCreateInputView(): View = PerfTrace.section(PerfTrace.CREATE_VIEW) { createInputView() }

    private fun createInputView(): View {
        val spec = KeyboardThemes.selected(this)
        themeId = spec.id
        val theme = KeyboardTheme(this, spec.palette(this))
        val strip =
            SuggestionStripView(this, theme).also {
                it.listener = this
                it.undoPeek.preview.source = { id ->
                    if (id ==
                        UndoPeek.PROOFREAD
                    ) {
                        textActions.proofreader.undoPreview()
                    } else {
                        textActions.customButtons.undoPreview(id)
                    }
                }
                it.voiceState = voice.state
                it.voiceAvailable = micShown
                it.suggestionTimeoutMillis = keyboardPrefs.suggestionTimeout.millis
                it.proofreadHolds = textActions.proofreader.holds
                it.proofreadState = textActions.proofreader.button
                it.customButtons = textActions.customButtons.visible
                it.applyLook(clipboardPrefs, voicePrefs, keyboardPrefs)
            }
        val keyboard =
            KeyboardView(this, theme).also {
                it.id = R.id.keyboard_keys
                it.listener = this
            }
        stripView = strip
        keyboardView = keyboard
        panelView = null
        emojiPanel = null
        promptPicker.detach()
        refreshKeyboard()
        return LinearLayout(this).apply {
            inputRoot = this
            orientation = LinearLayout.VERTICAL
            background = theme.backgroundDrawable()
            // Key previews and long-press popups of the top row are drawn over the strip.
            clipChildren = false
            clipToPadding = false
            addView(strip, fullWidth())
            addView(keyboard, fullWidth())
            // The IME window is edge-to-edge: keep the keys clear of the navigation bar and of
            // display cutouts. With gesture navigation the bar itself is only the thin handle, while
            // the hide-keyboard and IME switcher buttons drawn under the keys are a taller tappable
            // element, so the larger of the two is reserved.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
                val tappable = insets.getInsets(WindowInsets.Type.tappableElement())
                view.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, tappable.bottom))
                insets
            }
            // A view swapped in after a theme change is attached once the window insets were already
            // dispatched, so without an explicit request the bottom padding would stay zero.
            addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        view.requestApplyInsets()
                    }

                    override fun onViewDetachedFromWindow(view: View) = Unit
                },
            )
        }
    }

    private fun fullWidth() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Only the user's tap on the strip switches to the fullscreen mode, also in landscape. */
    override fun onEvaluateFullscreenMode(): Boolean = fullscreen.active

    override fun onCreateExtractTextView(): View = fullscreen.createView()

    /** The system hides the text in a field with IME_FLAG_NO_EXTRACT_UI, which would leave the area empty. */
    override fun onUpdateExtractingVisibility(ei: EditorInfo) {
        if (fullscreen.active) setExtractViewShown(true) else super.onUpdateExtractingVisibility(ei)
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        PerfTrace.section(PerfTrace.START_INPUT) {
            profile = EditorProfile.from(info, suggestAlways = keyboardPrefs.suggestAlways)
            // A theme picked in the settings applies the next time the keyboard shows up.
            if (themeId != null && KeyboardThemes.selected(this).id != themeId) setInputView(onCreateInputView())
            keyboardView?.emojiEnabled = emojiPrefs.enabled
            logic.reset()
            logic.shift.reset()
            logic.suggestionsAllowed = profile.suggestionsAllowed
            stripView?.suggestionTimeoutMillis = keyboardPrefs.suggestionTimeout.millis
            stripView?.voiceAvailable = micShown
            stripView?.applyLook(clipboardPrefs, voicePrefs, keyboardPrefs)
            fullscreen.onStartInput(profile.fullscreenAllowed)
            textActions.onStartInput(profile.voiceAllowed, info?.initialSelEnd ?: 0)
            closeClipboardPanel()
            onEmojiBack()
            promptPicker.close()
            clipChips.sync()
            mode = profile.initialMode
            refreshKeyboard()
            updateAutoCaps()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cursorDragging = false
        editor()?.let { logic.showCorrections(it) }
        stopKeepingCursorOnLine()
        closeClipboardPanel()
        onEmojiBack()
        promptPicker.close()
        voice.onHidden()
        textActions.reset()
        editor()?.let { logic.commitComposing(it) }
        logic.reset()
        super.onFinishInputView(finishingInput)
        fullscreen.leave(applyNow = finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        editor()?.let {
            logic.onSelectionChanged(it, newSelStart, newSelEnd, candidatesEnd, correct = !cursorDragging)
        }
        textActions.onSelectionChanged(newSelEnd)
        // Asking the editor about capitalization for every step would only slow the cursor down.
        if (cursorDragging) {
            cursorLines.onSelectionChanged()
        } else {
            updateAutoCaps()
        }
    }

    // region Keys

    override fun onKey(key: KeySpec) = PerfTrace.section(PerfTrace.KEY_PRESS) { handleKey(key) }

    private fun handleKey(key: KeySpec) {
        when (key.type) {
            KeyType.CHARACTER -> onAlternate(key.output)
            KeyType.SPACE -> {
                textActions.reset()
                editor()?.let { logic.typeSpace(it, SystemClock.uptimeMillis()) }
            }
            KeyType.ENTER -> {
                textActions.reset()
                editor()?.let { logic.typeEnter(it) }
            }
            KeyType.SHIFT -> logic.shift.onTap(SystemClock.uptimeMillis())
            KeyType.SYMBOLS -> switchMode(KeyboardMode.SYMBOLS)
            KeyType.MORE_SYMBOLS -> switchMode(KeyboardMode.MORE_SYMBOLS)
            KeyType.ALPHABET -> switchMode(KeyboardMode.LETTERS)
            KeyType.LANGUAGE -> switchLanguage()
            KeyType.BACKSPACE -> onBackspace()
        }
        syncShift()
    }

    override fun onAlternate(text: String) {
        textActions.reset()
        editor()?.let { logic.typeCharacter(it, text) }
        syncShift()
    }

    override fun onBackspace() {
        textActions.reset()
        editor()?.let { logic.backspace(it) }
    }

    override fun onShiftLongPress() {
        logic.shift.onLongPress()
        syncShift()
    }

    override fun onLanguageLongPress() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    override fun onSuggestionPicked(word: String) {
        textActions.reset()
        editor()?.let { logic.pickSuggestion(it, word) }
    }

    override fun onSettingsClicked() = openSettings(requestPermission = false)

    override fun onClipboardClicked() = openClipboardPanel()

    override fun onFullscreenClicked() = fullscreen.enter()

    override fun onChipClicked(chip: ClipChip) {
        val payload = clipChips.consume()
        if (chip.kind == ClipKind.IMAGE && payload is ClipPayload.Image) {
            pasteImage(payload.source.toUri(), payload.mimeType)
        } else {
            chip.insert?.let(::insertText)
        }
        clipChips.refresh()
    }

    override fun onMicClicked() {
        textActions.reset()
        voice.toggle()
    }

    override fun onVoiceCancel() = voice.cancel()

    override fun onProofreadClicked() = onTextAction { textActions.proofreader.onTap() }

    override fun onProofreadHeld() = onTextAction { textActions.proofreader.onHold() }

    override fun onCustomButtonUsed(
        id: String,
        held: Boolean,
    ) = onTextAction {
        // A button of the fullscreen panel stays in reach while the list of prompts is open.
        promptPicker.close()
        textActions.customButtons.onGesture(id, held)
    }

    /** The composed word is finished first: its underline must not survive a replacement. */
    private inline fun onTextAction(action: () -> Unit) {
        editor()?.let { logic.commitComposing(it) }
        logic.reset()
        action()
    }

    // endregion

    // region Moving the cursor

    override fun onSpaceDragStart() {
        val editor = editor() ?: return
        cursorDragging = true
        cursorLines.reset()
        logic.startCursorMove(editor)
        cursorKeptOnLine = keyboardView?.spaceDragMode == SpaceDragMode.TRACKPAD
        if (cursorKeptOnLine) {
            visualLine.reset()
            currentInputConnection?.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
                InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER or
                    InputConnection.CURSOR_UPDATE_FILTER_VISIBLE_LINE_BOUNDS,
            )
        }
    }

    override fun onSpaceDragMove(
        chars: Int,
        lines: Int,
    ) {
        val editor = editor() ?: return
        // In a single-line field there is nowhere to go, and the field would lose the focus.
        if (lines != 0 && profile.multiLine) {
            // A step along the line would overwrite the arrow key, which the editor applies later.
            if (cursorLines.move(editor, lines, SystemClock.uptimeMillis())) visualLine.reset()
            return
        }
        val steps = if (cursorKeptOnLine) visualLine.allowedSteps(chars) else chars
        if (steps == 0) return
        moveAlongLine(editor, steps)
    }

    private fun moveAlongLine(
        editor: CursorEditor,
        steps: Int,
    ) {
        val step =
            if (cursorKeptOnLine) {
                CursorMotion.moveHorizontally(editor, steps, true, visualLine.lowEdge, visualLine.highEdge)
            } else {
                CursorMotion.moveHorizontally(editor, steps)
            }
        if (cursorKeptOnLine && step != null) visualLine.onMoved(step, steps)
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo) {
        if (!cursorKeptOnLine) return
        val editor = editor() ?: return
        val baseline = cursorAnchorInfo.insertionMarkerBaseline
        val visible = cursorAnchorInfo.visibleLineBounds
        if (!baseline.isNaN() && visible.isNotEmpty()) {
            cursorLines.onVisibleLines(baseline, visible.map { LineSpan(it.top, it.bottom) })
        }
        val line = visible.firstOrNull { baseline >= it.top && baseline <= it.bottom }
        val walk =
            visualLine.onMarker(
                editor,
                cursorAnchorInfo.insertionMarkerHorizontal,
                baseline,
                cursorAnchorInfo.selectionStart,
                line?.left ?: Float.NaN,
                line?.right ?: Float.NaN,
                line?.height()?.times(MAX_CHAR_WIDTH_OF_LINE_HEIGHT) ?: Float.NaN,
            )
        if (walk != 0) moveAlongLine(editor, walk)
    }

    override fun onSpaceDragEnd() {
        cursorDragging = false
        stopKeepingCursorOnLine()
        updateAutoCaps()
    }

    private fun stopKeepingCursorOnLine() {
        if (!cursorKeptOnLine) return
        cursorKeptOnLine = false
        currentInputConnection?.requestCursorUpdates(0)
    }

    // endregion

    // region Clipboard

    /** The history replaces the strip and the keys for as long as it is open. */
    private fun openClipboardPanel() {
        if (!cover.ready) return
        val panel =
            panelView
                ?: ClipboardPanelView(
                    this,
                    KeyboardTheme(this, KeyboardThemes.selected(this).palette(this)),
                    clipStore,
                    clipboardPrefs,
                    promptResults,
                    promptResultsShown = { voicePrefs.promptResultsEnabled },
                ).also {
                    it.listener = this
                    panelView = it
                }
        panel.open()
        cover.show(panel)
    }

    private fun closeClipboardPanel() {
        cover.hide(panelView)
    }

    override fun onClipboardBack() = closeClipboardPanel()

    override fun onClipboardPick(entry: ClipEntry) {
        closeClipboardPanel()
        when (val payload = entry.payload) {
            is ClipPayload.Text -> insertText(payload.text)
            is ClipPayload.Image -> pasteImage(clipStore.imageUri(payload.source), payload.mimeType)
        }
    }

    /** Hands the image to the editor as rich content; fields that take no images get a message. */
    private fun pasteImage(
        uri: Uri,
        mimeType: String,
    ) {
        val info = currentInputEditorInfo
        val connection = currentInputConnection
        if (info == null || connection == null) return
        val accepted = EditorInfoCompat.getContentMimeTypes(info)
        val acceptedType = ClipboardContent.acceptedImageType(mimeType, accepted)
        if (acceptedType == null) {
            showMessage(R.string.paste_image_unsupported)
            return
        }
        editor()?.let { logic.commitComposing(it) }
        logic.reset()
        if (!ClipboardContent.commitImage(connection, info, uri, acceptedType)) showMessage(R.string.paste_image_failed)
    }

    // endregion

    // region Emoji

    /** The emoji picker replaces the strip and the keys for as long as it is open. */
    override fun onEmojiRequested() {
        if (!cover.ready) return
        val panel =
            emojiPanel
                ?: EmojiPanelView(
                    this,
                    KeyboardTheme(this, KeyboardThemes.selected(this).palette(this)),
                    emojiAssets,
                    emojiPrefs,
                ).also {
                    it.listener = this
                    emojiPanel = it
                }
        panel.open(if (language == Language.RUSSIAN) "АБВ" else "ABC", rememberRecents = !profile.incognito)
        cover.show(panel)
    }

    /** Back to the keys: from the picker's own button, and whenever the keyboard starts or stops. */
    override fun onEmojiBack() {
        cover.hide(emojiPanel)
    }

    override fun onEmojiPick(text: String) = insertText(text)

    // endregion

    // region Voice input

    override fun textBeforeDictation(): CharSequence? = editor()?.textBeforeCursor(CONTEXT_LENGTH)

    override fun insertText(text: String) {
        textActions.reset()
        val editor = editor() ?: return
        editor.beginBatchEdit()
        logic.commitComposing(editor)
        logic.reset()
        editor.commitText(text)
        editor.endBatchEdit()
        updateAutoCaps()
    }

    override fun onVoiceState(state: VoiceState) {
        stripView?.voiceState = state
    }

    override fun setKeepScreenOn(keep: Boolean) {
        val window = window?.window ?: return
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun dictationLocale(): Locale = language.locale

    override fun openSettings(requestPermission: Boolean) {
        val intent =
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SettingsActivity.EXTRA_REQUEST_MICROPHONE, requestPermission)
        startActivity(intent)
    }

    /** Runs on the decoder's thread; a crash while loading switches the engine off for the next start. */
    private fun openNeuralEngine() =
        neuralLoadGuard(this, keyboardPrefs) {
            Handler(Looper.getMainLooper()).post { showMessage(R.string.slide_engine_crashed) }
        }.open { SwipeModelAssets.open(this) }

    override fun showMessage(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region Modes

    private fun switchMode(next: KeyboardMode) {
        mode = next
        refreshKeyboard()
        updateAutoCaps()
    }

    private fun switchLanguage() {
        editor()?.let { logic.commitComposing(it) }
        language = language.next()
        logic.locale = language.locale
        prefs().edit { putString(PREF_LANGUAGE, language.code) }
        decoder.load(language) { onDictionaryLoaded() }
        // The symbol pages look the same in both languages, so go back to letters to show the switch.
        mode = KeyboardMode.LETTERS
        refreshKeyboard()
        updateAutoCaps()
    }

    // endregion

    // region Gestures

    override fun onGestureStart() {
        textActions.reset()
        gestureInProgress = true
        gestureSequence++
        preemptivePending = false
        editor()?.let { logic.startGesture(it) }
        gestureContext = textBeforeCursor()
    }

    override fun onGestureUpdate(points: List<TouchPoint>) {
        val now = SystemClock.uptimeMillis()
        if (preemptivePending || now - lastPreemptiveAt < PREEMPTIVE_INTERVAL_MILLIS) return
        preemptivePending = true
        lastPreemptiveAt = now
        val sequence = gestureSequence
        decoder.decode(language, points.toList(), gestureContext, complete = false) { words ->
            preemptivePending = false
            if (sequence == gestureSequence && gestureInProgress) logic.showGesturePreview(words)
        }
    }

    override fun onGestureEnd(points: List<TouchPoint>) {
        val sequence = gestureSequence
        decoder.decode(language, points, gestureContext, complete = true) { words ->
            if (sequence == gestureSequence) {
                gestureInProgress = false
                editor()?.let { logic.commitGesture(it, words) }
                syncShift()
            }
        }
    }

    override fun onGeometryChanged(geometry: KeyboardGeometry) {
        if (geometry.spec.mode != KeyboardMode.LETTERS) return
        val metrics = resources.displayMetrics
        decoder.setLayout(geometry.spec.language, GestureLayoutFactory.create(geometry, metrics.xdpi, metrics.ydpi))
    }

    private fun onDictionaryLoaded() {
        keyboardView?.gestureEnabled = gesturesAvailable()
        logic.refreshCompletions()
    }

    // endregion

    private fun refreshKeyboard() {
        val view = keyboardView ?: return
        view.spec = KeyboardLayouts.spec(language, mode)
        view.enterAction = profile.enterAction
        view.gestureEnabled = gesturesAvailable()
        view.spaceDragMode = keyboardPrefs.spaceDragMode
        decoder.setSlideEngine(keyboardPrefs.slideEngine)
        syncShift()
    }

    private fun syncShift() {
        keyboardView?.shiftState = logic.shift.state
    }

    private fun gesturesAvailable(): Boolean =
        mode == KeyboardMode.LETTERS &&
            keyboardPrefs.slideEnabled &&
            profile.gesturesAllowed &&
            decoder.isReady(language)

    private fun updateAutoCaps() {
        if (mode != KeyboardMode.LETTERS) return
        if (!keyboardPrefs.autoCapitalize) return
        val ic = currentInputConnection ?: return
        val inputType = currentInputEditorInfo?.inputType ?: return
        logic.shift.applyAutoCaps(cursorNeedsCaps(ic, inputType))
        syncShift()
    }

    /**
     * Asks the editor about auto-capitalization only when the answer matters: the call blocks
     * the main thread on the editor's process and selection updates arrive for every key press.
     */
    private fun cursorNeedsCaps(
        ic: InputConnection,
        inputType: Int,
    ): Boolean {
        val capsFlags = inputType and AUTO_CAPS_FLAGS
        if (capsFlags == 0 || logic.shift.state == ShiftState.LOCKED) return false
        // Inside a composed word the cursor follows a letter: no new word or sentence starts here.
        val allCaps = capsFlags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0
        if (logic.isComposing && !allCaps) return false
        return ic.getCursorCapsMode(capsFlags) != 0
    }

    private fun textBeforeCursor(): String = editor()?.textBeforeCursor(CONTEXT_LENGTH)?.toString().orEmpty()

    private fun editor(): ConnectionEditor? = currentInputConnection?.let { ConnectionEditor(it, this) }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private companion object {
        const val PREFS = "keyboard"
        const val PREF_LANGUAGE = "language"
        const val PREEMPTIVE_INTERVAL_MILLIS = 120L
        const val CONTEXT_LENGTH = 64

        /** Even the widest letter is narrower than this part of the height of its line. */
        const val MAX_CHAR_WIDTH_OF_LINE_HEIGHT = 0.8f
        const val AUTO_CAPS_FLAGS =
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
}
