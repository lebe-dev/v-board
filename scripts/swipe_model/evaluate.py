#!/usr/bin/env python3
"""Measures the installed slide to type model on the Mac, the way `just swipe-device-check` does on a phone.

It runs the ExecuTorch program and spec of the app with a copy of the keyboard's feature extraction
(SwipeFeatureExtractor) and word search (NeuralWordSearch) over the validation gestures written by
prepare_valid.py. Accuracy matches the phone to the last digit; time means nothing here. It takes
about a minute, so search settings and dictionaries can be tried without a phone. Keep the copy in
step with the Kotlin code: a change there that is missing here shows as a mismatch with the phone.

    just swipe-eval /path/to/data.zip [--dict words.tsv] [--beam 4] [--rescored 4] [--limit 1000]

Like the phone check, it skips the first 20 gestures of the grid `default`, so the numbers compare.

With --contexts (written by `bigrams.py contexts`) and --bigrams (written by `bigrams.py build`) the
rescored words also get the association with the previous word, as `BigramTable` does in the keyboard.
--tune picks the weight and the smoothing on one half of the gestures and reports the other, both ways.
"""

import argparse
import contextlib
import heapq
import math
import multiprocessing
import os
import struct
import time
from pathlib import Path

import torch

ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / "app/src/main/assets/swipe/ru.pte"
SPEC = ROOT / "app/src/main/assets/swipe/ru.properties"
DICTIONARY = ROOT / "core/src/main/dict/ru.tsv"

GRID = "default"
WARM_UP = 20
CANDIDATES = 4
MRR_WEIGHTS = (1.0, 0.1, 0.09, 0.08)
LENGTH_PENALTY = 0.5
MAX_STEPS = 34
MAX_POINTS = 299
MIN_INTERVAL_MILLIS = 12.0
WORD = "$"
FOLDED_LETTERS = {"ё": "е"}
CONTEXT_WEIGHT = 0.4
CONTEXT_SMOOTHING = 30000.0
CONTEXT_RESCORED = 6


def read_spec(path: Path) -> dict:
    spec = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            spec[key.strip()] = value.strip()
    return spec


def read_validation(path: Path) -> tuple[dict, list]:
    """Key centers of the grid by letter, and the gestures as (word, xs, ys, ts)."""
    centers, gestures = {}, []
    for line in path.read_text(encoding="utf-8").splitlines():
        fields = line.split("\t")
        if fields[0] == "key":
            if fields[1] == GRID:
                x, y, w, h = map(float, fields[3:7])
                centers[fields[2]] = (x + w / 2, y + h / 2)
        elif len(fields) == 5 and fields[1] == GRID:
            xs, ys, ts = ([float(v) for v in field.split(",")] for field in fields[2:5])
            gestures.append((fields[0], xs, ys, ts))
    return centers, gestures


def read_dictionary(path: Path) -> dict:
    """A trie of nested dicts; the node of a word holds it under WORD as (word, frequency)."""
    root = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        word, frequency = line.split("\t")[:2]
        node = root
        for letter in word:
            node = node.setdefault(letter, {})
        node[WORD] = (word, float(frequency))
    return root


def words_of(root: dict) -> set:
    found, stack = set(), [root]
    while stack:
        node = stack.pop()
        for key, child in node.items():
            if key == WORD:
                found.add(fold(child[0]))
            else:
                stack.append(child)
    return found


def fold(word: str) -> str:
    for letter, base in FOLDED_LETTERS.items():
        word = word.replace(letter, base)
    return word


