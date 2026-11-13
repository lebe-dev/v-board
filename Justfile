set shell := ["bash", "-euo", "pipefail", "-c"]
# SENTRY_DSN and the release key settings come from `.env` (see `.env.example`) and reach every Gradle build.
set dotenv-load

gradle := "./gradlew --console=plain"
adb := env_var_or_default("ADB", "adb")
ime := "ru.tinyops.android.vboard/.KeyboardService"
debug_apk := "app/build/outputs/apk/debug/app-debug.apk"
release_apk := "app/build/outputs/apk/release/app-release.apk"
# Packed into debug builds only; release builds download it from the GitHub release.
word_pairs := "build/bigrams/assets/dict/ru.bigrams"
# Russian Wikipedia for the word pair table: untrusted downloads, kept outside the repository.
wiki_dir := env_var_or_default("WIKI_DIR", env_var("HOME") + "/.cache/v-board/wikipedia-ru")
# Security scanners, pinned by version and digest.
osv_scanner_image := "ghcr.io/google/osv-scanner:v2.7.1@sha256:a70ffffc2bfd33482953e5d6b64c6fcdb30239717a07b0f0d3ab2859ce3e4cde"
gitleaks_image := "ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f"
trivy_image := "aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb"
trivy_cache := env_var("HOME") + "/.cache/v-board/trivy"
bigrams_python := "PYTHONDONTWRITEBYTECODE=1 uv run --python 3.12 --no-project --with pyarrow==26.0.1 --with numpy==2.5.4 python -I"

default:
    @just --list

# Run all unit tests: slide to type decoder, keyboard core (JVM) and app (Robolectric).
test: voice-runtime
    {{ gradle }} :stt:test :core:test :app:testDebugUnitTest

# Static analysis: ktlint, detekt and Android Lint; fails on any finding.
lint: voice-runtime
    {{ gradle }} ktlintCheck detekt :app:lintDebug

# Security scans in Docker: OSV-Scanner and Trivy check the shipped dependencies, Gitleaks the git history.
security: voice-runtime
    #!/usr/bin/env bash
    set -euo pipefail
    rm -rf build/security
    {{ gradle }} -PsecurityLocks --write-locks \
        :app:dependencies --configuration releaseRuntimeClasspath \
        :core:dependencies --configuration runtimeClasspath \
        :stt:dependencies --configuration runtimeClasspath >/dev/null
    lockfiles=()
    for module in app core stt; do
        lockfiles+=(--lockfile "gradle.lockfile:/repo/build/security/$module/gradle.lockfile")
    done
    run=(docker run --rm -v "$PWD:/repo:ro" -w /repo)
    echo "OSV-Scanner: dependencies"
    "${run[@]}" {{ osv_scanner_image }} scan source "${lockfiles[@]}"
    echo "Trivy: dependencies"
    mkdir -p "{{ trivy_cache }}"
    "${run[@]}" -v "{{ trivy_cache }}:/cache" {{ trivy_image }} fs --cache-dir /cache --quiet \
        --scanners vuln --exit-code 1 build/security
    echo "Gitleaks: git history"
    "${run[@]}" -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0=/repo \
        {{ gitleaks_image }} git --redact --no-banner /repo

# Auto-format Kotlin sources with ktlint.
format:
    {{ gradle }} ktlintFormat

# Build the debug APK.
build: voice emoji
    {{ gradle }} :app:assembleDebug
    @echo "APK: {{ debug_apk }}"

# Build the release APK (one universal APK, R8-minified, signed with the release key) and the word pair table
# the app downloads from the GitHub release into dist/; attach both to the release.
release: security voice emoji
    #!/usr/bin/env bash
    set -euo pipefail
    if [ ! -f "{{ word_pairs }}" ]; then
        echo "No word pair table: run 'just bigrams' first, the release offers it for download." >&2
        exit 1
    fi
    {{ gradle }} :app:assembleRelease
    # ExecuTorch aborts the process on any native error when R8 strips fbjni (seen in 0.14.1).
    for class in com.facebook.jni.HybridData\$Destructor com.facebook.jni.UnknownCppException; do
        if ! grep -qF "$class -> $class:" app/build/outputs/mapping/release/mapping.txt; then
            echo "R8 removed or renamed $class; check the fbjni rules in app/proguard-rules.pro." >&2
            exit 1
        fi
    done
    version="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
    mkdir -p dist
    cp "{{ release_apk }}" "dist/vboard-${version}.apk"
    echo "APK: dist/vboard-${version}.apk"
    # The app checks the unpacked table against the hash built into it, so this exact file goes to the release.
    gzip -9 -n -c "{{ word_pairs }}" > dist/ru.bigrams.gz
    echo "Word pairs: dist/ru.bigrams.gz (attach to the v${version} release)"
    # Error reports name the build as version+commit; only this exact mapping decodes its stack traces.
    mapping="dist/vboard-${version}+$(git rev-parse --short HEAD).mapping.txt.gz"
    gzip -9 -c app/build/outputs/mapping/release/mapping.txt > "$mapping"
    echo "R8 mapping: $mapping"
    if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
        echo "Warning: uncommitted changes are in this build, so its commit does not describe it." >&2
    fi

