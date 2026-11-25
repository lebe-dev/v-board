# Dictionaries

## Word lists and binary images

The word lists are `core/src/main/dict/ru.tsv` and `en.tsv`: word and frequency pairs. They are not
parsed at runtime. The `:core` task `dictionaryImages` compiles them with `DictionaryImage` into
`dict/<language>.dict` files, which `:app` packs as assets.

`DictionaryImage` is a little-endian binary image with a fixed header and aligned sections:

- word frequencies;
- log probabilities for the language model;
- trie arrays: first child, next sibling, word index and labels;
- word text offsets and the text itself.

The image holds both the word table used for completions and the trie used by the gesture decoder, so
one file serves both. `:app` memory-maps the asset with `DictionaryAssets.open`; the `.dict`
extension is in `noCompress`, because a memory-mapped asset must stay uncompressed in the APK.

`Dictionary` reads the image and exposes:

- `size`, `contains`, `frequency`;
- `completions(prefix, limit)`, which selects the most frequent words in a prefix range without
  sorting thousands of candidates;
- `corrections(word, limit)` for one-edit corrections;
- `languageModel`, a lazy unigram model over the same vocabulary.

## Word pair tables

A `BigramTable` holds how often words follow each other in Russian Wikipedia. It is produced by
`scripts/swipe_model/bigrams.py` and installed with `just bigrams` at
`build/bigrams/assets/dict/ru.bigrams`. The table is tens of megabytes and is not in git.

The format is documented in `BigramTable`: a header, word offsets, UTF-8 word text, unigram log
probabilities, pair ranges per word and packed pair entries. Words are folded (`ё` into `е`) and the
table carries its own vocabulary, so it does not depend on the dictionary order. Lookups are absolute
reads, which makes a memory-mapped table thread-safe.

The table is used in two places:

- `NextWords` produces next-word predictions and context-aware prefix completions on the main thread.
- `SwitchableGestureEngine` passes a `BigramTable.Context` to the neural search, where it adds a
  `bigramTerm` to candidate scores.

The rules for splitting text into word pairs in `bigrams.py` and `BigramTable.previousWord` must
match. `scripts/swipe_model/evaluate.py` repeats the same features and search in Python and must be
kept in sync with the Kotlin code.

## Delivery: debug and release

The two build types deliver the table differently.

- **Debug** builds add `build/bigrams/assets` as an asset directory. The table is inside the APK at
  `dict/ru.bigrams`, and `WordPairs.isBundled` detects it.
- **Release** builds do not pack the table. `app/build.gradle.kts` reads its SHA-256 and size with
  `FileFingerprint` and writes `WORD_PAIRS_URL`, `WORD_PAIRS_SHA256` and `WORD_PAIRS_SIZE` into
  `BuildConfig` for the release variant only. The URL points at
  `https://github.com/lebe-dev/v-board/releases/download/v<versionName>/ru.bigrams.gz`.

`WordPairs` reconciles the two:

- `isAvailable` means "bundled or a verified download is installed".
- `open` memory-maps the bundled asset or the downloaded file.
- `installed` accepts a file only when its length matches the expected size.
- `delete` removes downloaded tables.

`WordPairsDownload` is a process-wide singleton with a `StateFlow` of progress and failure states.
It downloads with timeouts, checks free space, unpacks through `WordPairsInstaller`, verifies the
size and SHA-256, writes to a temporary file and renames it into place. A failed download is reported
to the settings screen; the "release has no table" case is distinguished from a network failure.

`WordPairsRefresh` and its job/receiver run after `MY_PACKAGE_REPLACED`. If the user had downloaded a
table of another version, the app fetches the table of the new version in the background. A user who
never downloaded one gets nothing. An old table is deleted because its hash no longer matches the
build.

The user-facing download lives in "Slide to type" - "Word pairs dictionary" in the settings. The
keyboard picks up an installed or deleted table at the next field through
`GestureDecoder.refreshBigrams()`, called from `KeyboardService.refreshKeyboard`.

## Tap typing inputs

`TapSuggester` combines several dictionary sources:

- `Dictionary.completions` for exact prefix completions.
- `BigramTable` context for ranking and next words.
- `KeyProximity`, built from `KeyboardGeometry` or from the layout spec, for spatial correction costs.
- `TapEditDistance` for edit costs.
- `TapSuggestOptions` for the settings-controlled parts.

`GestureDecoder` rebuilds the suggester for a language whenever the dictionary, word pair table or
key geometry changes. `setGeometry` is called when the keyboard is measured, so corrections use the
real key centers of the current screen.

## Dictionary sources and tests

- `core/src/test/.../dict/DictionaryImageTest` checks the image round trip.
- `DictionaryTest` covers completions and corrections.
- `BigramTableTest`, `NextWordsTest` and `WordPairsInstallerTest` cover the table and its delivery.
- `TapSuggesterTest` covers ranking, autocorrect candidates and spatial weighting.
- `just dicts` regenerates the images; `just bigrams` regenerates the word pair table.