class Features:
    """SwipeFeatureExtractor: thinning, mapping onto the training grid, trajectory and nearest key."""

    def __init__(self, spec: dict, centers: dict) -> None:
        number = lambda key: float(spec[key])  # noqa: E731
        left, right, bottom = centers["й"], centers["х"], centers["я"]
        self.scale_x = (number("anchor_right") - number("anchor_left")) / (right[0] - left[0])
        self.scale_y = (number("anchor_bottom") - number("anchor_top")) / (bottom[1] - left[1])
        self.offset_x = number("anchor_left") - self.scale_x * left[0]
        self.offset_y = number("anchor_top") - self.scale_y * left[1]
        self.x_range = (number("x_min"), number("x_max"))
        self.y_range = (number("y_min"), number("y_max"))
        self.normalizations = [
            (number(f"{name}_mean"), number(f"{name}_std"))
            for name in ("velocity_x", "velocity_y", "acceleration_x", "acceleration_y")
        ]
        labels = spec["key_labels"]
        # In token order, because training resolves a point equally far from two keys to the lower token.
        keys = sorted((labels.index(label), center) for label, center in centers.items() if label in spec["grid_keys"])
        self.keys = keys

    def __call__(self, xs: list, ys: list, ts: list):
        points = thin_out(list(zip(xs, ys, ts)))
        if len(points) < 2:
            return None
        x = torch.tensor([self.scale_x * p[0] + self.offset_x for p in points], dtype=torch.float32)
        y = torch.tensor([self.scale_y * p[1] + self.offset_y for p in points], dtype=torch.float32)
        t = torch.tensor([p[2] for p in points], dtype=torch.float32)
        velocity_x, velocity_y = central_difference(x, t), central_difference(y, t)
        derived = [velocity_x, velocity_y, central_difference(velocity_x, t), central_difference(velocity_y, t)]
        columns = [
            (x - self.x_range[0]) / (self.x_range[1] - self.x_range[0]),
            (y - self.y_range[0]) / (self.y_range[1] - self.y_range[0]),
        ] + [(values - mean) / std for values, (mean, std) in zip(derived, self.normalizations)]
        keys = torch.tensor([self.nearest(p[0], p[1]) for p in points], dtype=torch.long)
        return torch.stack(columns, dim=1), keys

    def nearest(self, x: float, y: float) -> int:
        best, best_distance = 0, math.inf
        for token, (center_x, center_y) in self.keys:
            distance = (center_x - x) ** 2 + (center_y - y) ** 2
            if distance < best_distance:
                best, best_distance = token, distance
        return best


def thin_out(points: list) -> list:
    kept = [points[0]]
    for point in points[1:-1]:
        if point[2] - kept[-1][2] >= MIN_INTERVAL_MILLIS:
            kept.append(point)
    last = points[-1]
    if len(points) == 1:
        pass
    elif last[2] > kept[-1][2]:
        kept.append(last)
    elif len(kept) > 1:
        kept[-1] = last
    if len(kept) <= MAX_POINTS:
        return kept
    step = (len(kept) - 1) / (MAX_POINTS - 1)
    return [kept[int(i * step)] for i in range(MAX_POINTS)]


def central_difference(values: torch.Tensor, times: torch.Tensor) -> torch.Tensor:
    result = torch.zeros_like(values)
    if len(values) > 2:
        result[1:-1] = (values[2:] - values[:-2]) / (times[2:] - times[:-2])
    return result


class Search:
    """NeuralWordSearch: best-first beam search limited to the dictionary, then rescoring."""

    def __init__(self, spec: dict, decode, beam: int, rescored: int) -> None:
        self.tokens = spec["decoder_tokens"]
        self.eos = int(spec["eos_id"])
        self.sos = int(spec["sos_id"])
        self.decode = decode
        self.beam = beam
        self.rescored = rescored
        self.folded = {base: letter for letter, base in FOLDED_LETTERS.items() if letter not in self.tokens}
        self.calls = 0

    def logits(self, tokens: list, memory) -> torch.Tensor:
        self.calls += 1
        return self.decode.execute([torch.tensor(tokens, dtype=torch.long), memory])[0].double()

    def __call__(self, memory, root: dict, limit: int) -> list:
        order = 0
        partial = [(0.0, order, [self.sos], [root])]
        finished = []
        while partial:
            score, _, tokens, nodes = heapq.heappop(partial)
            for child in self.expand(memory, score, tokens, nodes):
                if child[1][-1] == self.eos or len(tokens) >= MAX_STEPS:
                    finished.append(child)
                else:
                    order += 1
                    heapq.heappush(partial, (child[0], order, child[1], child[2]))
            if len(partial) > self.beam:
                partial = heapq.nsmallest(self.beam, partial)
                heapq.heapify(partial)
        finished.sort(key=lambda hypothesis: hypothesis[0])
        count = min(self.rescored, len(finished))
        full_scores = [self.full_score(memory, hypothesis[1]) for hypothesis in finished[:count]] if count >= 2 else []
        return finished, full_scores

    def expand(self, memory, score: float, tokens: list, nodes: list) -> list:
        allowed = self.allowed(nodes)
        if not allowed:
            return []
        if len(allowed) == 1:
            log_probs = [0.0]
        else:
            chosen = self.logits(tokens, memory)[-1][allowed]
            log_probs = (chosen - torch.logsumexp(chosen, 0)).tolist()
        slots = range(len(allowed))
        if len(allowed) > self.beam:
            slots = sorted(slots, key=lambda slot: -log_probs[slot])[: self.beam]
        length = len(tokens)
        denormalized = score * length**LENGTH_PENALTY
        children = []
        for slot in slots:
            token = allowed[slot]
            child_score = (denormalized - log_probs[slot]) / (length + 1) ** LENGTH_PENALTY
            child_nodes = nodes if token == self.eos else self.advance(nodes, token)
            children.append((child_score, tokens + [token], child_nodes))
        return children

    def allowed(self, nodes: list) -> list:
        allowed = []
        for token, letter in enumerate(self.tokens):
            other = self.folded.get(letter)
            if any(letter in node or (other is not None and other in node) for node in nodes):
                allowed.append(token)
        if any(WORD in node for node in nodes):
            allowed.append(self.eos)
        return allowed

    def advance(self, nodes: list, token: int) -> list:
        letter = self.tokens[token]
        other = self.folded.get(letter)
        found = []
        for node in nodes:
            if letter in node:
                found.append(node[letter])
            if other is not None and other in node:
                found.append(node[other])
        return found

    def full_score(self, memory, tokens: list) -> float:
        log_probs = torch.log_softmax(self.logits(tokens[:-1], memory), dim=-1)
        log_prob = log_probs.gather(1, torch.tensor(tokens[1:])[:, None]).sum().item()
        return -log_prob / len(tokens) ** LENGTH_PENALTY

    def folded_word(self, tokens: list) -> str:
        end = len(tokens) - 1 if tokens[-1] == self.eos else len(tokens)
        return "".join(self.tokens[token] for token in tokens[1:end])


