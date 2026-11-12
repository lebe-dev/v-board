#!/usr/bin/env python3
"""Packs a model trained with neural-swipe-typing into a directory to take to the keyboard project.

Run from the root of the neural-swipe-typing checkout, in its uv environment:

    uv run python make_bundle.py --train-config experiments/ru_full/version_0/config.json \
        --checkpoint best.ckpt --out bundle

The directory then holds weights.pt, config.json, ru.properties and golden.txt:
the plain weights, the training settings, what the keyboard needs to know about the model, and the
features the training code computes for real gestures, which the keyboard's own code must reproduce.
"""

import argparse
import json
import sys
from pathlib import Path

# Escapes keep this file plain ASCII, so pasting it into any console cannot damage it.
ANCHOR_LEFT = "\u0439"
ANCHOR_RIGHT = "\u0445"
ANCHOR_BOTTOM = "\u044f"
GOLDEN_GESTURES = 200
SPECIAL_DECODER_TOKENS = ("<eos>", "<unk>", "<pad>", "<sos>")
MODEL_PARTS = ("enc_in_emb_model.", "dec_in_emb_model.", "encoder.", "decoder.", "out.")


def read_json(path):
    with open(path, encoding="utf-8") as file:
        return json.load(file)


def center(hitbox):
    return hitbox["x"] + hitbox["w"] / 2, hitbox["y"] + hitbox["h"] / 2


def spec_lines(num_classes, box, grid, statistics, decoder_labels, eos_id, sos_id, key_labels):
    """The model spec as `key=value` lines."""
    by_label = {key["label"]: key["hitbox"] for key in grid["keys"] if "label" in key}
    left, top = center(by_label[ANCHOR_LEFT])
    right, _ = center(by_label[ANCHOR_RIGHT])
    _, bottom = center(by_label[ANCHOR_BOTTOM])
    grid_keys = "".join(label for label in by_label if label in key_labels)
    lines = [
        f"decoder_tokens={''.join(decoder_labels)}",
        f"num_classes={num_classes}",
        f"eos_id={eos_id}",
        f"sos_id={sos_id}",
        f"key_labels={''.join(key_labels)}",
        f"grid_keys={grid_keys}",
        f"anchor_left={left}",
        f"anchor_right={right}",
        f"anchor_top={top}",
        f"anchor_bottom={bottom}",
        f"x_min={box['x_min']}",
        f"x_max={box['x_max']}",
        f"y_min={box['y_min']}",
        f"y_max={box['y_max']}",
    ]
    for name in ("velocity_x", "velocity_y", "acceleration_x", "acceleration_y"):
        lines.append(f"{name}_mean={statistics[name]['mean']}")
        lines.append(f"{name}_std={statistics[name]['std']}")
    return lines


def valid_gestures(path, grid_name):
    with open(path, encoding="utf-8") as file:
        for line in file:
            record = json.loads(line)
            curve = record["curve"]
            if curve.get("grid_name") == grid_name:
                yield record["word"], curve["x"], curve["y"], curve["t"]


def numbers(values):
    return " ".join(f"{value:.7g}" for value in values)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--train-config", required=True)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    sys.path.insert(1, "src")
    import torch

    from feature_extraction.swipe_feature_extractor_factory import swipe_feature_extractor_factory
    from ns_tokenizers import CharLevelTokenizerv2, KeyboardTokenizer

    config = read_json(args.train_config)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    grid = read_json(config["grids_path"])[config["grid_name"]]
    statistics = read_json(config["trajectory_features_statistics_path"])
    boxes = read_json(config["bounding_boxes_path"])
    word_tokenizer = CharLevelTokenizerv2(config["vocab_path"])
    keyboard_tokenizer = KeyboardTokenizer(config["keyboard_tokenizer_path"])

    eos_id = word_tokenizer.char_to_idx["<eos>"]
    decoder_labels = [word_tokenizer.idx_to_char[index] for index in range(eos_id)]
    if any(label in SPECIAL_DECODER_TOKENS for label in decoder_labels):
        raise ValueError("The decoder tokens must start with the letters")
    specials = set(keyboard_tokenizer.all_special_tokens)
    key_labels = [
        keyboard_tokenizer.idx_to_label[index]
        for index in range(len(keyboard_tokenizer))
        if keyboard_tokenizer.idx_to_label[index] not in specials
    ]
    if any(keyboard_tokenizer.get_token(label) != index for index, label in enumerate(key_labels)):
        raise ValueError("The key tokens must start with the letters")

    lines = spec_lines(
        config["num_classes"],
        boxes[config["grid_name"]],
        grid,
        statistics,
        decoder_labels,
        eos_id,
        word_tokenizer.char_to_idx["<sos>"],
        key_labels,
    )
    (out / "ru.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (out / "config.json").write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")

    checkpoint = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
    weights = {name.removeprefix("model."): value for name, value in checkpoint["state_dict"].items()}
    weights = {name: value for name, value in weights.items() if name.startswith(MODEL_PARTS)}
    torch.save(weights, out / "weights.pt")

    extractor = swipe_feature_extractor_factory(
        grid=grid,
        keyboard_tokenizer=keyboard_tokenizer,
        trajectory_features_statistics=statistics,
        bounding_boxes=boxes,
        grid_name=config["grid_name"],
        component_configs=config["feature_extractor"],
    )
    with open(out / "golden.txt", "w", encoding="utf-8") as golden:
        for key in grid["keys"]:
            label = key.get("label")
            if label in key_labels:
                box = key["hitbox"]
                golden.write(f"key {label} {box['x']} {box['y']} {box['w']} {box['h']}\n")
        gestures = valid_gestures(config["dataset_paths"]["val"], config["grid_name"])
        for count, (word, xs, ys, ts) in enumerate(gestures):
            if count == GOLDEN_GESTURES:
                break
            tensors = (torch.tensor(values, dtype=torch.float32) for values in (xs, ys, ts))
            trajectory, keys = extractor(*tensors)
            golden.write(
                f"gesture {word}|{numbers(xs)}|{numbers(ys)}|{numbers(ts)}|"
                f"{numbers(keys.tolist())}|{numbers(trajectory.flatten().tolist())}\n"
            )
    print(f"Bundle written to {out}: {sorted(file.name for file in out.iterdir())}")


if __name__ == "__main__":
    main()
