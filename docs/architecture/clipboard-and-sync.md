# Clipboard and Unison

## Pieces

- `core/clipboard` holds the value model: `ClipPayload` (text or image), `ClipEntry`, `ClipHistory`,
  `ClipChips`, `ClipExtractor`, `ClipEcho`, `LinkCleaner` and `RemoteClip`.
- `app/clipboard` handles Android storage and clipboard access: `ClipboardHistoryStore`,
  `ClipboardPrefs`, `ClipboardContent`, `ClipChipsControl`, `Thumbnails` and `ClipChipsControl`'s
  Unison-facing calls.
- `app/ui/ClipboardPanelView` is the panel that replaces the strip and keys.
- `app/unison` is the bridge client and control for synchronizing copies with another device.

`KeyboardService.startClipboard` creates the store, the Unison control and the chips control. It
registers a clipboard listener, refreshes chips at every field start and destroys the objects with the
service.

## History model

`ClipHistory` keeps the newest 30 entries, newest first, deduplicated by payload. The id is the copy
time in milliseconds. `ClipPayload.Text` holds text; `ClipPayload.Image` holds a source name and a
MIME type, with the actual file owned by the store.

`ClipChips` derives up to three strip chips from a clip:

- the clip itself as a text or image chip;
- extracted links, domains, e-mail addresses and phone numbers, unless extraction is turned off.

`ClipExtractor` uses regular expressions and strips trailing punctuation. `ClipEcho` keeps only
SHA-256 digests, never text, and recognizes pasted remote items, items that arrived, and repeated
sends. `LinkCleaner` removes tracking parameters from links when the clean-links setting is on.

## Storage

`ClipboardHistoryStore` writes to `filesDir/clipboard/`:

- `history.json`, written atomically, holds the index and the newest seen timestamp;
- `images/`, with image copies named by a content hash and the MIME extension.

Rules:

- The store loads the index off the main thread; `onChanged` is posted to the main thread.
- A clip older than the newest seen clip is ignored; this survives process restarts.
- Text longer than 100000 characters is not stored.
- Images up to 10 MiB are copied into the app, because the source URI may expire.
- Sensitive clips (`ClipDescription.EXTRA_IS_SENSITIVE`) are never stored or shown.
- Dropped entries delete their image file unless another entry references the same source.
- A missing image file removes the entry at load.

`ClipboardPrefs` in `clipboard_prefs` controls enabled, icon color, strip placement, extracted chips
and history retention. The panel's history switch writes `keep_history` directly.

## Panel

`ClipboardPanelView` shows the clipboard history and, when the prompt results buffer is enabled, the
prompt results tab.

- Clipboard cards are arranged in two columns; tapping pastes text or an image, edit mode deletes and
  clears.
- Prompt results are a single list with filters (all, transcripts, each prompt), tap to insert and
  drag to reorder.
- Images are decoded on a single background executor and released with the panel.
- The Unison pause button appears only while the bridge is connected and the clipboard tab is shown.

`KeyboardService.onClipboardPick` converts a pick into text insertion or `ClipboardContent.pasteImage`.
Image paste checks that the editor accepts the MIME type before committing content; otherwise it shows
a message.

## Unison bridge

Unison is a separate app, bound by AIDL. The interface is frozen and append-only.

- `IUnisonBridge` publishes text and binary clips, registers a listener, exposes recent remote items,
  opens an item by id, and pauses or resumes synchronization.
- `IUnisonBridgeListener` is one-way and reports remote items and state changes.
- `IPublishCallback` is one-way and reports the result of a publish.
- The service is `app.unison/.service.BridgeService`, protected by the signature permission
  `app.unison.permission.BRIDGE`. Only apps signed with the same key can connect.
- `protocolVersion()` must be at least 1; version 2 adds publish results. New methods must never be
  added in the middle of the existing interface.

`UnisonBridgeClient` binds on demand, keeps calls on one I/O executor and publishes on a separate
writer executor, posts callbacks to the main thread, rebinds after binder death and turns
`RemoteException` and `SecurityException` into logged failures. `UnisonControl` implements both
`RemoteClips` for the strip and `SyncPause` for the panel.

## What is synchronized

Outgoing, all conditions must hold:

- Unison is enabled and the bridge is connected and not paused;
- `EditorProfile.clipboardSyncAllowed` is true: not a password field and not incognito;
- the clip is not sensitive;
- the clip is not an echo of a remote item or of a recent send;
- the size fits the bridge limits: 16 MiB of text maximum, with 256 KiB going directly through the
  binder and larger text through a pipe; images stream through a pipe and must have a supported image
  MIME type.

The listener captures the primary clip 250 ms after a change. A copy is remembered before content
checks, so each copy is examined once. A copy that happened while Unison was disconnected is sent at
the next connection only if it is younger than one minute.

Incoming:

- On connect the client asks for three recent items and catches up with a recent local clip.
- Remote items live for five minutes as strip chips and are not shown while paused.
- Pasting a remote text opens it, cleans links if configured, and inserts it; pasting an image writes
  it to `cacheDir/unison/`, exposes it through the app FileProvider and commits it to the editor.
- A missing item says it is gone; a temporarily empty item can be retried.

Pausing is shared state: the keyboard sets it immediately and Unison reports the authoritative value
through `BridgeState`.

## Preferences and files

`unison_prefs` stores `enabled` (off by default) and `last_clip_at` (device-local, excluded from the
settings backup). Remote images are cached in `cacheDir/unison/` and older files are deleted after 24
hours. Nothing from the clipboard is written to the app log or to an error report.

## Related code

- `core/src/main/kotlin/ru/tinyops/android/vboard/core/clipboard/`
- `app/src/main/kotlin/ru/tinyops/android/vboard/clipboard/`
- `app/src/main/kotlin/ru/tinyops/android/vboard/unison/`
- `app/src/main/aidl/app/unison/bridge/`
- `app/src/main/java/app/unison/bridge/`
- `app/src/main/kotlin/ru/tinyops/android/vboard/ui/ClipboardPanelView.kt`
