#!/usr/bin/env python3
"""Exports trained slide to type weights to an ExecuTorch program for the keyboard.

The training code uses the stock PyTorch transformer layers. Their attention falls apart into dozens
of small pieces when lowered to XNNPACK, which makes every decoder call about ten times slower on a
phone. So the weights go into an equivalent model written with operators XNNPACK takes whole, and
the export fails unless it gives the same answers as the stock layers.

    uv run --python 3.12 --no-project --with torch==2.13.0 --with executorch==1.5.1 \
        scripts/swipe_model/export_model.py --weights bundle/weights.pt --config bundle/config.json \
        --output bundle/ru.pte

With --random there are no weights: the words mean nothing, the time the model takes is real, which
is enough to measure speed on a phone before anything is trained.
"""

import argparse
import collections
import json
import math
from pathlib import Path

import torch
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.exir import to_edge_transform_and_lower
from executorch.runtime import Runtime
from torch import nn
from torch.export import Dim, export

TRAJECTORY_FEATURES = 6
MAX_POINTS = 299
MAX_WORD = 35
SAMPLE_POINTS = 40
SAMPLE_WORD = 5
TOLERANCE = 1e-3
LAYER_NORM_EPS = 1e-5

# Only used with --random: the numbers of the training configuration.
PROBE_SHAPE = {"d_model": 128, "heads": 4, "feed_forward": 128, "layers": 4, "keys": 36, "tokens": 37, "classes": 35}
PROBE_SPEC = """decoder_tokens=-абвгдежзийклмнопрстуфхцчшщъыьэюя
num_classes=35
eos_id=33
sos_id=36
key_labels=абвгдеёжзийклмнопрстуфхцчшщъыьэюя-
grid_keys=йцукенгшщзхфывапролджэячсмитьбю
anchor_left=49.5
anchor_right=1031
anchor_top=92
anchor_bottom=400
x_min=0
x_max=1080
y_min=0
y_max=667
velocity_x_mean=0
velocity_x_std=1
velocity_y_mean=0
velocity_y_std=1
acceleration_x_mean=0
acceleration_x_std=0.1
acceleration_y_mean=0
acceleration_y_std=0.1
"""


def positional_encoding(length: int, size: int) -> torch.Tensor:
    """The harmonic encoding of "Attention is all you need", shape (length, 1, size)."""
    position = torch.arange(length).unsqueeze(1)
    scale = torch.exp(torch.arange(0, size, 2) * (-math.log(10000.0) / size))
    encoding = torch.zeros(length, 1, size)
    encoding[:, 0, 0::2] = torch.sin(position * scale)
    encoding[:, 0, 1::2] = torch.cos(position * scale)
    return encoding


class KeyEmbedding(nn.Module):
    def __init__(self, keys: int, size: int) -> None:
        super().__init__()
        self.key_emb = nn.Embedding(keys, size)


class PointEmbedding(nn.Module):
    def __init__(self, keys: int, size: int) -> None:
        super().__init__()
        self.key_emb = KeyEmbedding(keys, size)


class StockModel(nn.Module):
    """The model as it is trained: the attribute names match the keys of the weights."""

    def __init__(self, shape: dict) -> None:
        super().__init__()
        size, heads, feed_forward, layers = shape["d_model"], shape["heads"], shape["feed_forward"], shape["layers"]
        self.key_size = size - TRAJECTORY_FEATURES
        self.enc_in_emb_model = PointEmbedding(shape["keys"], self.key_size)
        self.dec_in_emb_model = nn.Sequential(nn.Embedding(shape["tokens"], size))
        self.encoder = nn.TransformerEncoder(
            nn.TransformerEncoderLayer(size, heads, feed_forward, dropout=0.0),
            layers,
            norm=nn.LayerNorm(size, eps=LAYER_NORM_EPS),
            enable_nested_tensor=False,
        )
        self.decoder = nn.TransformerDecoder(
            nn.TransformerDecoderLayer(size, heads, feed_forward, dropout=0.0),
            layers,
            norm=nn.LayerNorm(size, eps=LAYER_NORM_EPS),
        )
        self.out = nn.Linear(size, shape["classes"])
        self.register_buffer("point_position", positional_encoding(MAX_POINTS, self.key_size))
        self.register_buffer("word_position", positional_encoding(MAX_WORD, size))

    def encode(self, trajectory: torch.Tensor, keys: torch.Tensor) -> torch.Tensor:
        embedded = self.enc_in_emb_model.key_emb.key_emb(keys).unsqueeze(1)
        embedded = embedded + self.point_position[: embedded.size(0)]
        return self.encoder(torch.cat((trajectory.unsqueeze(1), embedded), dim=-1)).squeeze(1)

    def decode(self, tokens: torch.Tensor, memory: torch.Tensor) -> torch.Tensor:
        embedded = self.dec_in_emb_model[0](tokens).unsqueeze(1)
        embedded = embedded + self.word_position[: embedded.size(0)]
        length = embedded.size(0)
        mask = torch.triu(torch.full((length, length), float("-inf")), diagonal=1)
        return self.out(self.decoder(embedded, memory.unsqueeze(1), tgt_mask=mask)).squeeze(1)


