#!/usr/bin/env python3
"""Builds the Russian bigram table for the slide to type search from Wikipedia text.

Three steps, each a subcommand:

    count     counts word pairs in Wikipedia parquet shards (wikimedia/wikipedia, 20231101.ru)
    contexts  gives each validation gesture a previous word from a held-out shard, for evaluate.py
    build     writes the binary table the keyboard reads (`ru.bigrams`), keeping frequent pairs

Only words of the keyboard dictionary count, with `ё` folded into `е` as the model and the search
do. Text is cut into sentences at `.!?…;:()«»"` and line breaks; no pair crosses a sentence. A token
that is not a dictionary word (a number, a Latin word, a stray dash) breaks the pair too. Commas do
not break pairs.

Keep the sentence and token rules in step with `BigramTable.previousWord` in the keyboard, and the
file layout with `BigramTable`.

Shards are untrusted downloads: run this with `python -I` from outside their directory.
"""

import argparse
import multiprocessing
import random
import re
import struct
import sys
import time
import zlib
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parents[2]
DICTIONARY = ROOT / "core/src/main/dict/ru.tsv"

SENTENCE_BREAK = re.compile(r'[.!?…\n;:()«»"]+')
WORD = re.compile(r"[а-я]+(?:-[а-я]+)*")
DASHES = "—–-"
ID_BITS = 19
GRID = "default"

MAGIC = 0x4B424247  # "KBBG"
VERSION = 1
LOG_COUNT_STEP = 256  # quantized ln c(prev, w) = round(ln c * 256), 13 bits
COUNT_BITS = 13
HEADER = struct.Struct("<IIIIq")  # magic, version, words, pairs, total pairs


def fold(word: str) -> str:
    return word.replace("ё", "е")


def read_vocabulary(path: Path) -> list:
    """Dictionary words, lowercased and folded, sorted and unique: the word ids of the counts."""
    words = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        word = line.split("\t")[0].strip().lower()
        if word:
            words.add(fold(word))
    words = sorted(words)
    if len(words) >= 1 << ID_BITS:
        sys.exit(f"{len(words)} words do not fit in {ID_BITS} bits")
    return words


def sentences(text: str):
    """Sentences of [text] as lists of tokens, lowercased, folded, commas dropped, edge dashes cut."""
    text = fold(text.lower()).replace(",", " ")
    for sentence in SENTENCE_BREAK.split(text):
        yield [token.strip(DASHES) for token in sentence.split()]


def token_ids(text: str, index: dict) -> list:
    """Word ids of [text], with -1 for a token that breaks pairs and between sentences."""
    ids = []
    for tokens in sentences(text):
        ids.extend(index.get(token, -1) for token in tokens)
        ids.append(-1)
    return ids


def merge(keys: np.ndarray, counts: np.ndarray) -> tuple:
    """Sums the counts of equal keys; returns the keys sorted."""
    order = np.argsort(keys, kind="stable")
    keys, counts = keys[order], counts[order]
    if len(keys) == 0:
        return keys, counts
    starts = np.flatnonzero(np.concatenate(([True], keys[1:] != keys[:-1])))
    return keys[starts], np.add.reduceat(counts, starts)


WORKER = {}


def start_counter(vocabulary: list) -> None:
    WORKER["index"] = {word: i for i, word in enumerate(vocabulary)}


def count_row_group(task: tuple) -> tuple:
    path, group = task
    texts = pq.ParquetFile(path).read_row_group(group, columns=["text"]).column(0).to_pylist()
    index = WORKER["index"]
    ids = []
    for text in texts:
        ids.extend(token_ids(text, index))
    ids = np.asarray(ids, dtype=np.int64)
    left, right = ids[:-1], ids[1:]
    valid = (left >= 0) & (right >= 0)
    keys = (left[valid] << ID_BITS) | right[valid]
    unique, counts = np.unique(keys, return_counts=True)
    return unique, counts.astype(np.int64)


