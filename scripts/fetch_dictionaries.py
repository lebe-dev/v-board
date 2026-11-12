#!/usr/bin/env python3
"""Builds the bundled gesture-typing dictionaries from wordfreq.

Source: wordfreq (https://github.com/rspeer/wordfreq). It aggregates many open
corpora (OpenSubtitles, Wikipedia, subtitles, Twitter, ...) and reports a
single de-duplicated frequency per word. The package code is Apache-2.0; the
bundled frequency data is released under CC BY-SA 4.0.

Output: core/src/main/dict/<lang>.tsv, one "word<TAB>frequency" per line:
lowercase, letters of the target alphabet only, 2+ letters, top N by frequency.
wordfreq splits text at hyphens, so common Russian words such as "что-то" and
"из-за" are added from a list here. wordfreq estimates their frequency from
the parts, which puts "что-то" above "чтобы"; like wordfreq does for word breaks
it has to guess, the estimate is divided by 10 for each hyphen.

Run with `just dicts`, which pins the wordfreq version: another version gives
other lists. Set TOP_N_EN / TOP_N_RU to change the vocabulary size.
"""

import os
import pathlib

ALPHABETS = {
    "en": set("abcdefghijklmnopqrstuvwxyz"),
    "ru": set("абвгдеёжзийклмнопрстуфхцчшщъыьэюя"),
}
# Russian is highly inflected, so it gets a larger share of the vocabulary. At 300k words the
# Russian list covers 98.3% of the NeuroSwipe validation words (120k: 95.0%, 500k: 99.1%).
TOP_N = {
    "en": int(os.environ.get("TOP_N_EN", "60000")),
    "ru": int(os.environ.get("TOP_N_RU", "300000")),
}

HYPHEN_FACTOR = 10

_RU_PRONOUNS = (
    "кто кого кому кем ком что чего чему чем чём "
    "какой какая какое какие какого какую каким каком какому каких какими "
    "чей чья чьё чьи чью чьего чьей чьему чьим чьих чьими "
    "где куда откуда когда как почему зачем отчего сколько скольких"
).split()
_RU_KOE = (
    "кто кого кому кем ком что чего чему чем чём как где куда "
    "какой какая какое какие какого какую каким каком какому каких какими"
).split()
_RU_PO = (
    "моему твоему своему нашему вашему другому прежнему новому старому разному любому всякому "
    "настоящему хорошему русски английски немецки французски украински человечески дружески детски"
).split()
_RU_FIXED = (
    "из-за из-под все-таки всё-таки опять-таки наконец-то так-то вот-вот чуть-чуть еле-еле "
    "точь-в-точь давным-давно как-никак волей-неволей мало-помалу ей-богу туда-сюда "
    "ну-ка давай-ка давайте-ка ха-ха ха-ха-ха"
).split()
HYPHENATED = {
    "en": [],
    "ru": [f"{word}-{particle}" for word in _RU_PRONOUNS for particle in ("то", "либо", "нибудь")]
    + [f"кое-{word}" for word in _RU_KOE]
    + [f"по-{word}" for word in _RU_PO]
    + _RU_FIXED,
}
OUT = pathlib.Path(__file__).resolve().parent.parent / "core/src/main/dict"


def build(lang: str, alphabet: set[str]) -> None:
    import wordfreq

    limit = TOP_N[lang]
    merged: dict[str, float] = {}
    for word in wordfreq.top_n_list(lang, limit * 2):
        lowered = word.lower()
        if len(lowered) < 2 or not set(lowered) <= alphabet:
            continue
        frequency = wordfreq.word_frequency(lowered, lang)
        if frequency <= 0.0:
            continue
        merged[lowered] = merged.get(lowered, 0.0) + frequency
        if len(merged) >= limit:
            break

    # Below the rarest word of the list a hyphenated word would not have made the cut either.
    rarest = min(merged.values())
    for word in HYPHENATED[lang]:
        parts = word.split("-")
        if not all(parts) or not all(set(part) <= alphabet for part in parts):
            raise ValueError(f"{word} is not a {lang} word of letters joined by hyphens")
        frequency = wordfreq.word_frequency(word, lang) / HYPHEN_FACTOR ** (len(parts) - 1)
        if frequency >= rarest:
            merged[word] = merged.get(word, 0.0) + frequency

    top = sorted(merged.items(), key=lambda kv: (-kv[1], kv[0]))
    target = OUT / f"{lang}.tsv"
    target.write_text("".join(f"{w}\t{f}\n" for w, f in top), encoding="utf-8")
    print(f"{lang}: {len(top)} words -> {target.relative_to(OUT.parents[4])}")


def main() -> None:
    try:
        import wordfreq  # noqa: F401
    except ImportError:
        raise SystemExit("wordfreq is required: run `just dicts`")
    OUT.mkdir(parents=True, exist_ok=True)
    for lang, alphabet in ALPHABETS.items():
        build(lang, alphabet)


if __name__ == "__main__":
    main()