def rank(finished: list, full_scores: list, count: int, association) -> list:
    """NeuralWordSearch.rescore: the [count] best by full score, less the weighted [association]."""
    count = min(count, len(full_scores))
    if count < 2:
        return finished
    scored = [(full_scores[i] - association(finished[i][1]), i) for i in range(count)]
    return [finished[i] for _, i in sorted(scored)] + finished[count:]


def words(finished: list, limit: int) -> list:
    result = []
    for _, _, nodes in finished:
        for word, _ in sorted((node[WORD] for node in nodes if WORD in node), key=lambda entry: -entry[1]):
            if len(result) == limit:
                return result
            if word not in result:
                result.append(word)
    return result


class Bigrams:
    """BigramTable: reads the file of `bigrams.py build` and gives log P(w | prev) - log P(w)."""

    HEADER = struct.Struct("<IIIIq")
    MAGIC = 0x4B424247
    COUNT_BITS = 13
    LOG_COUNT_STEP = 256.0

    def __init__(self, path: Path) -> None:
        data = path.read_bytes()
        magic, version, size, pairs, _ = self.HEADER.unpack_from(data)
        if magic != self.MAGIC or version != 1:
            raise SystemExit(f"{path} is not a bigram table")
        at = self.HEADER.size

        def ints(count: int, kind: str) -> list:
            nonlocal at
            values = struct.unpack_from(f"<{count}{kind}", data, at)
            at += 4 * count
            return list(values)

        offsets = ints(size + 1, "i")
        text = data[at : at + offsets[-1]]
        at += offsets[-1] + (-offsets[-1] % 4)
        self.ids = {text[offsets[i] : offsets[i + 1]].decode("utf-8"): i for i in range(size)}
        self.uni = ints(size, "f")
        self.totals = ints(size, "i")
        self.starts = ints(size + 1, "i")
        self.entries = ints(pairs, "I")

    def association(self, previous: str, word: str, smoothing: float) -> float:
        """The same arithmetic as BigramTable.association, in double precision."""
        previous_id = self.ids.get(previous)
        if previous_id is None:
            return 0.0
        unseen = math.log(smoothing) - math.log(self.totals[previous_id] + smoothing)
        word_id = self.ids.get(word)
        if word_id is None:
            return unseen
        low, high = self.starts[previous_id], self.starts[previous_id + 1]
        while low < high:
            middle = (low + high) // 2
            entry_word = self.entries[middle] >> self.COUNT_BITS
            if entry_word < word_id:
                low = middle + 1
            elif entry_word > word_id:
                high = middle
            else:
                quantized = self.entries[middle] & ((1 << self.COUNT_BITS) - 1)
                pair_count = math.exp(quantized / self.LOG_COUNT_STEP)
                word_prob = math.exp(self.uni[word_id])
                return math.log(pair_count / word_prob + smoothing) - math.log(self.totals[previous_id] + smoothing)
        return unseen


