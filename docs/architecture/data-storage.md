# Data on the device

Everything the keyboard stores is in the app's private storage. Android auto backup is off
(`allowBackup="false"`) and the extraction rules exclude both backups and device transfer. The app also
has its own JSON settings backup, which is explicit and user-driven.

## Preferences

| File | Owner | What it holds |
| --- | --- | --- |
| `keyboard_prefs` | `KeyboardPrefs` | typing, suggestions, autocorrect, delete key, slide engine, fullscreen, strip, hidden settings and diagnostic switches |
| `keyboard` | `KeyboardThemes`, `KeyboardService` | theme choice and app language of the keyboard |
| `voice` | `VoicePrefs` | voice engine and settings, servers, remote recognition, dictionary, enhancement, prompt library, custom buttons, quick replies, microphone modes |
| `emoji_prefs` | `EmojiPrefs` | enabled, recents, pins and hidden emoji |
| `clipboard_prefs` | `ClipboardPrefs` | clipboard switches, icon color and placement |
| `updates` | `UpdatePrefs` | background check, interval, beta channel, last release and last check |
| `unison_prefs` | `UnisonPrefs` | enabled and the last clip time |

All are `MODE_PRIVATE`. Parsed JSON values are cached by `ParseCache`; the format is private and
versioned only where a migration is needed. File names and backup format are a compatibility
contract: the settings backup uses them verbatim.

`SettingsBackup` covers all seven files, excluding device-local keys: the neural load marker and log
switch, recent emoji, update state and Unison last clip time. API keys travel only with consent.

## Files

| Path | Written by | Purpose |
| --- | --- | --- |
| `filesDir/app-error-log.jsonl` | `ErrorLogStore` | opt-in error log, 7 days, 500 entries |
| `filesDir/clipboard/history.json` | `ClipboardHistoryStore` | clipboard index |
| `filesDir/clipboard/images/` | `ClipboardHistoryStore` | copies of clipboard images |
| `filesDir/prompt_results/history.json` | `PromptResultStore` | last 50 prompt answers and transcripts |
| `filesDir/models/vad/silero_vad-<install>.onnx` | `VadRecorder` | VAD model copied out of the APK |
| `noBackupFilesDir/swipe-typing-log.jsonl` | `SwipeLogStore` | opt-in swipe log, 500 entries |
| `noBackupFilesDir/word-pairs/` | `WordPairs` | downloaded word pair tables |
| `noBackupFilesDir/swipe/` | `SwipeModelAssets` | swipe model copied out of the APK |
| `cacheDir/unison/` | `UnisonControl` | remote clipboard images, 24 hours |
| `cacheDir/updates/` | `UpdateDownload` | downloaded APK |
| `cacheDir/exports/` | export screens | temporary share files |

`noBackupFilesDir` is used for data that can be recreated or downloaded and must never be restored on
another device. The VAD and swipe models are copied out of the APK by `AssetFiles.extract` and named
after the install time, so an update always brings its own copy; older copies are deleted.

Every JSON file is written atomically (`AtomicFile` or a temporary file and rename). Disk work runs on
dedicated executors, and stores close through `DiskQueue`, which rejects late work after shutdown.

## Exports

- Settings export goes through the Storage Access Framework; the file is chosen by the user.
- The app log can be copied, shared or saved as `vboard-log-<timestamp>.txt`.
- The swipe log and calibration export a zip to `cacheDir/exports` and share it through the FileProvider.

The FileProvider authority is `${applicationId}.clips`. Its paths are limited to clipboard images,
Unison images and exports.

## What is never stored

- Typed or dictated text in any log or report.
- Clipboard contents in logs.
- Text and clipboard data in error reports; `ErrorScrubber` removes messages and exception values.
- Anything typed in a password or incognito field. Clipboard sync is also blocked there, and prompt
  results are not written.
- Swipe samples from passwords or incognito fields.

## Lifecycle

- The settings activity and the keyboard are in the same process and share the preference objects.
- The keyboard recreates its input view when the theme changes and re-reads settings at field start.
- The diagnostic stores start with `KeyboardApp`; the clipboard and prompt result stores load their
  index in the background at keyboard start.
- Nothing in the app assumes that its process stays alive between fields.
