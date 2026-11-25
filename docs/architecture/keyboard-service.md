# Keyboard service

## Lifecycle

`KeyboardService` extends `InputMethodService`. It is declared in `AndroidManifest.xml` with
`BIND_INPUT_METHOD` and the `android.view.InputMethod` intent filter. `res/xml/method.xml` declares
the subtypes: Russian and English.

`onCreate` builds the object graph once per service instance:

1. `KeyboardPrefs`, `GestureDecoder`, and an `InputLogic` whose callbacks come from the decoder and
   the strip.
2. `PromptResultStore`, `VoiceInput`, `ProofreadControl`, `PromptPickerControl`,
   `CustomButtonsControl`, `DictationUndoControl`, `TextActions` and `FullscreenControl`.
3. Clipboard and emoji objects, then `startClipboard`, which creates `ClipboardHistoryStore`,
   `UnisonControl` and `ClipChipsControl`.
4. It restores the active language and asks `GestureDecoder` to load that language and the other one.
   Loading is asynchronous.

`onCreateInputView` builds a `LinearLayout` with two children: `SuggestionStripView` and
`KeyboardView`. It also applies the selected theme and window insets. The strip sits above the keys and
holds suggestions, the microphone, the clipboard icon, the fullscreen icon, custom buttons, the undo
peek and the error notice.

`onStartInputView` reacts to the new field. It builds an `EditorProfile` from `EditorInfo`, resets
`InputLogic`, applies settings that could have changed in the settings activity, closes transient
panels, refreshes the keyboard layout and runs auto-capitalization. If the theme changed, the input
view is rebuilt.

`onFinishInputView` commits the composing word, drops transient state, closes panels, tells voice and
text actions that the field is gone and leaves fullscreen.

`onDestroy` tears the objects down in dependency order: Unison and clipboard first, then text actions
and voice, then the decoder thread, then the stores that may still be writing files.

## Editor profile

`EditorProfile` is the single place that turns `EditorInfo` into keyboard decisions:

| Flag | Meaning |
| --- | --- |
| `initialMode` | Numeric fields start on the symbols page |
| `suggestionsAllowed` | Text, not a password, and not `TYPE_TEXT_FLAG_NO_SUGGESTIONS` unless `suggestAlways` is on |
| `gesturesAllowed` | Text and not a password; voice input uses the same flag |
| `enterAction` | Icon of the enter key, from `imeOptions` |
| `multiLine` | Enables vertical cursor movement on the space bar |
| `fullscreenAllowed` | Text and not a password |
| `incognito` | `IME_FLAG_NO_PERSONALIZED_LEARNING`: no swipe log, no clipboard sync, no learning |
| `clipboardSyncAllowed` | Not a password and not incognito |

The profile is recomputed for every field. Nothing about a previous field leaks into the next one.

## Input view structure

`KeyboardView` is a custom `View` in `ui/`. It does not use child views per key.

- `spec` is a `KeyboardSpec` from `KeyboardLayouts`: rows of `KeySpec` with width units.
- On size change it builds a `KeyboardGeometry` that places every key in pixels. The geometry is
  reported to the service through `onGeometryChanged`, and the service passes it to the decoder so
  gesture coordinates and key centers match what the user sees.
- `KeyRenderer` paints the keys with the current `KeyboardTheme`.
- Touches are resolved with a hit box that includes the gaps, so every point inside the keyboard maps
  to one key.

The touch model:

- A character key fires on release.
- Backspace fires on press and repeats while held. `KeyboardView` sends `DeleteHoldMode.CHARACTERS`
  for a press and the configured word or all-text mode after the hold threshold.
- Pressing a second key releases the first immediately, which supports fast two-thumb typing.
- A finger that starts on a letter and travels more than half a key becomes a gesture. Points are
  reported in view coordinates.
- A finger on the space bar reports drag steps. The service turns them into cursor movement, or into
  line movement in multiline fields.

`KeyboardView.Listener` is the contract between the view and `KeyboardService`: keys, delete steps,
alternates, emoji, shift and language long presses, gesture start/update/end, space drag and geometry
changes.

## Suggestion strip

`SuggestionStripView` is a custom view that contains several independent areas: suggestion chips,
the microphone with its mode popup, clipboard and fullscreen buttons, custom buttons, proofread and
dictation undo buttons, the voice bubble and the error notice. The service pushes state into it with
plain properties (`voiceState`, `customButtons`, `proofreadState`, colors, placements) and receives
`SuggestionStripView.Listener` callbacks for taps and long presses.

