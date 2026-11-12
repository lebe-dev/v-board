#!/usr/bin/env bash
# Downloads what recognition on the user's server needs to split speech into phrases:
#   runtime  sherpa-onnx AAR (native runtime)  -> app/libs/sherpa-onnx.aar                  (~56 MB)
#   models   Silero VAD                        -> app/src/main/assets/voice/silero_vad.onnx (~1 MB)
# Every file is pinned by SHA-256, so a truncated or tampered download fails loud.
# The files are gitignored; already verified files are not downloaded again.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WHAT="${1:-all}"

SHERPA_VERSION="1.13.0"
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
SHERPA_SHA256="28e9b348736d84b610361daf161f5904440d6d516531e896bf9ee8b0ad02764e"

VAD_URL="https://raw.githubusercontent.com/amidexe/govorun-lite/91444be6316a17a05054cd3f815ae15a02e2dda8/app/src/main/assets/silero_vad.onnx"
VAD_SHA256="9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# fetch <url> <destination> <sha256>
fetch() {
  local url="$1" dest="$2" expected="$3" name temporary actual
  name="$(basename "${dest}")"
  if [[ -f "${dest}" ]] && [[ "$(sha256_file "${dest}")" == "${expected}" ]]; then
    echo "OK     ${name}"
    return
  fi
  mkdir -p "$(dirname "${dest}")"
  echo "Fetch  ${name}"
  temporary="$(mktemp "$(dirname "${dest}")/.${name}.download.XXXXXX")"
  if ! curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --progress-bar \
    --output "${temporary}" "${url}"; then
    rm -f -- "${temporary}"
    echo "Download failed: ${url}" >&2
    exit 1
  fi
  actual="$(sha256_file "${temporary}")"
  if [[ "${actual}" != "${expected}" ]]; then
    rm -f -- "${temporary}"
    echo "SHA-256 mismatch for ${name}: expected ${expected}, got ${actual}" >&2
    exit 1
  fi
  chmod 644 "${temporary}"
  mv -f -- "${temporary}" "${dest}"
  echo "OK     ${name}"
}

fetch_runtime() {
  fetch "${SHERPA_URL}" "${ROOT}/app/libs/sherpa-onnx.aar" "${SHERPA_SHA256}"
}

fetch_models() {
  # Older checkouts hold the GigaAM model here, and everything in assets/ ends up in the APK.
  rm -rf -- "${ROOT}/app/src/main/assets/voice/gigaam-v3"
  fetch "${VAD_URL}" "${ROOT}/app/src/main/assets/voice/silero_vad.onnx" "${VAD_SHA256}"
}

case "${WHAT}" in
  runtime) fetch_runtime ;;
  models) fetch_models ;;
  all) fetch_runtime; fetch_models ;;
  *) echo "usage: $0 [runtime|models|all]" >&2; exit 2 ;;
esac
