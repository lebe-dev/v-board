# Settings

## Host and navigation

`SettingsActivity` is the only settings activity. It is a `ComponentActivity` with Compose content,
declared as a launcher activity with `singleTask` and `adjustResize`. It is opened from the keyboard
cog, from the launcher and from the system keyboard settings.

There is no navigation library. `Screen` is an enum that describes every page and its parent.
`SettingsNavigation` keeps the current screen and the return targets of the two pages that can be
opened from several places (servers and custom buttons) in `rememberSaveable`, and animates between
pages with Material shared-axis transitions. `BackHandler` is installed only for nested pages.

The activity also owns the parts that do not fit a single screen:

- microphone permission state and the recognition status of the system and Govorun engines;
- the selected keyboard theme for the settings chrome;
- `UpdateSchedule.sync`, called on create, so an imported backup immediately changes the schedule;
- `VoiceSettingsState`, a Compose-observable wrapper around `VoicePrefs` used by voice and text
  screens.

## Screens

| Area | Screens |
| --- | --- |
| Typing | Keys, suggestions, autocorrect, delete key, slide engine, dictionary, emoji |
| Appearance | Themes, emoji groups and single emoji |
| Voice | Voice input, microphone button, animation, prompts, servers, remote recognition |
| Text features | Dictation feature, proofread feature |
| Interaction | Clipboard, fullscreen, custom buttons, quick replies, integrations |
| Application | Language, backup, diagnostics, updates, GitHub link, version |

`MainSettingsScreen` lists the sections and supports hiding rows. The hidden ids are stored in
`KeyboardPrefs.hiddenSettings`; the "Application" section cannot be hidden. The edit mode is local to
the screen.

Each settings page is an ordinary composable that reads a preference object once and writes changes
immediately. `SharedPreferences` is shared in-process between the settings and the keyboard, so the
keyboard sees a change the next time it starts a field. `VoiceSettingsState` adds a version counter so
that all voice screens recompose after any update.

Draft editors (server form, prompt editor, quick reply, microphone mode, custom button name) keep a
local draft and save on confirmation. The custom dictionary screen keeps a text buffer with an
explicit save action.

## Preference layer

Each feature has a wrapper class with typed properties. Files and ownership:

| File | Owner |
| --- | --- |
| `keyboard_prefs` | `KeyboardPrefs` |
| `keyboard` | `KeyboardThemes`, keyboard language |
| `voice` | `VoicePrefs`, nested prompt, button, reply and mic-mode JSON |
| `emoji_prefs` | `EmojiPrefs` |
| `clipboard_prefs` | `ClipboardPrefs` |
| `updates` | `UpdatePrefs` |
| `unison_prefs` | `UnisonPrefs` |

Most writes use `apply`; the neural model load marker and backup import use `commit` because they must
survive a process death.

`ParseCache` caches parsed JSON for the larger structures (servers, prompts, custom buttons, quick
replies, mic button) by raw string, so screens and the keyboard do not reparse them on every read.

## Settings backup

`SettingsBackup` defines the file format, `BackupActions` runs export and import.

Format: JSON with `format: "vboard-settings"`, `version`, `app_version`, `exported_at`,
`app_language`, `api_keys` and `preferences`. `preferences` maps a preference file to keys, and every
value carries its type (`boolean`, `int`, `long`, `float`, `string`, `string_set`). Decoding validates
the type of every value and rejects unknown formats, unsupported versions, malformed JSON and files
over 1 MiB.

Which keys travel:

- All keys of the seven preference files by default.
- Device-specific keys are excluded: the neural loading marker and the local error log switch, recent
  emoji, update check state and Unison last-clip time.
- API keys of voice servers only when the user agrees. A backup without keys keeps the keys already
  stored for servers with the same id on import; the legacy flat key entries are dropped.

Import replaces every known file that the backup carries and leaves other files alone. It commits
synchronously, then applies the app language and recreates the activity if needed.

Export uses the Storage Access Framework with a default name `vboard-settings-yyyy-MM-dd_HH-mm-ss.json`;
import accepts JSON and generic octet-stream and caps the read at one MiB plus one byte.

## Application language

The app language follows the system by default and can be set to English or Russian through
`LocaleManager.applicationLocales`. `res/xml/locales.xml` declares both languages for the system
picker. The backup carries the choice, but importing a language recreates the activity.

## Related code

- `app/src/main/kotlin/ru/tinyops/android/vboard/settings/SettingsActivity.kt`
- `app/src/main/kotlin/ru/tinyops/android/vboard/settings/SettingsPage.kt`
- `app/src/main/kotlin/ru/tinyops/android/vboard/settings/SettingsComponents.kt`
- `app/src/main/kotlin/ru/tinyops/android/vboard/settings/SettingsBackup.kt`
- `app/src/main/kotlin/ru/tinyops/android/vboard/settings/BackupActions.kt`
