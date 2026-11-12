#!/usr/bin/env python3
"""Builds the emoji picker assets from the emoji-datasource-apple npm package.

Writes into app/src/main/assets/emoji/:
  emoji.tsv        the catalog (category, emoji, image name, keywords, group); committed to git
  img/<name>.webp  one 64 px picture per emoji; ignored by git and fetched by `just emoji`

The package code and data are MIT-licensed; the pictures themselves are Apple's artwork. They
are downloaded at build time and are not part of the repository.

Needs Pillow (`pip install Pillow`). The package tarball is pinned by its SHA-256.
"""

import hashlib
import io
import json
import sys
import tarfile
import urllib.request
from pathlib import Path

from PIL import Image

VERSION = "16.0.0"
URL = f"https://registry.npmjs.org/emoji-datasource-apple/-/emoji-datasource-apple-{VERSION}.tgz"
SHA256 = "b34d1665e136f163b0d4433318df151895b785186e8c729b2211b43017945075"

# Android 14 (the minimum supported system) draws Emoji 15.0; newer ones would show as boxes in
# the editor even though the picker has their pictures.
LATEST_EMOJI = (15, 0)

# Picker tabs, in order. Datasource category names map to the stable ids used by the app.
CATEGORIES = {
    "Smileys & Emotion": "smileys",
    "People & Body": "people",
    "Animals & Nature": "nature",
    "Food & Drink": "food",
    "Activities": "activities",
    "Travel & Places": "travel",
    "Objects": "objects",
    "Symbols": "symbols",
    "Flags": "flags",
}

# The datasource splits the categories into about 85 subcategories, too fine for a settings page.
# They are merged into the groups the user can switch on and off; a group id is unique across
# categories and names a string in the app.
GROUPS = {
    "faces": ["face-smiling", "face-affection", "face-tongue", "face-hand", "face-neutral-skeptical",
              "face-sleepy", "face-unwell", "face-hat", "face-glasses", "face-concerned",
              "face-negative", "face-costume"],
    "animal-faces": ["cat-face", "monkey-face"],
    "hearts-emotions": ["heart", "emotion"],
    "hands": ["hand-fingers-open", "hand-fingers-partial", "hand-single-finger", "hand-fingers-closed",
              "hands", "hand-prop"],
    "body": ["body-parts"],
    "people": ["person", "person-gesture", "person-role", "person-fantasy", "person-resting",
               "person-symbol"],
    "people-sports": ["person-activity", "person-sport"],
    "family": ["family"],
    "animals": ["animal-mammal", "animal-bird", "animal-amphibian", "animal-reptile", "animal-marine",
                "animal-bug"],
    "plants": ["plant-flower", "plant-other"],
    "fruit-vegetables": ["food-fruit", "food-vegetable"],
    "meals": ["food-prepared", "food-asian"],
    "sweets": ["food-sweet"],
    "drinks": ["drink", "dishware"],
    "places": ["place-map", "place-geographic", "place-building", "place-religious", "place-other",
               "hotel"],
    "transport": ["transport-ground", "transport-water", "transport-air"],
    "time-weather": ["time", "sky & weather"],
    "events": ["event"],
    "awards": ["award-medal"],
    "sports": ["sport"],
    "games": ["game"],
    "crafts": ["arts & crafts"],
    "clothing": ["clothing"],
    "music": ["sound", "music", "musical-instrument"],
    "devices": ["phone", "computer", "light & video"],
    "office": ["book-paper", "mail", "writing", "office", "lock"],
    "money": ["money"],
    "tools": ["tool", "science", "medical", "household", "other-object"],
    "signs": ["transport-sign", "warning", "religion", "zodiac", "gender", "other-symbol", "av-symbol"],
    "arrows-math": ["arrow", "math", "punctuation", "currency"],
    "keys-letters": ["keycap", "alphanum"],
    "shapes": ["geometric"],
    "misc-flags": ["flag", "subdivision-flag"],
    "country-flags": ["country-flag"],
}
GROUP_OF = {sub: group for group, subs in GROUPS.items() for sub in subs}

PICTURE_SIDE = 64
WEBP_QUALITY = 90

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/emoji"


def version_of(text: str) -> tuple[int, ...]:
    return tuple(int(part) for part in text.split("."))


def download() -> bytes:
    print(f"Downloading emoji-datasource-apple {VERSION} (~100 MB)...")
    with urllib.request.urlopen(URL) as response:
        data = response.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != SHA256:
        sys.exit(f"Checksum mismatch: expected {SHA256}, got {digest}")
    return data


def to_text(unified: str) -> str:
    return "".join(chr(int(code, 16)) for code in unified.split("-"))


def main() -> None:
    archive = tarfile.open(fileobj=io.BytesIO(download()), mode="r:gz")
    catalog = json.load(archive.extractfile("package/emoji.json"))
    entries = [
        e
        for e in catalog
        if e["category"] in CATEGORIES
        and e["has_img_apple"]
        and version_of(e["added_in"]) <= LATEST_EMOJI
    ]
    entries.sort(key=lambda e: (list(CATEGORIES).index(e["category"]), e["sort_order"]))

    (OUT / "img").mkdir(parents=True, exist_ok=True)
    rows = []
    for e in entries:
        name = e["image"].removesuffix(".png")
        target = OUT / "img" / f"{name}.webp"
        if not target.exists():
            source = archive.extractfile(f"package/img/apple/64/{e['image']}")
            picture = Image.open(source).convert("RGBA")
            if picture.size != (PICTURE_SIDE, PICTURE_SIDE):
                picture = picture.resize((PICTURE_SIDE, PICTURE_SIDE), Image.LANCZOS)
            picture.save(target, "WEBP", quality=WEBP_QUALITY, method=6)
        keywords = " ".join(sorted({k.replace("_", " ") for k in e["short_names"]}))
        group = GROUP_OF[e["subcategory"]]
        rows.append(f"{CATEGORIES[e['category']]}\t{to_text(e['unified'])}\t{name}\t{keywords}\t{group}")

    (OUT / "emoji.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"Wrote {len(rows)} emoji to {OUT}")


if __name__ == "__main__":
    main()
