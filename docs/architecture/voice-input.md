# Voice input

## Entry points

Voice lives in `app/voice/` with Android-free value types in `core/voice/`. `KeyboardService` creates
`VoiceInput` and `TextActions` in `onCreate` and implements `VoiceInput.Host`, which is the only way
voice code talks to the editor:

- `textBeforeDictation()` and `dictationLocale()` give context;
- `insertText()` commits text and records it for dictation undo;
- `onVoiceState()` updates the strip;
- `setKeepScreenOn()`, `openSettings()` and `showMessage()` handle UI effects.

The strip calls the service on a microphone tap (`onMicUsed(null)`), on a hold-menu choice
(`onMicUsed(modeId)`), on cancel and on dictation undo. Voice is available only when
`EditorProfile.voiceAllowed` is true, which excludes passwords and non-text fields.

## Dictation flow

`VoiceInput` is main-thread only and drives one dictation at a time:

1. `start(plan)` checks the microphone permission and selects an engine. Remote recognition wins when
   it is configured and ready; otherwise the system recognizer or Govorun is used.
2. The recorder reports recognized phrases. In `AFTER_PAUSE` mode each phrase is inserted when it is
   ready; in `REPEAT_TAP` mode phrases are buffered until the user stops dictation.
3. `VoiceDictionary` transformations are applied to every phrase.
4. If text improvement is enabled, the phrase goes through `EnhancementQueue` and the improved text is
   inserted; otherwise it is inserted directly.
5. `finish()` completes the transcript and, when a queue exists, waits for it in the `ENHANCING`
   state. Prompt results are stored only if the model changed at least one piece.
6. Dictation undo can remove everything inserted while voice was active, as long as the editor text
   still ends with exactly that text.

`VoiceState` phases are `IDLE`, `LISTENING`, `RECOGNIZING` and `ENHANCING`, with optional `speech`,
`preparing` and `preview` details for the strip and the voice bubble. Native engines are released
after two minutes of inactivity because they hold significant memory.

## Recognition engines

### System recognizer

`SystemSpeechRecorder` uses the phone's default `SpeechRecognizer` on the main thread. It restarts
listening after every result, treats timeout and no-match as normal, retries a busy recognizer three
times, and maps other errors to `DictationFailure`. Level animation comes from `onRmsChanged`.

### Govorun

Govorun integration is an AIDL service in another app. The keyboard owns the microphone and streams
16 kHz mono PCM into a pipe; Govorun performs voice activity detection and returns phrases.

- Binding target: package `com.govorun.lite`, action `com.govorun.api.RECOGNITION_SERVICE`.
- Protocol 1 with continuous profiles is required; profile 0 is the default pause, profile 1 is the
  short pause.
- While Govorun loads, up to about one minute of audio is buffered.
- Binder death and runtime exceptions thrown by the other app are logged and turned into
  `DictationFailure.UNAVAILABLE`; they never crash the keyboard.
- The settings activity binds the same service to show installed, authorized and ready states.

### Remote recognition

Remote recognition records locally and sends each speech segment to a compatible
`/audio/transcriptions` endpoint.

- `MicrophoneCapture` opens `AudioRecord` at 16 kHz mono 16-bit and prefers a USB, wired or Bluetooth
  headset over the built-in microphone. It does not change the system audio mode or volume.
- `VadRecorder` runs the bundled Silero VAD over 512-sample windows. It keeps one native VAD instance
  per pause length, handles segment boundaries with `SpeechSegmenter` pre-roll and queues up to eight
  segments.
- `RemoteTranscriber` sends one request per segment through `ServerHttp`, which is a blocking
  `HttpURLConnection` on a daemon thread pool. Responses are capped at 4 MiB; cancellation
  disconnects the request.
- A failed segment is reported once per dictation and recording continues. The recognized text is
  never logged; only sizes and result lengths are.

## Text improvement

`TextEnhancer` is a single non-streaming chat completion: the prompt is the system message, the text
is the user message, and the answer is read from `choices[0].message.content`. `EnhancementQueue`
enhances phrases in dictation order while recording continues. If the server fails, the original
phrase is inserted and the user is told once per dictation.

`EnhancementOutcome` distinguishes success, unchanged text, a failed request, authentication failure
(401/403) and a timeout. Request timeouts are configured per server and default to 30 seconds. Servers
can be HTTP or SOCKS5 via a proxy.

Text features share the same server model:

- Enhancement has its own enable switch, server, threshold and prompts.
- Proofread takes the selection or the whole field, asks the server, compares the text before
  replacing it, and offers a second-tap undo.
- A custom button can run a prompt on the field or the selection.

`core/voice/ServerProfile`, `SelfHostedServer` and `RemoteRecognitionServer` validate addresses,
proxies, keys and timeouts and compute the recipient fingerprint. A stored profile with an accepted
recipient counts as user consent for that address.

## Prompt library and results

`PromptStore` keeps prompts and per-place selections in the `voice` preference file. Built-in prompts
are inserted only on first start. Prompt text is composed when used, so editing a prompt changes every
place that references it; deleting one applies `PromptRemoval` fallback rules to every selection.

`PromptResultStore` keeps the last 50 answers and raw transcripts in
`filesDir/prompt_results/history.json`. Writes are off when the user disables the buffer or when the
field is incognito, but existing results stay readable. Results are shown in the clipboard panel and
inserted as plain text.

`QuickReplyStore` and `CustomButtonStore` use JSON values in the same preference file. Unreadable JSON
is logged and treated as an empty list. `MicButtonStore` defines the tap mode and up to eight hold
modes, each with its own processing and ending.

## Custom buttons and dictation undo

`CustomButtonsControl` and `ButtonActions` implement one active custom button at a time. Commands are
clear, copy, paste, prompt and quick replies; prompt buttons can ask which prompt to use at tap time.
Insertion modes are replace all, replace selection, append and at cursor. A second button pressed
while one is active reports "busy"; a gesture on the active button cancels or undoes it.

`DictationUndoControl` records the text inserted while voice is active and removes exactly that text
when the cursor still stands after it.

## Preferences

All voice settings live in one `voice` preference file through `VoicePrefs`:

- engine, microphone color, voice animation, headset preference, pause, recording mode, keep screen
  on, auto stop;
- servers as a JSON array, default text and audio servers, per-feature enable and server;
- remote recognition settings and its language and prompt;
- dictionary text and enable switch, enhancement threshold, prompt results switch;
- nested JSON for prompts, prompt selections, custom buttons, quick replies and the microphone
  button.

API keys are stored in the same file. The settings backup includes them only with explicit consent.

## Failure handling

- Missing microphone permission opens the settings screen instead of recording.
- An unavailable engine is checked before recording starts and reported through the strip.
- A VAD failure ends the dictation with a message.
- A server failure inserts the original text and reports once.
- Late callbacks after the keyboard is destroyed are dropped: `PromptResultStore` and clipboard
  stores use `DiskQueue`, which turns away work after `close()`.

## Related code

- `app/src/main/kotlin/ru/tinyops/android/vboard/voice/`
- `app/src/main/kotlin/ru/tinyops/android/vboard/voice/engine/`
- `app/src/main/kotlin/ru/tinyops/android/vboard/voice/govorun/`
- `app/src/main/kotlin/ru/tinyops/android/vboard/voice/system/`
- `app/src/main/aidl/com/govorun/api/v1/`
- `core/src/main/kotlin/ru/tinyops/android/vboard/core/voice/`
- `docs/architecture/clipboard-and-sync.md` for how prompt results are shown
