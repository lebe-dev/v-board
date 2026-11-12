#!/usr/bin/env bash
# Downloads Russian Wikipedia (wikimedia/wikipedia, 20231101.ru: 21 parquet shards, ~4.6 GB) for
# the word pair table of slide to type. Text under CC BY-SA 4.0.
#   usage: fetch_wikipedia.sh <directory>
# The directory must lie outside the repository: the shards are untrusted data, read only by
# `python -I scripts/swipe_model/bigrams.py`. Every shard is pinned by SHA-256 at a fixed dataset
# revision; already verified shards are not downloaded again.
set -euo pipefail

DEST="${1:?usage: $0 <directory>}"
REVISION="b04c8d1ceb2f5cd4588862100d08de323dccfbaa"
BASE="https://huggingface.co/datasets/wikimedia/wikipedia/resolve/${REVISION}/20231101.ru"

SHARDS=(
  "9f8eaf92accb29a4641010655a50d1c8a5880d22020a5c98ea2666a28c7be66a  train-00000-of-00021.parquet"
  "1e099f0e54624cb49573b632ccc750806dc64803707c499f3492ed6550cb3227  train-00001-of-00021.parquet"
  "252a36e7455b027cfc7d21071ae3b42e8d42885c9f204934fc26f98f7e93f142  train-00002-of-00021.parquet"
  "59e9cf1361e11439d8049b52bab94a9b0a6288f4f88224ba18c79b6f089fd565  train-00003-of-00021.parquet"
  "190509c9964f83832f1319b6fdaf1002f97e3cf16eca2835a99f7cef1e254b0a  train-00004-of-00021.parquet"
  "063b4ad46d609dfa0ef5b79414806ec8a755a4f1d282674c0d83a2aa477894fa  train-00005-of-00021.parquet"
  "a8910c56a94253edf23c722e782f54efc812f262fbf61ca4fc75149cb070d458  train-00006-of-00021.parquet"
  "39b59952cd92a148b301f4d2b3ae1fb71e4caab987c6abe278f0dbdf0b01bb88  train-00007-of-00021.parquet"
  "9df744fb888585b0db5cacf605f8a8ba8562828a0c240106c70d716738e41e87  train-00008-of-00021.parquet"
  "bf3781c558a3a2b8125e567f060895ead704577943832e6047bcc71f202975fd  train-00009-of-00021.parquet"
  "f480c34cd725c63ce276a322ac2f06aa97c3b356c4574a94fb5a273a1501857b  train-00010-of-00021.parquet"
  "30a2bd638e0556d1d09d459fd5ef5a4a7accbd54a7cc452798770bd80481dc5f  train-00011-of-00021.parquet"
  "21e33d8a7a0d068e573db688f5816a22fd690bf699dee958373f9b9e957921ad  train-00012-of-00021.parquet"
  "475666c0d80153ab9a76adca42db8afd18fc16c87804e3de0532ffc1309b2d0c  train-00013-of-00021.parquet"
  "d2ad352beecb92765110e57e8ee9409f4ffe03ff9e4b6ec0640840dec748db18  train-00014-of-00021.parquet"
  "dc248873a3cc80b857f11a0320cfaa889da803eabf09351f1ba8abbf9348eda0  train-00015-of-00021.parquet"
  "0e2d2100e924ebedb057a37e789fec861fe2b78787c912c266e36eadd8d2e0a7  train-00016-of-00021.parquet"
  "1a5c96dfda452e7725229760347e781b46f88ccd5df4196a5c1a9d46b51b1a65  train-00017-of-00021.parquet"
  "588e30523cfed480151b4bd5c1a7d7211dc03971fee399a3db6af3ad04e42c87  train-00018-of-00021.parquet"
  "4c3bd676fbeb9d92ae7ed324e99f329de2542f5399cbf39b924529fff944dec6  train-00019-of-00021.parquet"
  "fc1e2edc58fa27085d36c32c38922d7904077d9cdac05719efbcefa3faeea240  train-00020-of-00021.parquet"
)

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# fetch <name> <sha256>
fetch() {
  local name="$1" expected="$2" dest temporary actual
  dest="${DEST}/${name}"
  if [[ -f "${dest}" ]] && [[ "$(sha256_file "${dest}")" == "${expected}" ]]; then
    echo "OK     ${name}"
    return
  fi
  echo "Fetch  ${name}"
  temporary="$(mktemp "${DEST}/.${name}.download.XXXXXX")"
  if ! curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --silent --show-error \
    --output "${temporary}" "${BASE}/${name}"; then
    rm -f -- "${temporary}"
    echo "Download failed: ${BASE}/${name}" >&2
    return 1
  fi
  actual="$(sha256_file "${temporary}")"
  if [[ "${actual}" != "${expected}" ]]; then
    rm -f -- "${temporary}"
    echo "SHA-256 mismatch for ${name}: expected ${expected}, got ${actual}" >&2
    return 1
  fi
  chmod 644 "${temporary}"
  mv -f -- "${temporary}" "${dest}"
  echo "OK     ${name}"
}

mkdir -p "${DEST}"
pids=()
for entry in "${SHARDS[@]}"; do
  fetch "${entry#*  }" "${entry%%  *}" &
  pids+=($!)
  # Four downloads at a time.
  if (( ${#pids[@]} >= 4 )); then
    wait "${pids[0]}"
    pids=("${pids[@]:1}")
  fi
done
for pid in "${pids[@]}"; do wait "${pid}"; done
