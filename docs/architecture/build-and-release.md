# Build and release

## Gradle layout

The root `build.gradle.kts` applies the shared ktlint and detekt configuration to every subproject.
The version catalog is `gradle/libs.versions.toml`; versions are pinned there, with the notable
exception of Docker image digests in the Justfile.

`:app` configuration that matters for the architecture:

- `applicationId` and namespace `ru.tinyops.android.vboard`;
- `minSdk 34`, `targetSdk 36`, `compileSdk 36`;
- Java 17 bytecode and a Java 17 target for Kotlin;
- AIDL, Compose and `BuildConfig` generation are enabled;
- only `arm64-v8a` is packaged, because the bundled speech runtime is built for phones;
- `noCompress` covers `onnx`, `pte`, `dict` and `bigrams`, so dense models are not unpacked on every
  start and dictionaries can be memory-mapped from the APK.

Build types:

- `debug` is the development build. With `-PswipeCheck=true` it gets the `.swipecheck` application id
  suffix, so a connected test package cannot uninstall the user's keyboard.
- `release` enables R8 and resource shrinking and uses the release signing config when one is
  configured. A release build without a key fails at `checkReleaseSigning` rather than producing an
  unsigned APK.

Generated values in `BuildConfig`:

- `GIT_HASH` - short commit hash, shown on the settings page;
- `SENTRY_DSN` - from the environment or `.env`;
- `WORD_PAIRS_URL`, `WORD_PAIRS_SHA256`, `WORD_PAIRS_SIZE` - only in release builds, computed from the
  word pair table present at build time.

## Native and downloaded pieces

Several runtime inputs are not in the repository:

| Input | Source | Where it lands |
| --- | --- | --- |
| sherpa-onnx AAR | `just voice-runtime` | `app/libs/sherpa-onnx.aar` |
| Silero VAD model | `just voice-models` | `app/src/main/assets/voice/silero_vad.onnx` |
| Emoji pictures | `just emoji` | `app/src/main/assets/emoji/img/` |
| Swipe model | `just swipe-install <bundle>` | `app/src/main/assets/swipe/` |
| Word pair table | `just bigrams` | `build/bigrams/assets/dict/ru.bigrams` (debug assets, release dist) |

Emoji catalog metadata is committed; only the images are fetched. The swipe model is not committed
because its licence is unsettled. The voice runtime is required to compile `:app` at all, which is
why `test`, `lint`, `build` and `release` depend on the voice recipes.

## Assets

`copyDictionaryImages` puts the `:core` binary dictionaries into the app's generated assets.
`FileFingerprint` reads the word pair table at every build and produces the hash and size used by
release builds. The `:core` `dictionaryImages` task compiles `core/src/main/dict/*.tsv` into images
once per source change.

## Signing and error reporting

`.env` (loaded by the Justfile and read by Gradle) is the single place for build-time secrets. The
sample is `.env.example`.

- `RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD` configure signing. `just release-key` creates a 4096-bit RSA key valid for
  10000 days outside the repository.
- `SENTRY_DSN` turns on error reporting. Without it the Sentry SDK is not initialized and the
  settings screen hides the option.
- `SENTRY_URL`, `SENTRY_ORG`, `SENTRY_PROJECT`, `SENTRY_AUTH_TOKEN` enable R8 mapping upload. All four
  are required. The plugin is configured to upload only that mapping: no traces, no dependency list,
  no source context, no telemetry.

`just retrace <mapping> <trace>` decodes a release stack trace with the mapping saved next to the APK.

## R8

`app/proguard-rules.pro` keeps three groups:

- `com.k2fsa.sherpa.onnx.**`, called from native code;
- `com.facebook.jni.**` and `@DoNotStrip` members, the JNI layer of ExecuTorch;
- a `-dontwarn` for optional javax annotations.

`just release` checks that the fbjni classes survived R8 and stops otherwise. A release without those
rules aborts the process on the first native model error.

## Checks

- `just test` runs `:stt:test`, `:core:test` and `:app:testDebugUnitTest`.
- `just lint` runs ktlint, detekt for every module (including type-resolution detekt for `:app` debug
  and `:core` main/test), and Android Lint with the Compose checks. Lint treats warnings as errors.
- `just security` runs OSV-Scanner and Trivy over resolved runtime dependencies, Semgrep with the
  project rules in `.semgrep/`, and Gitleaks over the tree. Images are pinned by digest.
- `just release` also runs `:app:lintRelease`.

## Justfile workflows

| Recipe | What it does |
| --- | --- |
| `just build` | Fetch voice assets and emoji if needed, build the debug APK |
| `just deploy` | Build, install on the USB phone and select the IME |
| `just release` | Security scans, voice and emoji, release lint and APK, word pair `dist/ru.bigrams.gz`, R8 mapping |
| `just deploy-release` | Install the release APK, install its profile and compile it |
| `just release-key` | Create a signing key |
| `just profile` | Regenerate the baseline profile on a phone |
| `just bench` | Run macrobenchmarks and check performance budgets |
| `just dicts` | Rebuild bundled dictionaries from pinned wordfreq |
| `just bigrams` | Build the Russian word pair table from Wikipedia |
| `just swipe-*` | Evaluate, compare and install the slide model |

## Baseline profile

`:benchmark` is an `com.android.test` module targeting `:app`. It contains the macrobenchmark and the
baseline profile generator. `baselineProfile` in `:app` is set to `saveInSrc = true` and is not
regenerated by ordinary builds; `just profile` runs it on an unlocked phone and commits the result
under `app/src/release/generated/baselineProfiles`. The APK ships it and `deploy-release` makes the
system install and compile it.

## Version and release

`versionCode` and `versionName` are set in `app/build.gradle.kts`. The release tag must be
`v<versionName>`, because the word pair URL contains that tag and the update checker compares it.
`just release` copies the APK, the gzipped word pair table and the R8 mapping into `dist/`. The word
pair file is part of the release: the hash built into the APK only accepts that exact file.
