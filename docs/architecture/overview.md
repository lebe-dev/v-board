# Overview

## Modules

The Gradle build has four modules. Dependencies point one way, from the app down to the libraries.

| Module | Type | Responsibility |
| --- | --- | --- |
| `:stt` | Kotlin/JVM, no Android | Slide to type decoder library: beam search, language models, spatial model, gesture models |
| `:core` | Kotlin/JVM, no Android | Keyboard logic that does not need Android: layouts, input rules, dictionaries, gesture engines, clipboard and voice value types |
| `:app` | Android application | IME service, Android views, settings activity, voice runtime, clipboard storage, updates, diagnostics |
| `:benchmark` | Android test module | Macrobenchmark and baseline profile generation for `:app` |

`settings.gradle.kts` includes `:stt`, `:core`, `:app` and `:benchmark` in that order. `:core` exposes
`:stt` as an API dependency, and `:app` depends on `:core`. The JVM modules target Java 17 bytecode,
so the same code can run in unit tests without an emulator.

The split follows testability rather than Android packaging:

- Anything that is a pure function of coordinates, layouts, dictionaries and settings lives in `:core`
  or `:stt`, where JVM tests can call it directly.
- Anything that needs `Context`, `SharedPreferences`, `InputConnection`, audio, Compose or a native
  runtime lives in `:app`.
- Android view code (`:app`, package `ui`) is deliberately thin: it turns touches into callbacks and
  draws state that the logic layer produced.

## Runtime layers

```
Android framework
    |
KeyboardService (IME shell) ------ SettingsActivity (Compose)
    |                                     |
    +-- KeyboardView / Strip / Panels     +-- preference classes
    |
    +-- InputLogic (editing rules)          \
    +-- GestureDecoder (background)          > :core
    |      +-- SwitchableGestureEngine      /
    |             +-- GestureEngine -> :stt
    |             +-- NeuralGestureEngine
    +-- VoiceInput / TextActions
    +-- ClipChipsControl / UnisonControl
    +-- FullscreenControl
```

`KeyboardService` is the composition root of the running keyboard. It creates the long-lived objects in
`onCreate`, builds the input view in `onCreateInputView`, and wires every callback from the views and
from the feature controls to those objects. Feature controls (`VoiceInput`, `ClipboardHistoryStore`,
`UnisonControl`, `FullscreenControl`, `TextActions`) are separate classes that receive narrow
callbacks instead of talking to each other directly.

`SettingsActivity` is a second entry point in the same process and the same APK. It is a Compose
activity that reads and writes the same preference objects as the keyboard. There is no shared
view-model layer: the settings screens are ordinary composables that take values and lambdas.

## Threading

- **Main thread** owns every view, `KeyboardService`, `InputLogic`, the settings UI and most feature
  controls. `InputLogic` is not thread-safe by design and is only called from IME callbacks.
- **Gesture thread** is a single `ExecutorService` owned by `GestureDecoder`, named
  `gesture-decoder`. Dictionary loading, bigram loading, layout updates and all gesture decoding run
  there because `BeamSearchDecoder` keeps reusable working memory and the engines are confined to one
  thread. Results return to the main thread through a `Handler`.
- **Voice** uses its own executors and recorders; recording callbacks arrive on an audio thread and
  are posted back to the main thread before they change the strip or the editor.
- **Background jobs** cover scheduled update checks and fetching a word pair table after an update.
  They are `JobService` implementations declared in the manifest.
- **Startup** is kept cheap. `KeyboardApp` starts error reporting and the swipe log store; the
  keyboard itself loads dictionaries in the background and shows keys immediately.

## What is shared between the keyboard and the settings

`SharedPreferences` files are the contract between the two parts of the app. Each preference class
wraps one file and exposes typed properties, so both the IME and the settings UI see the same values
without an IPC layer. See [Data on the device](data-storage.md) for the file list.

Long-lived user data (clipboard history, prompt results, downloaded word pairs, logs) lives in files
under the app's private directories, not in preferences.

## Error handling principles

- A failure of an optional engine must not take the keyboard down. The neural slide engine catches
  any `Throwable`, reports it and falls back to the default decoder.
- Features that depend on a server or on another app report a short user-facing message through the
  strip or a toast, and record a technical entry in the optional app log.
- The keyboard works offline. Network access is only used for an explicitly configured voice or text
  server, update checks and the word pair download.
