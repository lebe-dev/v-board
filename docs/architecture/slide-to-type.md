# Slide to type

## Pieces

Slide to type has three layers:

1. `:stt` is a standalone decoder library. It knows points, keys, a language model and nothing about
   Android. `SlideToTypeDecoder` is its public interface; `BeamSearchDecoder` is the implementation.
2. `:core/gesture` adapts the library to the keyboard. `GestureEngine` wraps the decoder and converts
   on-screen geometry into the decoder's `KeyboardLayout`. `SwitchableGestureEngine` picks between the
   default decoder and the neural model. `SlideEngine` is the user setting.
3. `:app` owns `GestureDecoder`, which loads dictionaries and word pairs, runs engines on one
   background thread, and exposes decode, completion, correction and prediction calls to the service.

## Default decoder

`BeamSearchDecoder` treats a gesture as a polyline through key centers and finds the vocabulary word
whose polyline best matches it.

- The gesture is downsampled with `GestureDownsampler`. Points closer than a fraction of a key height
  are dropped unless a maximum time gap has passed.
- Every vocabulary prefix is aligned against the points using an incremental DTW column. Extending a
  hypothesis by one character costs O(points), and the working memory lives in `DecodeWorkspace`
  between decodes, so warm decodes allocate little.
- The first character absorbs the leading points, the last character is pinned to the final point,
  and intermediate points are scored against the line between consecutive vertices.
- A Gaussian spatial model, configured relative to key height, turns distances into costs.
- The language model is a unigram frequency model over the same dictionary vocabulary.
- The beam is pruned by prefix cost plus a fixed cost for each point the prefix has not reached.
  Without that term, prefixes of long words would be discarded too early.

`DecodeConfig` holds the tuning parameters: beam width, result counts, maximum word length, spatial
and language weights, sigma, downsampling threshold and the unexplained-point cost. They are defaults
for the library, not per-device values.

Live suggestions during a gesture use the same decoder with fewer results; the final result after
finger lift uses the full settings.

## Layout adaptation

`GestureLayoutFactory.create` converts `KeyboardGeometry`, the same pixel placement the user sees,
into `stt`'s `KeyboardLayout`. It keeps letter keys only and adds aliases for letters without their
own key: Russian `ё` shares the geometry of `е`, and `ъ` shares `ь`. Coordinates stay in view pixels;
the layout also carries `xdpi` and `ydpi`.

The layout is pushed whenever the keyboard is measured or the keyboard page changes. Symbol pages do
not take part in slide to type.

## Switchable engine and the neural model

`SwitchableGestureEngine` owns one default `GestureEngine` and optionally one `NeuralGestureEngine`.
Routing rules:

- Only finished gestures go to the neural model. Live previews always use the default decoder,
  because the model was trained on whole words and is too heavy for every finger movement.
- Only Russian uses the neural model. `SlideEngine.EXPERIMENTAL` means "neural where it applies,
  default elsewhere".
- Anything the neural path cannot serve falls back to the default decoder: no model in the build, a
  language the model does not know, a runtime error, an empty candidate list.

The neural path is built from three parts:

- `SwipeFeatureExtractor` maps the on-screen layout onto the training grid by the centers of the
  corner keys, then produces a trajectory with velocity and acceleration plus the nearest key token
  for every point. Normalization constants come from the model spec.
- `ExecuTorchSwipeModel` runs the exported ExecuTorch program. It has an `encode` method that turns
  the trajectory into memory and a `decode` method that returns logits after each token.
- `NeuralWordSearch` does best-first beam search over dictionary words, renormalizing logits over the
  tokens the dictionary allows after a prefix. The best candidates are decoded a second time without
  that restriction and reordered by full-word probability. Frequency, word pair association and path
  geometry are added as separate terms, visible in `ScoredCandidate`.

`SwipeModelSpec` is read from `ru.properties` next to the model: token tables, class count, special
tokens, training grid anchors and feature normalization. `SwipeModelAssets` opens the model and
copies the `.pte` file out of the APK to `noBackupFilesDir/swipe` once per installed version, because
the runtime needs a file path.

## Loading failures

`NeuralLoadGuard` protects the keyboard from a native crash while the model loads. Before loading it
sets a synchronous marker in `KeyboardPrefs`. After a successful start the marker is cleared. If the
process died while the marker was set, the next start switches the engine back to `DEFAULT`, records
an error through `AppLog` and `ErrorReporting`, and tells the user. Loading and decoding also catch
any `Throwable`, because native failures can arrive as `Error`; `SwitchableGestureEngine` then stops
using the model for this process.

## Calibration and logging

`core/gesture/calibration` and `core/gesture/logging` are side channels around decoding:

- Calibration records finished gestures with the words the user actually meant and can export them
  as a sample archive. It is used from the swipe calibration screen.
- The free typing swipe log records an opt-in sample of everyday gestures with the engine details.
  It only runs outside passwords and incognito fields. `SwipeTypingLog` in `:app` decides when a
  record is kept; `SwipeLogBuffer` and `SwipeLogArchive` in `:core` hold and export records.

Neither path changes the decode result.

## Dictionary and word pair inputs

The default decoder needs a `LanguageModel`, which `Dictionary` exposes over its binary word table.
The neural search needs a `WordTrie` from the same model and optionally a `BigramTable` for context.
`GestureDecoder` owns all of them per language and rebuilds the tap suggester when any input changes.
See [Dictionaries](dictionaries.md).

## Performance

- Decoding runs on the single `gesture-decoder` thread. Nothing on the main thread waits for it.
- `PerfTrace` sections around key presses, view creation, dictionary loading, live decode, final
  decode and neural decode make the cost visible in a trace without changing the code.
- `core/src/test/.../gesture/GestureAllocationTest` guards allocation behaviour of warm decodes.
- `:benchmark` holds the macrobenchmark and baseline profile used for startup and typing cost.

## Tests and evaluation

- `stt/src/test` tests the decoder, language model, trie and spatial model on the JVM.
- `core/src/test/.../gesture` covers layout adaptation, engine switching, calibration, logging and
  the neural scoring parts.
- `just swipe-eval`, `just swipe-baseline` and `just swipe-device-check` run the Python evaluation and
  on-device checks described in `DEV.md` and the project instructions. The Kotlin features and the
  Python evaluation must stay in sync.