# Create the release signing key; keytool asks for the passwords. Keep the file and the passwords out of git.
release-key path=(env_var("HOME") + "/.config/v-board/release.jks") alias="vboard":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -e "{{ path }}" ]; then
        echo "{{ path }} already exists: a new key would not update the installed app." >&2
        exit 1
    fi
    mkdir -p "$(dirname "{{ path }}")"
    keytool -genkeypair -v -keystore "{{ path }}" -alias "{{ alias }}" -keyalg RSA -keysize 4096 -validity 10000
    chmod 600 "{{ path }}"
    echo "Add to .env: RELEASE_KEYSTORE_FILE={{ path }} and RELEASE_KEY_ALIAS={{ alias }}, plus both passwords."
    echo "Back up the keystore: without it no update can be installed over the published app."

# Decode a stack trace from a release error report: `just retrace dist/vboard-X+hash.mapping.txt.gz trace.txt`.
retrace mapping stacktrace:
    #!/usr/bin/env bash
    set -euo pipefail
    tool="${ANDROID_HOME:?ANDROID_HOME is not set}/cmdline-tools/latest/bin/retrace"
    map="{{ mapping }}"
    if [[ "$map" == *.gz ]]; then
        map="$(mktemp)"
        trap 'rm -f "$map"' EXIT
        gunzip -c "{{ mapping }}" > "$map"
    fi
    "$tool" "$map" "{{ stacktrace }}"

# Build the debug APK, install it on the USB-connected phone and make it the active keyboard.
deploy: build
    #!/usr/bin/env bash
    set -euo pipefail
    if ! {{ adb }} -d get-state >/dev/null 2>&1; then
        echo "No USB device: connect the phone and enable USB debugging (adb devices)." >&2
        exit 1
    fi
    {{ adb }} -d install -r "{{ debug_apk }}"
    {{ adb }} -d shell ime enable "{{ ime }}"
    {{ adb }} -d shell ime set "{{ ime }}"
    echo "Installed and selected {{ ime }}"

# Install the release APK on the USB-connected phone, compile it from its profile and select it.
deploy-release: release
    #!/usr/bin/env bash
    set -euo pipefail
    version="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
    {{ adb }} -d install -r "dist/vboard-${version}.apk"
    # A profile inside the APK reaches the system only through ProfileInstaller; without this step the compiler has none.
    {{ adb }} -d shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE ru.tinyops.android.vboard/androidx.profileinstaller.ProfileInstallReceiver
    {{ adb }} -d shell cmd package compile -f -m speed-profile ru.tinyops.android.vboard
    {{ adb }} -d shell ime enable "{{ ime }}"
    {{ adb }} -d shell ime set "{{ ime }}"
    echo "Installed and selected {{ ime }}"

# Regenerate the Baseline Profile on the unlocked USB-connected phone; the result is kept in app/src/release.
profile: voice
    ADB="{{ adb }}" scripts/on_unlocked_phone.sh {{ gradle }} :app:generateBaselineProfile -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true

# Measure a release-like build on the unlocked USB-connected phone and check the performance budgets.
bench: voice
    ADB="{{ adb }}" scripts/on_unlocked_phone.sh {{ gradle }} :benchmark:connectedBenchmarkReleaseAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
    python3 scripts/check_perf_budgets.py

# Download what recognition on a server needs: sherpa-onnx runtime and Silero VAD (SHA-256 pinned).
voice: voice-runtime voice-models

# Download the sherpa-onnx AAR (~56 MB), needed to compile the app.
voice-runtime:
    scripts/fetch_voice_assets.sh runtime

# Download Silero VAD (~1 MB), bundled into the APK.
voice-models:
    scripts/fetch_voice_assets.sh models

# Fetch the emoji pictures (Apple style, ~3 MB in the APK) and rebuild the catalog; needs `pip install Pillow`.
emoji:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -d app/src/main/assets/emoji/img ] && [ -n "$(ls -A app/src/main/assets/emoji/img)" ]; then
        echo "Emoji pictures are already in place."
    else
        python3 scripts/fetch_emoji.py
    fi

# Rebuild the bundled dictionaries from wordfreq; the version is pinned because another one gives other lists.
dicts:
    uv run --python 3.12 --no-project --with wordfreq==3.1.1 python -I scripts/fetch_dictionaries.py

