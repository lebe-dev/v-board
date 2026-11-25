# Architecture

V-Board is an Android input method editor (IME) with two languages, slide to type, voice input,
clipboard history and a large settings surface. The documents below describe how the current code is
put together. The source code stays the primary reference: when a document and the code disagree, the
code is right.

## Reading order

| Document | What it covers |
| --- | --- |
| [Overview](overview.md) | Modules, dependency direction, runtime layers and threading |
| [Keyboard service](keyboard-service.md) | IME lifecycle, input view, editor profile, keys and panels |
| [Editing and suggestions](editing-and-suggestions.md) | Composing text, tap typing, autocorrect, delete modes, cursor movement |
| [Slide to type](slide-to-type.md) | Default beam search decoder, neural engine, layouts and scoring |
| [Dictionaries](dictionaries.md) | Binary dictionary images, word pair tables and their delivery |
| [Voice input](voice-input.md) | Recognition engines, recording, text enhancement, prompts and custom buttons |
| [Clipboard and Unison](clipboard-and-sync.md) | Clipboard history, chips and synchronization with other devices |
| [Emoji and theming](emoji-and-theming.md) | Emoji catalog, panels and the keyboard theme model |
| [Settings](settings.md) | Settings host, screens, preferences and backup |
| [Diagnostics](diagnostics.md) | Logs, error reports, swipe logs, performance traces |
| [Updates](updates.md) | Release check, download, verification and installation |
| [Build and release](build-and-release.md) | Gradle modules, assets, signing, Justfile workflows |
| [Data on the device](data-storage.md) | Preferences, files and directories the app owns |

## Terms

- **Composing text** - the word the keyboard currently owns in the editor. An editor may replace it,
  which is how suggestions and swipes are applied.
- **Swipe** - a slide to type gesture, from touch down to finger lift.
- **Strip** - the row above the keys with suggestions, the microphone, the clipboard button and
  custom buttons.
- **Field** - the editor the keyboard is attached to, described by `EditorInfo` and `EditorProfile`.
- **Incognito field** - a field with `IME_FLAG_NO_PERSONALIZED_LEARNING`: nothing typed there is kept
  on the device and no clipboard sync leaves it.
