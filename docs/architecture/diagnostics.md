# Diagnostics

Diagnostics are opt-in and local by default. The keyboard does not send anything anywhere unless the
user turns on error reporting, and it never sends typed text, dictation or clipboard contents.

## Startup hooks

`KeyboardApp.onCreate` runs four steps in order:

1. `AppLog.init` connects the error log to `filesDir` and `KeyboardPrefs`.
2. `AppLog.recordCrashes` installs an uncaught exception handler that writes the crash to the log
   before delegating to the previous handler.
3. `SwipeTypingLog.init` prepares the swipe log ring buffer.
4. `ErrorReporting.sync` starts or stops the reporting SDK according to the DSN and the user switch.

## App error log

`AppLog` writes JSON lines to `filesDir/app-error-log.jsonl`. The log is off by default
(`KeyboardPrefs.logErrors`). `AppLog.error` always writes logcat; it writes the file only while the
switch is on.

`ErrorLogStore` keeps at most 500 entries for 7 days, truncates stack traces to 8000 characters and
skips malformed lines. `LogReport` turns entries into a text file with the app version, build hash,
Android version, device model, ABIs and slide engine, which the user can copy, share or save from the
settings screen. `Problems.report` is the path for survived non-exception problems: it writes the
same log and, when reporting is on, sends a synthetic `IllegalStateException`.

## Error reporting

`BuildConfig.SENTRY_DSN` is the only way to enable the SDK. When it is empty, `ErrorReporting` is
unavailable and the settings screen does not show the switch. When it is set, the user still has to
turn on `KeyboardPrefs.sendErrors`.

The SDK is configured conservatively:

- breadcrumbs, screenshots, view hierarchy, thread dumps, session tracking, client reports,
  interaction, lifecycle and network breadcrumbs and tracing are off;
- `beforeSend` runs `ErrorScrubber`;
- the manifest disables automatic initialization.

`ErrorScrubber` drops the message, user, request, breadcrumbs, tags, extras, server name, logger,
device name, exception values and per-frame variables and context lines. What remains is the exception
type, stack frame locations and the environment. `ReportEnvironment` provides a coarse device slug.

The release name is `appId@version+gitHash`. The R8 mapping of a release is saved next to the APK and
`just retrace` decodes a stack trace with it.

## Exit history and the neural model guard

`ExitHistory` reads the system's historical process exit reasons and keeps the newest three abnormal
ones (crash, native crash, ANR, signal, low memory and similar). Nothing is stored.

`NeuralLoadGuard` uses `KeyboardPrefs.neuralModelLoading` as a crash marker. The marker is written
with `commit` so that it survives an immediate native death. If it is still set on the next start,
the previous process died while loading the model: the guard logs the recent exits, reports an error,
switches `slideEngine` back to `DEFAULT` and tells the user.

## Free typing swipe log

The swipe log is a diagnostic for the experimental engine, not telemetry.

- It is off by default and turns itself off after 4 hours; the deadline is stored in
  `KeyboardPrefs.swipeLogUntilMillis`.
- It only records finished gestures in text fields, never in passwords, never in incognito fields and
  never while calibration is running. The gate is `EditorProfile.swipeLogAllowed`.
- It keeps at most 500 entries in `noBackupFilesDir/swipe-typing-log.jsonl`.
- An entry contains the points, the keyboard layout, the previous word, the engine, candidates with
  their score breakdown, the inserted text, the outcome and the final word.

`SwipeLogTracker` follows the outcome of a swipe: kept, replaced by a picked suggestion, deleted, or
retyped. `SwipeLogStore` writes JSON lines atomically; `SwipeLogArchive` exports a zip compatible with
the evaluation scripts (`valid.ref`, `valid.jsonl`, `meta.json`). The user exports it explicitly from
the settings screen; it is written to `cacheDir/exports` and shared through the app's FileProvider.

The file never reaches `AppLog` or an error report. It leaves the device only when the user shares or
saves it.

## Swipe calibration

`SwipeCalibration` is an in-memory session that asks the user to swipe a fixed list of Russian words.
Only Russian gestures are recorded and only while the calibration field is focused. It records points,
layout, candidates and engine per word, then scores top-1 and any-candidate hits with `CalibrationScore`.
The result can be exported as a `SwipeSampleArchive`, the same format the evaluation tooling reads.
Layouts are registered by `GestureDecoder.setLayout`.

## Performance traces

`PerfTrace` wraps `android.os.Trace` sections with stable names:

- `kbd.dict.load`, `kbd.decode.live`, `kbd.decode.final`, `kbd.decode.neural`;
- `kbd.key`, `kbd.draw`, `kbd.createView`, `kbd.startInput`.

`:benchmark` consumes these names, so a trace section added to a hot path is visible in both
macrobenchmarks and system traces.

## Where the switches live

The diagnostics page shows the reporting switch only when reporting is available and links to the app
log, swipe log and calibration. The app log switch lives on its own screen. All switches are stored in
`keyboard_prefs`.