# Build the Russian word pair table for slide to type from Wikipedia (~4.6 GB, downloaded once into WIKI_DIR).
bigrams min="3":
    #!/usr/bin/env bash
    set -euo pipefail
    scripts/fetch_wikipedia.sh "{{ wiki_dir }}"
    mkdir -p build/bigrams "$(dirname "{{ word_pairs }}")"
    # Shard 20 stays out: bigrams-contexts takes the previous words of the validation gestures from it.
    shards=()
    for shard in "{{ wiki_dir }}"/train-000*-of-00021.parquet; do
        [[ "$shard" == *train-00020-* ]] || shards+=("$shard")
    done
    {{ bigrams_python }} scripts/swipe_model/bigrams.py count "${shards[@]}" --output build/bigrams/counts.npz
    {{ bigrams_python }} scripts/swipe_model/bigrams.py build build/bigrams/counts.npz --min-count {{ min }} \
        --output "{{ word_pairs }}"

# Give the NeuroSwipe validation gestures previous words from the Wikipedia shard the table leaves out.
bigrams-contexts archive:
    python3 -I scripts/swipe_model/prepare_valid.py "{{ archive }}" build/swipe/valid.txt
    scripts/fetch_wikipedia.sh "{{ wiki_dir }}"
    mkdir -p build/bigrams
    {{ bigrams_python }} scripts/swipe_model/bigrams.py contexts build/swipe/valid.txt \
        "{{ wiki_dir }}/train-00020-of-00021.parquet" --output build/bigrams/contexts.tsv

# Measure the default slide to type decoder on the NeuroSwipe validation set inside data.zip.
swipe-baseline archive:
    python3 -I scripts/swipe_model/prepare_valid.py "{{ archive }}" build/swipe/valid.txt
    SWIPE_VALID_TEXT="$PWD/build/swipe/valid.txt" {{ gradle }} :core:test --rerun --info --tests '*DefaultEngineBaselineTest*' | grep BASELINE

# Measure the installed slide to type model on the USB-connected phone, in a package of its own; `contexts` from bigrams-contexts.
swipe-device-check archive limit="1000" assets="" contexts="": voice
    #!/usr/bin/env bash
    set -euo pipefail
    python3 -I scripts/swipe_model/prepare_valid.py "{{ archive }}" build/swipe/check/valid.txt
    rm -f build/swipe/check/contexts.tsv
    if [ -n "{{ contexts }}" ]; then cp "{{ contexts }}" build/swipe/check/contexts.tsv; fi
    # Connected tests run on every attached device otherwise, and another project may use an emulator.
    export ANDROID_SERIAL="$({{ adb }} -d get-serialno)"
    override=""
    if [ -n "{{ assets }}" ]; then override="-PswipeModelAssets=$(cd "{{ assets }}" && pwd)"; fi
    {{ gradle }} :app:connectedDebugAndroidTest -PswipeCheck=true $override \
        -Pandroid.testInstrumentationRunnerArguments.limit={{ limit }}
    {{ adb }} -d logcat -d -s SwipeCheck:I | tail -n 6

# Measure the installed slide to type model on the Mac: the accuracy of swipe-device-check in about a minute.
swipe-eval archive *args:
    python3 -I scripts/swipe_model/prepare_valid.py "{{ archive }}" build/swipe/valid.txt
    PYTHONDONTWRITEBYTECODE=1 uv run --python 3.12 --no-project --with torch==2.13.0 --with executorch==1.5.1 python -I scripts/swipe_model/evaluate.py build/swipe/valid.txt {{ args }}

# Export an untrained model and measure its speed on the phone: the words mean nothing, the time is real.
swipe-probe archive limit="300":
    uv run --python 3.12 --no-project --with torch==2.13.0 --with executorch==1.5.1 scripts/swipe_model/export_model.py --random --output build/swipe/probe/swipe/ru.pte
    just swipe-device-check "{{ archive }}" {{ limit }} build/swipe/probe

# Export the weights from a bundle made on the training machine into the app and check its features.
swipe-install bundle:
    #!/usr/bin/env bash
    set -euo pipefail
    for file in weights.pt config.json ru.properties golden.txt; do
        [ -f "{{ bundle }}/$file" ] || { echo "Missing $file in {{ bundle }}" >&2; exit 1; }
    done
    mkdir -p app/src/main/assets/swipe core/src/test/resources/swipe
    uv run --python 3.12 --no-project --with torch==2.13.0 --with executorch==1.5.1 scripts/swipe_model/export_model.py --weights "{{ bundle }}/weights.pt" --config "{{ bundle }}/config.json" --output app/src/main/assets/swipe/ru.pte
    cp "{{ bundle }}/ru.properties" app/src/main/assets/swipe/
    cp "{{ bundle }}/ru.properties" "{{ bundle }}/golden.txt" core/src/test/resources/swipe/
    {{ gradle }} :core:test --tests '*SwipeTrainingParityTest*'

# Remove build outputs.
clean:
    {{ gradle }} clean
    rm -rf dist
