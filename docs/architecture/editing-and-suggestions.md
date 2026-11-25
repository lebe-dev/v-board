# Editing and suggestions

## Where the rules live

`InputLogic` in `:core` contains all editing rules. It talks to the editor through the `TextEditor`
interface, which `KeyboardService` implements over `InputConnection` in `ConnectionEditor`. This keeps
composing, autocorrect, delete modes and suggestion application testable on the JVM.

`InputLogic` receives callbacks for its data:

- `completions(prefix, precedingText)` - words for the prefix being typed.
- `corrections(word)` - better spellings of a committed word.
- `predictions(precedingText)` - words that may follow the text before the cursor.
- `tapSuggestions(typed, taps, precedingText)` - the full tap suggestion result, including a confident
  autocorrection.
- `onSuggestions(words)` - what the strip should display.
- `onAutocorrectHighlight(enabled)` - whether the word being typed is shown as about to be corrected.

`KeyboardService` wires all of them to `GestureDecoder`. The decoder owns dictionaries and the
suggester, so `InputLogic` remains free of assets and threading.

## Composing text

The word being typed is kept as composing text in the editor and mirrored in a `StringBuilder`. The
mirror is what suggestions replace and what the strip sees. Rules:

- A letter typed while `suggestionsAllowed` is false is committed immediately; there is no composing
  region and no suggestions.
- Space, enter, punctuation, a cursor move or picking a suggestion commits the composing word.
- Deleting into the end of a committed word or typing at its end (when no word character follows the
  cursor) pulls that word back into the composing region so completions, corrections and replacement
  apply to the whole word rather than only the edited suffix.
- After a swiped word, a composing region stays so that alternatives can replace it. The next typed
  letter commits it first.
- A swipe after a word without a trailing space inserts a "phantom space". The same happens before the
  first typed letter after a swipe. Punctuation never gets the phantom space.
- Backspace on a swiped word deletes the whole word, not one character.
- Text before the composing word is captured once, when the word starts, and used as context for
  completions.

`ConnectionEditor` wraps `InputConnection` and adds two things: it batches edits where the logic asks
for it, and it implements `replaceAroundCursor` for corrections and word deletion.

## Tap suggestions and autocorrect

`TapSuggester` in `:core/dict` builds the list for the word being typed:

- Exact prefix completions, ranked by frequency and optionally by word pair context.
- `е`/`ё` variants for Russian.
- One-edit corrections, optionally weighted by the exact touch points on the keys.
- Fuzzy prefix completions when the typed prefix itself contains a typo.
- Two-edit corrections for longer words.

The result is a `TapSuggestions`: a list of lowercase words and an optional autocorrection. The
options come from `TapSuggestOptions`, which reflects the settings: corrections, fuzzy prefix, two
edits and the spatial tap model.

`InputLogic` shows the primary candidate first and the literal typed text second, then the rest. If a
confident autocorrection exists and autocorrect is enabled, it takes the primary slot and the word is
shown with a highlight. On space or on `, . ! ? ; :` the pending correction is applied instead of
committing the typed spelling. One backspace immediately after an applied correction restores the
original spelling. A completed valid word is not echoed back on the strip when there is no pending
autocorrection. When `trimSpaceBeforePunctuation` is enabled and `suggestionsAllowed` is true, typing
`, . ! ? ; : …` removes accidental spaces before the cursor when preceded by a word or closing mark.

When the cursor is not composing, `showCorrections` looks at the word under the cursor. If that word
is not in the dictionary, its corrections replace the strip; picking one replaces the word in place,
including the part after the cursor. When no completions, cursor corrections or next-word predictions
are active and the current input contains a single misspelled word with corrections (`SingleInputCorrection`),
those corrections are offered on the strip without requiring the cursor to move onto that word.

## Next-word predictions

When the cursor stands after a space and no composing word exists, `SuggestionStrip` offers the words
that usually follow the previous word. The source is the word pair table; without the table there are
no predictions. Picking a next word inserts it plus a space and immediately offers the words after it.
`offersNextWords` marks this mode so that a pick inserts rather than replaces.

Predictions are separate from completions and are controlled by their own setting.

## Delete modes

The delete key has four modes, stored as `DeleteHoldMode`:

| Mode | Behaviour |
| --- | --- |
| `CHARACTERS` | The default: one character per step, repeating while held |
| `WORDS` | Deletes the previous word with the spaces after it; a word being composed goes as a whole |
| `ALL` | Clears the field around the cursor in one step |
| `NONE` | A press deletes one character, holding does nothing more |

The view decides when a press becomes a hold and sends the step to `InputLogic.deleteBy`. The word
mode uses `TextRules.wordBeforeLength`, which treats punctuation runs as words, keeps hyphenated and
apostrophized words together and deletes a line break alone. The all mode finishes composing text
first, then replaces everything around the cursor up to large lookaround limits.

## Shift and capitalization

`ShiftController` is a small state machine with `OFF`, `ONCE` and `LOCKED`:

- A tap toggles one-shot shift.
- A second tap within 350 ms or a long press locks it.
- Typing a letter consumes one-shot shift.
- Auto-capitalization asks the editor with `getCursorCapsMode` and applies one-shot shift without
  overriding caps lock.

`TextRules.applyShift` and `matchCase` carry the state into suggested and swiped words. Russian
stressed vowels are also handled by the layout alternates, not by the input logic.

## Cursor movement

`CursorMotion` and `CursorLines` in `:core/input` implement character and line steps over an editor.
`VisualLineGuard` limits horizontal movement to the current visual line when the editor reports line
bounds. See [Keyboard service](keyboard-service.md) for the two space-drag modes and their fallback.

## Suggestion strip behaviour

`SuggestionStripView` only renders what `InputLogic` gives it. Notable rules:

- Up to three suggestions are shown. When all fit in three equal slots, the primary suggestion stands
  in the middle in bold with the second on the left and the third on the right. When long words do not
  fit in three slots, only the first two are shown side by side (primary on the right). If a word still
  overflows its slot and the shown words share a long prefix, the beginning is ellipsized instead of
  the end so differing endings stay readable.
- Chips disappear after the configured suggestion timeout.
- Swipe previews during a live gesture use the same strip, so a user sees candidates before lifting
  the finger.
- In incognito fields suggestions still work locally; only learning and logging are disabled.
- The strip also hosts unrelated controls (microphone, clipboard, custom buttons), so suggestion
  state is one of several independent inputs to the view.

## Tests worth knowing

Unit tests over `InputLogic` and its helpers live in `core/src/test/.../core/input` and cover
composing, autocorrect undo, delete modes, next words and cursor rules. Tests over the suggester live
in `core/src/test/.../core/dict`. They run with `just test` without a device.
