#!/usr/bin/env python3
"""Checks the latest benchmark results against the performance budgets.

Reads the JSON that Macrobenchmark leaves in benchmark/build/outputs and fails when the median of
a metric exceeds its budget. Budgets apply to the run with the Baseline Profile.
"""

import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESULTS = ROOT / "benchmark/build/outputs/connected_android_test_additional_output"

# Test name -> metric -> budget in milliseconds. Set from a Pixel 11 Pro with a margin of 2-3 times
# over its results, so that a budget catches a regression and not the noise of a single run. A frame
# has 16 ms at 60 Hz, and a slower phone needs its own numbers.
BUDGETS: dict[str, dict[str, float]] = {
    "swiping[profile]": {
        "kbd.decode.final.averageAverageMs": 12,
        "kbd.decode.final.maxMaxMs": 30,
        "kbd.decode.live.averageAverageMs": 15,
        "kbd.decode.live.maxMaxMs": 45,
        "kbd.draw.averageAverageMs": 2,
        "kbd.draw.maxMaxMs": 8,
        "frameDurationCpuMs.P95": 16,
    },
    "typing[profile]": {
        "kbd.key.averageAverageMs": 2,
        "kbd.key.maxMaxMs": 5,
        "kbd.draw.averageAverageMs": 2,
        "kbd.draw.maxMaxMs": 8,
        "frameDurationCpuMs.P95": 16,
    },
    "coldOpen[profile]": {
        "kbd.dict.load.maxMaxMs": 15,
        "kbd.createView.maxMaxMs": 8,
        "kbd.startInput.maxMaxMs": 2,
    },
}


def latest_results() -> dict:
    files = sorted(RESULTS.rglob("*benchmarkData.json"), key=lambda path: path.stat().st_mtime)
    if not files:
        raise SystemExit(f"No benchmark results under {RESULTS}: run `just bench`.")
    return json.loads(files[-1].read_text(encoding="utf-8"))


def measured(benchmark: dict) -> dict[str, float]:
    """The median of each metric, and the 50th and 95th percentiles of each per-frame metric."""
    values = {metric: result["median"] for metric, result in benchmark["metrics"].items()}
    for metric, result in benchmark.get("sampledMetrics", {}).items():
        values[f"{metric}.P50"] = result["P50"]
        values[f"{metric}.P95"] = result["P95"]
    return values


def main() -> int:
    failures = 0
    checked = 0
    for benchmark in latest_results()["benchmarks"]:
        name = benchmark["name"]
        budgets = BUDGETS.get(name, {})
        values = measured(benchmark)
        for metric in budgets.keys() - values.keys():
            print(f"{name:32} {metric:36} NOT MEASURED")
            failures += 1
        for metric, value in sorted(values.items()):
            budget = budgets.get(metric)
            verdict = ""
            if budget is not None:
                checked += 1
                verdict = "  ok" if value <= budget else f"  OVER BUDGET {budget}"
                failures += value > budget
            print(f"{name:32} {metric:36} {value:9.2f}{verdict}")
    print("Budgets exceeded." if failures else f"Within budgets: {checked} checked.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