def count(arguments: argparse.Namespace) -> None:
    started = time.time()
    vocabulary = read_vocabulary(arguments.dict)
    tasks = [(str(path), group) for path in arguments.shards for group in range(pq.ParquetFile(path).num_row_groups)]
    print(f"{len(vocabulary)} words, {len(arguments.shards)} shards, {len(tasks)} row groups")
    keys, counts = np.zeros(0, np.int64), np.zeros(0, np.int64)
    pending_keys, pending_counts, pending = [], [], 0
    context = multiprocessing.get_context("spawn")
    with context.Pool(arguments.workers, initializer=start_counter, initargs=(vocabulary,)) as pool:
        for done, (group_keys, group_counts) in enumerate(pool.imap_unordered(count_row_group, tasks, chunksize=1), 1):
            pending_keys.append(group_keys)
            pending_counts.append(group_counts)
            pending += len(group_keys)
            if pending > 50_000_000 or done == len(tasks):
                keys, counts = merge(np.concatenate([keys, *pending_keys]), np.concatenate([counts, *pending_counts]))
                pending_keys, pending_counts, pending = [], [], 0
                print(f"{done}/{len(tasks)} row groups, {len(keys)} pairs, {time.time() - started:.0f} s", flush=True)
    np.savez(arguments.output, vocabulary=np.array(vocabulary), keys=keys, counts=counts)
    print(f"{counts.sum()} pairs counted, {len(keys)} distinct -> {arguments.output}")


def read_gesture_words(path: Path) -> list:
    """Words of the validation gestures on the grid evaluate.py measures, in file order."""
    words = []
    for line in path.read_text(encoding="utf-8").splitlines():
        fields = line.split("\t")
        if len(fields) == 5 and fields[0] != "key" and fields[1] == GRID:
            words.append(fold(fields[0]))
    return words


def contexts(arguments: argparse.Namespace) -> None:
    """For every gesture, the word before a random occurrence of its word in the held-out shard."""
    vocabulary = read_vocabulary(arguments.dict)
    index = {word: i for i, word in enumerate(vocabulary)}
    words = read_gesture_words(arguments.validation)
    wanted = {index[word] for word in words if word in index}
    # Reservoir per word, as large as the number of gestures of that word.
    needed = {}
    for word in words:
        if word in index:
            needed[index[word]] = needed.get(index[word], 0) + 1
    reservoirs = {word_id: [] for word_id in wanted}
    seen = dict.fromkeys(wanted, 0)
    generator = random.Random(arguments.seed)
    shard = pq.ParquetFile(arguments.holdout)
    for group in range(shard.num_row_groups):
        for text in shard.read_row_group(group, columns=["text"]).column(0).to_pylist():
            ids = token_ids(text, index)
            for previous, word in zip(ids, ids[1:]):
                if previous < 0 or word not in reservoirs:
                    continue
                seen[word] += 1
                reservoir = reservoirs[word]
                if len(reservoir) < needed[word]:
                    reservoir.append(previous)
                else:
                    slot = generator.randrange(seen[word])
                    if slot < len(reservoir):
                        reservoir[slot] = previous
    for reservoir in reservoirs.values():
        generator.shuffle(reservoir)
    used = dict.fromkeys(reservoirs, 0)
    found = 0
    with open(arguments.output, "w", encoding="utf-8") as output:
        for number, word in enumerate(words):
            word_id = index.get(word)
            if word_id is None or not reservoirs[word_id]:
                continue
            # A word seen fewer times than it has gestures gives some of its contexts twice.
            reservoir = reservoirs[word_id]
            previous = vocabulary[reservoir[used[word_id] % len(reservoir)]]
            used[word_id] += 1
            output.write(f"{number}\t{word}\t{previous}\n")
            found += 1
    print(f"{found} of {len(words)} gestures got a previous word -> {arguments.output}")