def read_contexts(path: Path) -> dict:
    """Gesture number on the grid (counting from 0, warm-up included) to the folded previous word."""
    contexts = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        number, _, previous = line.split("\t")
        contexts[int(number)] = fold(previous)
    return contexts


STATE = {}


@contextlib.contextmanager
def quiet_native_stderr():
    """The runtime reports on stderr that it cannot read ARM CPU details of the Mac; that changes nothing here."""
    saved = os.dup(2)
    with open(os.devnull, "w") as devnull:
        os.dup2(devnull.fileno(), 2)
    try:
        yield
    finally:
        os.dup2(saved, 2)
        os.close(saved)


def start_worker(arguments: argparse.Namespace) -> None:
    torch.set_num_threads(1)
    with quiet_native_stderr():
        from executorch.runtime import Runtime

        program = Runtime.get().load_program(str(arguments.model))
        STATE["encode"] = program.load_method("encode")
        rescored = max(arguments.rescored, arguments.context_rescored)
        STATE["search"] = Search(STATE["spec"], program.load_method("decode"), arguments.beam, rescored)


def decode_gesture(gesture: tuple) -> tuple:
    """The finished hypotheses without their trie nodes' children, their full scores and decoder calls."""
    number, word, xs, ys, ts = gesture
    features = STATE["features"](xs, ys, ts)
    if features is None:
        return number, word, [], [], 0
    search = STATE["search"]
    search.calls = 0
    memory = STATE["encode"].execute(list(features))[0]
    finished, full_scores = search(memory, STATE["dictionary"], CANDIDATES)
    calls = search.calls - len(full_scores)
    # Trie nodes do not travel between processes cheaply; keep only the words they end.
    light = [(score, search.folded_word(tokens), [{WORD: node[WORD]} for node in nodes if WORD in node])
             for score, tokens, nodes in finished]
    return number, word, light, full_scores, calls


class Scorer:
    """Turns the decoded hypotheses into metrics for one weight and smoothing of the context."""

    def __init__(self, results: list, contexts: dict, bigrams, arguments: argparse.Namespace) -> None:
        self.results = results
        self.contexts = contexts
        self.bigrams = bigrams
        self.rescored = arguments.rescored
        self.context_rescored = arguments.context_rescored
        self.associations = {}

    def association_of(self, number: int, previous: str, smoothing: float):
        """Associations of the rescored words of a gesture, kept so that tuning tries weights quickly."""
        key = (number, smoothing)
        if key not in self.associations:
            self.associations[key] = {}
        cache = self.associations[key]

        def association(word: str) -> float:
            if word not in cache:
                cache[word] = self.bigrams.association(previous, word, smoothing)
            return cache[word]

        return association

    def candidates(self, result: tuple, weight: float, smoothing: float) -> tuple:
        number, _, finished, full_scores, _ = result
        previous = self.contexts.get(number) if self.bigrams else None
        if previous not in (self.bigrams.ids if self.bigrams else ()):
            previous = None
        if previous is None or weight == 0:
            count, association = self.rescored, lambda _: 0.0
        else:
            count = self.context_rescored
            of = self.association_of(number, previous, smoothing)
            association = lambda word: weight * of(word)  # noqa: E731
        rescoring_calls = min(count, len(full_scores)) if len(full_scores) >= 2 and count >= 2 else 0
        return words(rank(finished, full_scores, count, association), CANDIDATES), rescoring_calls

    def measure(self, weight: float, smoothing: float, select=lambda result: True) -> dict:
        top1 = mrr = calls = count = 0.0
        for result in self.results:
            if not select(result):
                continue
            found, rescoring_calls = self.candidates(result, weight, smoothing)
            candidates = list(dict.fromkeys(fold(word) for word in found))
            target = fold(result[1])
            calls += result[4] + rescoring_calls
            count += 1
            if target in candidates:
                place = candidates.index(target)
                top1 += place == 0
                mrr += MRR_WEIGHTS[place]
        count = max(count, 1)
        return {"top1": top1 / count, "mrr": mrr / count, "calls": calls / count, "count": int(count)}


WEIGHTS = (0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.5, 0.6)
SMOOTHINGS = (300.0, 1000.0, 3000.0, 10000.0, 30000.0, 100000.0, 300000.0, 1000000.0)