Suggestions come from `InputLogic`. The strip does not know how they were produced. It also owns the
suggestion timeout, after which the chips disappear.

## Panels

`KeysCover` swaps the strip and keys for a full-height panel and back. The input view keeps one
instance of each panel:

- `ClipboardPanelView` shows clipboard history, prompt results and the pause button of Unison sync.
- `EmojiPanelView` shows the emoji catalog with recents, pins and categories.
- `PromptPickerPanelView` is a temporary chooser used by a custom button that asks for a prompt.

Only one cover is visible at a time. Opening a panel closes the others; the keyboard start and end
also close them.

## Fullscreen mode

Fullscreen mode is not the system extract view. It starts only from a user tap on the strip and is
implemented by `FullscreenControl` plus `FullscreenEditorView`, which takes the screen area above the
keys and edits the same `InputConnection`. The service routes `onCreateExtractTextView` and
`onEvaluateFullscreenMode` to this control. Entering fullscreen commits the word being composed,
because actions on the whole text must not split it.

## Keys and modes

`handleKey` is a `when` over `KeyType`:

- `CHARACTER`, `SPACE`, `ENTER` go to `InputLogic`.
- `SHIFT` goes to `ShiftController`.
- `SYMBOLS`, `MORE_SYMBOLS`, `NUMBERS`, `ALPHABET` change the page through `KeyboardLayouts.spec`.
- `LANGUAGE` switches between Russian and English.
- `BACKSPACE` goes to the delete pipeline.

Each key press is wrapped in `PerfTrace.section`, so tracing can measure the cost of a key without
changing the code path. Diagnostics hooks around text keys, deletes and suggestion picks feed the
optional swipe log.

## Gestures

The gesture flow is deliberately split between the main thread and the decoder thread:

1. `onGestureStart` commits any composing word, increments a sequence number and reads the text before
   the cursor once. The text cannot change while the finger is down, so later steps reuse it.
2. `onGestureUpdate` throttles live previews to roughly one request per 120 ms. A stale result is
   dropped by comparing the sequence number.
3. `onGestureEnd` asks for the final decode. The result is applied to `InputLogic`, which inserts the
   best word as composing text and offers the rest as alternatives.
4. `GestureDecoder.applyGeometry` gives the decoder the current layout when the keyboard is in the
   letters mode. Symbol pages do not take part in slide to type.

`gesturesAvailable` combines the page, the setting, the field and the loaded dictionary, so the swipe
entry simply disappears when any of them is missing.

## Cursor movement

A horizontal drag on the space bar moves the cursor by character steps. In multiline fields a
vertical drag moves by lines. Two modes exist:

- `LINEAR` movement sends arrow key events through `CursorMotion` and passes from line to line.
- `TRACKPAD` mode requests cursor updates from the editor, moves in any direction and keeps the
  cursor on the visual line. `CursorLines` tracks visible lines and `VisualLineGuard` limits
  horizontal steps to the line edges. This mode requires the editor to answer `requestCursorUpdates`;
  without an answer it degrades to linear movement. Trackpad is the default.

During cursor movement `onUpdateSelection` is suppressed from running correction checks, because the
cursor passes over many words in a short time.

## Clipboard, emoji and voice entry points

The service implements the listeners of the clipboard panel, the emoji panel and `VoiceInput.Host`.
That keeps the views decoupled from storage and networking:

- Clipboard picks become text or an image pasted through `ClipboardContent`.
- Emoji picks are inserted as text, with recents disabled in incognito fields.
- Voice results and text actions go through `insertText`, which ends composing text, commits the text
  in one batch edit and offers it to the dictation undo buffer.

## Messages

`showMessage` chooses the surface: enhancement and server errors that belong to a voice or text
action appear in the strip's error notice, everything else is a toast. The distinction matters in
fullscreen and panel modes where a toast is the only visible element.

## Related code

- `app/src/main/kotlin/ru/tinyops/android/vboard/KeyboardService.kt`
- `app/src/main/kotlin/ru/tinyops/android/vboard/EditorProfile.kt`
- `app/src/main/kotlin/ru/tinyops/android/vboard/ui/KeyboardView.kt`
- `app/src/main/kotlin/ru/tinyops/android/vboard/ui/SuggestionStripView.kt`
- `app/src/main/kotlin/ru/tinyops/android/vboard/KeysCover.kt`
- `app/src/main/kotlin/ru/tinyops/android/vboard/FullscreenControl.kt`
