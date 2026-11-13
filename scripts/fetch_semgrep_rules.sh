#!/usr/bin/env bash
# Downloads the Kotlin rules of semgrep-rules at a pinned commit into <dir> and prints the rules directory.
# The archive is pinned by SHA-256; the rules are not stored in the repository because of their license.
set -euo pipefail

COMMIT="a5ad1714f00c9f7fde980cab0ea1d6dc6d414fd2"
URL="https://codeload.github.com/semgrep/semgrep-rules/tar.gz/${COMMIT}"
SHA256="e5a7ec33035fc7707b87385cb99e1228c76b1604efe5439e92891f3005714cc3"

DEST="${1:?usage: fetch_semgrep_rules.sh <dir>}/${COMMIT}"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

if [[ -f "${DEST}/.complete" ]]; then
  echo "${DEST}/kotlin"
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf -- "${work}"' EXIT
echo "Fetch  semgrep-rules ${COMMIT}" >&2
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 --retry 3 \
  --output "${work}/rules.tar.gz" "${URL}"
actual="$(sha256_file "${work}/rules.tar.gz")"
if [[ "${actual}" != "${SHA256}" ]]; then
  echo "SHA-256 mismatch for semgrep-rules: expected ${SHA256}, got ${actual}" >&2
  exit 1
fi
mkdir -p "${work}/out"
# Only the rule files: the test sources next to them are not needed.
tar -xzf "${work}/rules.tar.gz" -C "${work}/out" --strip-components=1 \
  --exclude '*.kt' --exclude '*.kts' "semgrep-rules-${COMMIT}/kotlin"
rm -rf -- "${DEST}"
mkdir -p "$(dirname "${DEST}")"
mv -- "${work}/out" "${DEST}"
touch "${DEST}/.complete"
echo "${DEST}/kotlin"