class LayerNorm(nn.Module):
    """Means over the last axis as a matrix product, because XNNPACK takes no such `mean`."""

    def __init__(self, stock: nn.LayerNorm) -> None:
        super().__init__()
        size = stock.weight.numel()
        self.weight = nn.Parameter(stock.weight.detach().clone())
        self.bias = nn.Parameter(stock.bias.detach().clone())
        self.register_buffer("average", torch.full((size, 1), 1.0 / size))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        centered = x - torch.matmul(x, self.average)
        variance = torch.matmul(centered * centered, self.average)
        return centered / torch.sqrt(variance + LAYER_NORM_EPS) * self.weight + self.bias


class Attention(nn.Module):
    """Multi-head attention for one sequence, (length, size), reshaping along one unknown axis at a time."""

    def __init__(self, stock: nn.MultiheadAttention) -> None:
        super().__init__()
        self.heads = stock.num_heads
        self.head_size = stock.head_dim
        self.scale = 1.0 / math.sqrt(stock.head_dim)
        query, key, value = stock.in_proj_weight.detach().chunk(3)
        biases = stock.in_proj_bias.detach().chunk(3)
        self.query = self._linear(query, biases[0])
        self.key = self._linear(key, biases[1])
        self.value = self._linear(value, biases[2])
        self.out = self._linear(stock.out_proj.weight.detach(), stock.out_proj.bias.detach())

    @staticmethod
    def _linear(weight: torch.Tensor, bias: torch.Tensor) -> nn.Linear:
        layer = nn.Linear(weight.shape[1], weight.shape[0])
        layer.weight = nn.Parameter(weight.clone())
        layer.bias = nn.Parameter(bias.clone())
        return layer

    def _split(self, x: torch.Tensor) -> torch.Tensor:
        return x.reshape(-1, self.heads, self.head_size).transpose(0, 1)

    def forward(self, target: torch.Tensor, source: torch.Tensor, mask: torch.Tensor | None = None) -> torch.Tensor:
        query = self._split(self.query(target))
        key = self._split(self.key(source))
        value = self._split(self.value(source))
        scores = torch.bmm(query, key.transpose(1, 2)) * self.scale
        if mask is not None:
            scores = scores + mask
        attended = torch.bmm(torch.softmax(scores, dim=-1), value)
        return self.out(attended.transpose(0, 1).reshape(-1, self.heads * self.head_size))


class EncoderLayer(nn.Module):
    def __init__(self, stock: nn.TransformerEncoderLayer) -> None:
        super().__init__()
        self.attention = Attention(stock.self_attn)
        self.up = stock.linear1
        self.down = stock.linear2
        self.norm1 = LayerNorm(stock.norm1)
        self.norm2 = LayerNorm(stock.norm2)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.norm1(x + self.attention(x, x))
        return self.norm2(x + self.down(torch.relu(self.up(x))))