def build(arguments: argparse.Namespace) -> None:
    """Writes the table: pairs seen at least --min-count times, the vocabulary they use, totals.

    Layout, little-endian, sections aligned to 4 bytes:
        header      magic, version, words V, pairs P (uint32 each), total pairs N (int64)
        offsets     int32[V + 1]  start of each word in text
        text        UTF-8 words, sorted by bytes (the order of code points)
        uni         float32[V]    ln P(w), P(w) = (c(w) + 0.5) / (N + 0.5 V'), c(w) = Σ_prev c(prev, w)
        totals      int32[V]      c(prev) = Σ_w c(prev, w), over all pairs, kept or not
        starts      int32[V + 1]  first entry of each previous word
        entries     int32[P]      w << 13 | round(ln c(prev, w) * 256), sorted by w within prev
    """
    data = np.load(arguments.counts)
    vocabulary, keys, counts = list(data["vocabulary"]), data["keys"], data["counts"]
    previous_ids, word_ids = keys >> ID_BITS, keys & ((1 << ID_BITS) - 1)
    total_pairs = int(counts.sum())
    unigrams = np.bincount(word_ids, weights=counts, minlength=len(vocabulary))
    totals = np.bincount(previous_ids, weights=counts, minlength=len(vocabulary))
    seen_words = int((unigrams > 0).sum())

    kept = counts >= arguments.min_count
    previous_ids, word_ids, kept_counts = previous_ids[kept], word_ids[kept], counts[kept]
    used = np.zeros(len(vocabulary), dtype=bool)
    used[previous_ids] = True
    used[word_ids] = True
    old_ids = np.flatnonzero(used)
    # Byte order of UTF-8 is code point order, so the folded words stay sorted as they are.
    new_id = np.full(len(vocabulary), -1, dtype=np.int64)
    new_id[old_ids] = np.arange(len(old_ids))
    previous_ids, word_ids = new_id[previous_ids], new_id[word_ids]
    order = np.lexsort((word_ids, previous_ids))
    previous_ids, word_ids, kept_counts = previous_ids[order], word_ids[order], kept_counts[order]

    words = [vocabulary[i].encode("utf-8") for i in old_ids]
    size = len(words)
    uni = np.log((unigrams[old_ids] + 0.5) / (total_pairs + 0.5 * seen_words)).astype("<f4")
    word_totals = totals[old_ids]
    if word_totals.max() >= 1 << 31:
        sys.exit("a total does not fit in int32")
    starts = np.zeros(size + 1, dtype="<i4")
    starts[1:] = np.cumsum(np.bincount(previous_ids, minlength=size))
    quantized = np.rint(np.log(kept_counts) * LOG_COUNT_STEP).astype(np.int64)
    if quantized.max() >= 1 << COUNT_BITS:
        sys.exit("a count does not fit in the quantized field")
    entries = ((word_ids << COUNT_BITS) | quantized).astype("<u4")

    offsets = np.zeros(size + 1, dtype="<i4")
    offsets[1:] = np.cumsum([len(word) for word in words])
    text = b"".join(words)
    with open(arguments.output, "wb") as output:
        output.write(HEADER.pack(MAGIC, VERSION, size, len(entries), total_pairs))
        output.write(offsets.tobytes())
        output.write(text + b"\0" * (-len(text) % 4))
        output.write(uni.tobytes())
        output.write(word_totals.astype("<i4").tobytes())
        output.write(starts.tobytes())
        output.write(entries.tobytes())
    size_mb = arguments.output.stat().st_size / 1e6
    crc = zlib.crc32(arguments.output.read_bytes())
    print(f"min count {arguments.min_count}: {len(entries)} pairs, {size} words, {size_mb:.1f} MB, crc32 {crc:08x}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    counting = commands.add_parser("count", help="count word pairs in parquet shards")
    counting.add_argument("shards", type=Path, nargs="+")
    counting.add_argument("--output", type=Path, required=True, help="an .npz file")
    counting.add_argument("--dict", type=Path, default=DICTIONARY)
    counting.add_argument("--workers", type=int, default=max(1, (multiprocessing.cpu_count() or 2) - 2))
    counting.set_defaults(run=count)

    choosing = commands.add_parser("contexts", help="previous words for the validation gestures")
    choosing.add_argument("validation", type=Path, help="the file written by prepare_valid.py")
    choosing.add_argument("holdout", type=Path, help="a parquet shard left out of the counts")
    choosing.add_argument("--output", type=Path, required=True)
    choosing.add_argument("--dict", type=Path, default=DICTIONARY)
    choosing.add_argument("--seed", type=int, default=1)
    choosing.set_defaults(run=contexts)

    building = commands.add_parser("build", help="write the binary table")
    building.add_argument("counts", type=Path, help="the .npz file written by count")
    building.add_argument("--output", type=Path, required=True)
    building.add_argument("--min-count", type=int, default=3)
    building.set_defaults(run=build)

    arguments = parser.parse_args()
    arguments.run(arguments)


if __name__ == "__main__":
    main()
