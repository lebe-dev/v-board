#!/usr/bin/env python3
"""Writes the validation gestures of the NeuroSwipe archive as plain text for the JVM baseline.

Output: one gesture per line, `word<TAB>grid<TAB>x,x,…<TAB>y,y,…<TAB>t,t,…`, and `key` lines first
describing the letters of each grid: `key<TAB>grid<TAB>label<TAB>x<TAB>y<TAB>w<TAB>h`.
"""

import json
import sys
import zipfile
from pathlib import Path


def main(archive_path: str, output_path: str) -> None:
    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    grids = {}
    gestures = []
    with zipfile.ZipFile(archive_path) as archive:
        with archive.open("valid.ref") as references, archive.open("valid.jsonl") as lines:
            for reference, line in zip(references, lines):
                curve = json.loads(line)["curve"]
                grid = curve["grid"]
                grids.setdefault(grid["grid_name"], grid)
                columns = [",".join(str(value) for value in curve[axis]) for axis in "xyt"]
                gestures.append("\t".join([reference.decode("utf-8").strip(), grid["grid_name"], *columns]))
    with open(output, "w", encoding="utf-8") as file:
        for name, grid in grids.items():
            for key in grid["keys"]:
                if "label" in key and key["label"].isalpha() and len(key["label"]) == 1:
                    box = key["hitbox"]
                    file.write(f"key\t{name}\t{key['label']}\t{box['x']}\t{box['y']}\t{box['w']}\t{box['h']}\n")
        file.write("\n".join(gestures) + "\n")
    print(f"Wrote {len(gestures)} gestures to {output}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: prepare_valid.py <data.zip> <output file>")
    main(sys.argv[1], sys.argv[2])