def tune(scorer: Scorer, contexts: dict) -> tuple:
    """Two-fold check: the weight and smoothing best on one half of the gestures with a context,
    measured on the other half, and back. Returns the pair best on all of them."""

    def half(parity: int):
        return lambda result: result[0] in contexts and result[0] % 2 == parity

    def best_on(select) -> tuple:
        grid = [(scorer.measure(w, m, select)["top1"], w, m) for m in SMOOTHINGS for w in WEIGHTS]
        return max(grid, key=lambda entry: entry[0])

    gains = []
    for parity in (0, 1):
        _, weight, smoothing = best_on(half(parity))
        other = half(1 - parity)
        base, tuned = scorer.measure(0.0, smoothing, other), scorer.measure(weight, smoothing, other)
        gains.append(tuned["top1"] - base["top1"])
        print(f"Tuned on half {parity}: weight {weight}, smoothing {smoothing:g}; "
              f"other half top1 {base['top1']:.4f} -> {tuned['top1']:.4f}")
    print(f"Held-out gain in top1, mean of both halves: {100 * sum(gains) / 2:+.2f} points")
    _, weight, smoothing = best_on(lambda result: result[0] in contexts)
    return weight, smoothing


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("validation", type=Path, help="the file written by prepare_valid.py")
    parser.add_argument("--model", type=Path, default=MODEL)
    parser.add_argument("--spec", type=Path, default=SPEC)
    parser.add_argument("--dict", type=Path, default=DICTIONARY, help="word<TAB>frequency per line")
    parser.add_argument("--beam", type=int, default=4)
    parser.add_argument("--rescored", type=int, default=4)
    parser.add_argument("--contexts", type=Path, help="gesture number, word and previous word per line")
    parser.add_argument("--bigrams", type=Path, help="the table written by bigrams.py build")
    parser.add_argument("--weight", type=float, default=CONTEXT_WEIGHT)
    parser.add_argument("--smoothing", type=float, default=CONTEXT_SMOOTHING)
    parser.add_argument("--context-rescored", type=int, default=CONTEXT_RESCORED, help="words rescored with a context")
    parser.add_argument("--tune", action="store_true", help="pick the weight and smoothing on half the gestures")
    parser.add_argument("--limit", type=int, default=0, help="gestures to measure, all when 0")
    parser.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    arguments = parser.parse_args()

    started = time.time()
    spec = read_spec(arguments.spec)
    centers, gestures = read_validation(arguments.validation)
    gestures = [(number, *gesture) for number, gesture in enumerate(gestures)][WARM_UP:]
    if arguments.limit:
        gestures = gestures[: arguments.limit]
    STATE["spec"] = spec
    STATE["features"] = Features(spec, centers)
    STATE["dictionary"] = read_dictionary(arguments.dict)
    vocabulary = words_of(STATE["dictionary"])

    # Forked workers share the dictionary built above instead of each reading it again.
    context = multiprocessing.get_context("fork")
    with context.Pool(arguments.workers, initializer=start_worker, initargs=(arguments,)) as pool:
        results = pool.map(decode_gesture, gestures, chunksize=16)

    contexts = read_contexts(arguments.contexts) if arguments.contexts else {}
    bigrams = Bigrams(arguments.bigrams) if arguments.bigrams else None
    scorer = Scorer(results, contexts, bigrams, arguments)
    count = len(results)
    covered = sum(fold(result[1]) in vocabulary for result in results)
    print(f"Dictionary {arguments.dict.name}: {len(vocabulary)} words, {covered / count:.4f} of the gestures covered")
    weight, smoothing = arguments.weight, arguments.smoothing
    if bigrams and contexts and arguments.tune:
        weight, smoothing = tune(scorer, contexts)
    plain = scorer.measure(0.0, smoothing)
    print(f"Gestures {count}, top1 {plain['top1']:.4f}, swipe_mrr {plain['mrr']:.4f}")
    print(f"Decoder calls per gesture: {plain['calls']:.1f}; {time.time() - started:.0f} s")
    if not (bigrams and contexts):
        return
    with_context = lambda result: result[0] in contexts  # noqa: E731
    base, chosen = scorer.measure(0.0, smoothing, with_context), scorer.measure(weight, smoothing, with_context)
    overall = scorer.measure(weight, smoothing)
    print(f"With the previous word (weight {weight}, smoothing {smoothing:g}), gestures with a context: "
          f"{chosen['count']}, top1 {base['top1']:.4f} -> {chosen['top1']:.4f}, "
          f"swipe_mrr {base['mrr']:.4f} -> {chosen['mrr']:.4f}")
    print(f"All gestures: top1 {overall['top1']:.4f}, swipe_mrr {overall['mrr']:.4f}, "
          f"decoder calls per gesture {overall['calls']:.1f}")


if __name__ == "__main__":
    main()