class DecoderLayer(nn.Module):
    def __init__(self, stock: nn.TransformerDecoderLayer) -> None:
        super().__init__()
        self.own = Attention(stock.self_attn)
        self.cross = Attention(stock.multihead_attn)
        self.up = stock.linear1
        self.down = stock.linear2
        self.norm1 = LayerNorm(stock.norm1)
        self.norm2 = LayerNorm(stock.norm2)
        self.norm3 = LayerNorm(stock.norm3)

    def forward(self, x: torch.Tensor, memory: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        x = self.norm1(x + self.own(x, x, mask))
        x = self.norm2(x + self.cross(x, memory))
        return self.norm3(x + self.down(torch.relu(self.up(x))))


class Encode(nn.Module):
    """(points, 6) float and (points) long to (points, size)."""

    def __init__(self, stock: StockModel) -> None:
        super().__init__()
        self.keys = stock.enc_in_emb_model.key_emb.key_emb
        self.layers = nn.ModuleList(EncoderLayer(layer) for layer in stock.encoder.layers)
        self.norm = LayerNorm(stock.encoder.norm)
        self.register_buffer("position", stock.point_position.squeeze(1))

    def forward(self, trajectory: torch.Tensor, keys: torch.Tensor) -> torch.Tensor:
        embedded = self.keys(keys) + self.position[: keys.size(0)]
        x = torch.cat((trajectory, embedded), dim=-1)
        for layer in self.layers:
            x = layer(x)
        return self.norm(x)


class Decode(nn.Module):
    """(length) long and (points, size) to the logits (length, classes)."""

    def __init__(self, stock: StockModel) -> None:
        super().__init__()
        self.tokens = stock.dec_in_emb_model[0]
        self.layers = nn.ModuleList(DecoderLayer(layer) for layer in stock.decoder.layers)
        self.norm = LayerNorm(stock.decoder.norm)
        self.out = stock.out
        self.register_buffer("position", stock.word_position.squeeze(1))
        future = torch.triu(torch.full((MAX_WORD, MAX_WORD), float("-inf")), diagonal=1)
        self.register_buffer("future", future)

    def forward(self, tokens: torch.Tensor, memory: torch.Tensor) -> torch.Tensor:
        length = tokens.size(0)
        x = self.tokens(tokens) + self.position[:length]
        mask = self.future[:length, :length]
        for layer in self.layers:
            x = layer(x, memory, mask)
        return self.out(self.norm(x))


def shape_of(weights: dict, config: dict) -> dict:
    return {
        "d_model": config["d_model"],
        "heads": config["encoder"]["params"]["num_heads"],
        "feed_forward": config["encoder"]["params"]["dim_feedforward"],
        "layers": config["encoder"]["params"]["num_layers"],
        "keys": weights["enc_in_emb_model.key_emb.key_emb.weight"].shape[0],
        "tokens": weights["dec_in_emb_model.0.weight"].shape[0],
        "classes": config["num_classes"],
    }


def load_stock(weights_path: str, config_path: str) -> StockModel:
    weights = torch.load(weights_path, map_location="cpu", weights_only=True)
    config = json.loads(Path(config_path).read_text(encoding="utf-8"))
    model = StockModel(shape_of(weights, config))
    # The positional encodings are computed here by the same formula, not taken from the weights.
    weights = {name: value for name, value in weights.items() if not name.endswith(".pe")}
    missing, unexpected = model.load_state_dict(weights, strict=False)
    missing = [name for name in missing if not name.endswith("_position")]
    if missing or unexpected:
        raise ValueError(f"The weights do not fit the model: missing {missing}, unexpected {unexpected}")
    return model


def samples(stock: StockModel, points: int, word: int) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
    generator = torch.Generator().manual_seed(points * 1000 + word)
    trajectory = torch.randn(points, TRAJECTORY_FEATURES, generator=generator)
    keys = torch.randint(0, stock.enc_in_emb_model.key_emb.key_emb.num_embeddings, (points,), generator=generator)
    tokens = torch.randint(0, stock.dec_in_emb_model[0].num_embeddings, (word,), generator=generator)
    return trajectory, keys, tokens


def check_close(name: str, actual: torch.Tensor, expected: torch.Tensor) -> None:
    difference = (actual - expected).abs().max().item()
    if difference > TOLERANCE:
        raise ValueError(f"{name} differs from the stock model by {difference}")
    print(f"{name}: largest difference {difference:.2e}")


def report_delegation(lowered, method: str) -> None:
    counts = collections.Counter()
    for node in lowered.exported_program(method).graph.nodes:
        if node.op != "call_function":
            continue
        name = getattr(node.target, "__name__", str(node.target))
        if "getitem" not in name:
            counts[name] += 1
    print(f"{method}: {dict(counts)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--weights")
    parser.add_argument("--config")
    parser.add_argument("--output", required=True)
    parser.add_argument("--random", action="store_true")
    args = parser.parse_args()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    if args.random:
        torch.manual_seed(1)
        stock = StockModel(PROBE_SHAPE)
        (output.parent / "ru.properties").write_text(PROBE_SPEC, encoding="utf-8")
    else:
        stock = load_stock(args.weights, args.config)
    stock.eval()
    encode, decode = Encode(stock).eval(), Decode(stock).eval()

    with torch.no_grad():
        for points, word in ((SAMPLE_POINTS, SAMPLE_WORD), (7, 1), (120, 12)):
            trajectory, keys, tokens = samples(stock, points, word)
            memory = stock.encode(trajectory, keys)
            check_close(f"encoder on {points} points", encode(trajectory, keys), memory)
            check_close(f"decoder on {word} tokens", decode(tokens, memory), stock.decode(tokens, memory))
        trajectory, keys, tokens = samples(stock, SAMPLE_POINTS, SAMPLE_WORD)
        memory = encode(trajectory, keys)

    swipe = Dim("swipe", min=2, max=MAX_POINTS)
    word_length = Dim("word", min=1, max=MAX_WORD - 1)
    programs = {
        "encode": export(encode, (trajectory, keys), dynamic_shapes=({0: swipe}, {0: swipe})),
        "decode": export(decode, (tokens, memory), dynamic_shapes=({0: word_length}, {0: swipe})),
    }
    lowered = to_edge_transform_and_lower(programs, partitioner=[XnnpackPartitioner()])
    report_delegation(lowered, "encode")
    report_delegation(lowered, "decode")
    with open(output, "wb") as file:
        lowered.to_executorch().write_to_file(file)

    program = Runtime.get().load_program(output)
    for points, word in ((SAMPLE_POINTS, SAMPLE_WORD), (7, 1), (120, 12)):
        trajectory, keys, tokens = samples(stock, points, word)
        with torch.no_grad():
            memory = stock.encode(trajectory, keys)
            logits = stock.decode(tokens, memory)
        check_close(f"program encoder on {points}", program.load_method("encode").execute([trajectory, keys])[0], memory)
        check_close(f"program decoder on {word}", program.load_method("decode").execute([tokens, memory])[0], logits)
    print(f"Wrote {output}, {output.stat().st_size} bytes")


if __name__ == "__main__":
    main()
