# Updates

## Model

`core/update` has the Android-free parts:

- `AppVersion` parses semantic versions, with an optional `v` prefix and build metadata, and orders
  pre-releases before the matching release.
- `ReleaseNotes` turns GitHub Markdown into the plain text shown in the settings.

`app/update` has the runtime:

- `UpdatePrefs` stores the settings and the last result in `updates`.
- `GithubReleases` talks to the GitHub API.
- `UpdateCheck` is the process-wide check state machine.
- `UpdateSchedule` and `UpdateCheckJob` run the optional background check.
- `UpdateDownload`, `ApkVerifier` and `UpdateInstaller` handle the APK.
- `UpdatesScreen` is the settings page and the banner on the main settings screen.

## Checking releases

`GithubReleases` uses `https://api.github.com/repos/lebe-dev/v-board/releases`:

- stable releases come from `/latest`, so drafts and pre-releases are never offered;
- beta releases come from a list request, and the highest non-draft version is chosen;
- the tag is parsed with `AppVersion`;
- the APK is the first `.apk` asset, with its download URL, size and `sha256:` digest when GitHub
  provides one.

The check compares the tag with `BuildConfig.VERSION_NAME`. A tag must be `v<versionName>` for the
release to be installable, because the word pair URL uses the same tag.

`UpdateCheck.start` runs one check at a time on `Dispatchers.IO` and publishes a `StateFlow` with
idle, checking, up to date, available and failed states. The latest release and the last check time
are stored even when the release is not newer, so the settings screen can show both. `available()`
filters by version and the beta preference; `announced()` additionally filters a dismissed version.

`UpdateSchedule` uses a persisted periodic `JobScheduler` job with id 7301 and network type `any`.
It is cancelled when the background check is off and rescheduled when the interval changes. The job
runs the same check and retries after a network failure.

## Downloading and verifying

`UpdateDownload` is a process-wide singleton with one download at a time and a `StateFlow` of progress
and failures. It writes `cacheDir/updates/vboard-<version>.apk`, using connect and read timeouts,
requires HTTP 200, and writes exactly the announced number of bytes while hashing them. Previous APKs
are deleted first.

`ApkVerifier` refuses a file unless:

- the package name matches the installed app;
- every signer certificate matches a certificate of the installed app.

The size and hash checks catch a damaged or substituted download; the signature check catches a
foreign build, including a debug build installed over a release or another signing key. A failed
verification is a distinct state, not a network error.

## Installing

`UpdateInstaller` uses Android's `PackageInstaller`:

- a full install session for the app package;
- the APK is streamed into `base.apk` and flushed;
- the session is committed with an explicit mutable `PendingIntent` to `UpdateInstallReceiver`.

The receiver maps `STATUS_PENDING_USER_ACTION` to a confirmation intent that the updates screen opens
while it is in the foreground, success and user abort to idle, and every other failure to the install
failure state and the app log. The screen sends the user to the unknown-sources setting when the app
is not allowed to install packages.

`REQUEST_INSTALL_PACKAGES` is declared for this flow, and every install is still confirmed by the
user in the system dialog.

## Settings and UI

`UpdatePrefs` in `updates`:

- `auto_check` off by default;
- `check_interval` daily or weekly;
- `beta_releases` off by default;
- the latest `AvailableRelease` as JSON;
- the last successful check time;
- the dismissed banner version.

The check state and the stored result are local keys and are excluded from the settings backup, so an
imported backup does not carry another phone's update state.

The updates page shows the installed version with the build hash, the last check, the manual check
button, release notes, APK size and install state. When a word pair table is installed, the page notes
that the new version downloads its own table.

## Relationship to the word pair table

The two downloads are separate but share the release tag:

- the update downloads an APK from the GitHub release;
- after the package is replaced, `WordPairsRefresh` downloads `ru.bigrams.gz` from the same tag if the
  user had a table of the previous version.

A release must therefore contain both the APK and `dist/ru.bigrams.gz`, and `versionName` must match
the tag. See [Dictionaries](dictionaries.md) and [Build and release](build-and-release.md).
