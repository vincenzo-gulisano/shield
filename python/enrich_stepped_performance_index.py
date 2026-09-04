#!/usr/bin/env python3

import argparse
import csv
import math
import os
import tempfile
from pathlib import Path


PLOT_CACHE_DIR = Path(tempfile.gettempdir()) / "shield-python-plot-cache"
PLOT_CACHE_DIR.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("XDG_CACHE_HOME", str(PLOT_CACHE_DIR))
os.environ.setdefault("MPLCONFIGDIR", str(PLOT_CACHE_DIR / "matplotlib"))

FIELDNAMES = [
    "run",
    "id",
    "ranking_mode",
    "selection_rank",
    "seed",
    "repetition",
    "graph_hash",
    "step",
    "target_rate_per_s",
    "start_second",
    "end_second",
    "crosses_warmup",
    "crosses_cooldown",
    "input_count",
    "output_count",
    "input_throughput_per_s",
    "output_throughput_per_s",
    "output_input_ratio",
    "avg_latency_ms",
    "min_latency_ms",
    "max_latency_ms",
    "max_latency_minus_avg_latency_ms",
    "latency_slope_ms_per_s",
    "per_second_csv",
]


def resolve_per_second_path(index_csv: Path, path_text: str) -> Path:
    path = Path(path_text)
    for candidate in (path, Path.cwd() / path, index_csv.parent / path, index_csv.parent / path.name):
        if candidate.exists():
            return candidate
    return index_csv.parent / path.name


def read_series(path: Path) -> list[dict[str, str]]:
    with path.open(newline="") as file:
        return list(csv.DictReader(file))


def latency_slope(rows: list[dict[str, str]]) -> str:
    points = [(int(row["second"]), float(row["avg_latency_ms"])) for row in rows if row["avg_latency_ms"]]
    if len(points) < 2:
        return ""
    mean_x = sum(x for x, _ in points) / len(points)
    mean_y = sum(y for _, y in points) / len(points)
    den = sum((x - mean_x) ** 2 for x, _ in points)
    if den == 0.0:
        return ""
    slope = sum((x - mean_x) * (y - mean_y) for x, y in points) / den
    return f"{slope:.6f}"


def target_rate(index_row: dict[str, str], step_index: int) -> float:
    start_rate = float(index_row["rate_start_per_s"])
    final_rate = float(index_row["rate_final_per_s"])
    steps = int(index_row["rate_steps"])
    if steps == 1:
        return start_rate
    return start_rate + (final_rate - start_rate) * step_index / (steps - 1)


def step_row(index_row: dict[str, str], series: list[dict[str, str]], step_index: int, path: Path) -> dict[str, str]:
    step_millis = int(index_row["rate_step_millis"])
    start_millis = step_index * step_millis
    end_millis = start_millis + step_millis
    start_second = start_millis // 1000
    end_second = math.ceil(end_millis / 1000)
    rows = [row for row in series if start_second <= int(row["second"]) < end_second]

    input_count = sum(int(row["input_count"]) for row in rows)
    output_count = sum(int(row["output_count"]) for row in rows)
    duration_seconds = (end_millis - start_millis) / 1000.0
    latency_rows = [row for row in rows if row["avg_latency_ms"] and int(row["output_count"]) > 0]
    latency_weight = sum(int(row["output_count"]) for row in latency_rows)
    avg_latency = (
        sum(float(row["avg_latency_ms"]) * int(row["output_count"]) for row in latency_rows) / latency_weight
        if latency_weight
        else None
    )
    min_latency = min((float(row["min_latency_ms"]) for row in latency_rows if row["min_latency_ms"]), default=None)
    max_latency = max((float(row["max_latency_ms"]) for row in latency_rows if row["max_latency_ms"]), default=None)

    warmup_millis = int(index_row["warm_up_millis"])
    cooldown_start_millis = int(index_row["rate_steps"]) * step_millis - int(index_row["cool_down_millis"])
    return {
        "run": index_row["run"],
        "id": index_row["id"],
        "ranking_mode": index_row["ranking_mode"],
        "selection_rank": index_row["selection_rank"],
        "seed": index_row["seed"],
        "repetition": index_row["repetition"],
        "graph_hash": index_row["graph_hash"],
        "step": str(step_index + 1),
        "target_rate_per_s": f"{target_rate(index_row, step_index):.6f}",
        "start_second": str(start_second),
        "end_second": str(end_second),
        "crosses_warmup": str(start_millis < warmup_millis and end_millis > 0).lower(),
        "crosses_cooldown": str(start_millis < int(index_row["rate_steps"]) * step_millis and end_millis > cooldown_start_millis).lower(),
        "input_count": str(input_count),
        "output_count": str(output_count),
        "input_throughput_per_s": f"{input_count / duration_seconds:.6f}",
        "output_throughput_per_s": f"{output_count / duration_seconds:.6f}",
        "output_input_ratio": "" if input_count == 0 else f"{output_count / input_count:.6f}",
        "avg_latency_ms": "" if avg_latency is None else f"{avg_latency:.6f}",
        "min_latency_ms": "" if min_latency is None else f"{min_latency:.6f}",
        "max_latency_ms": "" if max_latency is None else f"{max_latency:.6f}",
        "max_latency_minus_avg_latency_ms": ""
        if avg_latency is None or max_latency is None
        else f"{max_latency - avg_latency:.6f}",
        "latency_slope_ms_per_s": latency_slope(rows),
        "per_second_csv": str(path),
    }


def plot_run(index_row: dict[str, str], series: list[dict[str, str]], step_rows: list[dict[str, str]], output_dir: Path) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    output_dir.mkdir(parents=True, exist_ok=True)
    seconds = [int(row["second"]) for row in series]
    inputs = [int(row["input_count"]) for row in series]
    outputs = [int(row["output_count"]) for row in series]
    latencies = [float(row["avg_latency_ms"]) if row["avg_latency_ms"] else math.nan for row in series]

    fig, axes = plt.subplots(1, 2, figsize=(11, 3.8))
    title = (
        f"run {index_row['run']} - {index_row['id']} - "
        f"{index_row['ranking_mode']} r{index_row['selection_rank']} rep {index_row['repetition']}"
    )
    fig.suptitle(title)

    axes[0].plot(seconds, inputs, label="input", linewidth=1.0)
    axes[0].plot(seconds, outputs, label="output", linewidth=1.0)
    axes[0].set_title("Throughput")
    axes[0].set_ylabel("tuples/s")
    axes[0].legend()

    axes[1].plot(seconds, latencies, color="#d62728", linewidth=1.0)
    axes[1].set_title("Latency")
    axes[1].set_ylabel("ms")

    for step in step_rows:
        start = int(step["start_second"])
        end = int(step["end_second"])
        for ax in axes:
            ax.axvline(start, color="black", linestyle=":", linewidth=0.6, alpha=0.55)
        axes[0].hlines(float(step["input_throughput_per_s"]), start, end, colors="#1f77b4", linestyles="--", linewidth=0.8)
        axes[0].hlines(float(step["output_throughput_per_s"]), start, end, colors="#ff7f0e", linestyles="--", linewidth=0.8)
        if step["avg_latency_ms"]:
            axes[1].hlines(float(step["avg_latency_ms"]), start, end, colors="#d62728", linestyles="--", linewidth=0.8)
    for ax in axes:
        ax.axvline(int(index_row["rate_steps"]) * int(index_row["rate_step_millis"]) / 1000.0,
                   color="black", linestyle=":", linewidth=0.6, alpha=0.55)
        ax.set_xlabel("second")
        ax.grid(True, linewidth=0.4, alpha=0.35)

    fig.tight_layout()
    output_path = output_dir / f"run-{int(index_row['run']):04d}-{clean_file_part(index_row['id'])}-r{index_row['selection_rank']}-rep{int(index_row['repetition']):02d}.pdf"
    fig.savefig(output_path)
    plt.close(fig)


def clean_file_part(value: str) -> str:
    cleaned = "".join(char if char.isalnum() or char in "_.-" else "_" for char in value)
    return cleaned or "x"


def enrich(index_csv: Path, plot_dir: Path) -> list[dict[str, str]]:
    enriched = []
    with index_csv.open(newline="") as file:
        for index_row in csv.DictReader(file):
            if index_row["status"] != "ok" or index_row.get("rate_mode") != "stepped":
                continue
            path = resolve_per_second_path(index_csv, index_row["per_second_csv"])
            series = read_series(path)
            run_steps = []
            for step_index in range(int(index_row["rate_steps"])):
                row = step_row(index_row, series, step_index, path)
                run_steps.append(row)
                enriched.append(row)
            plot_run(index_row, series, run_steps, plot_dir)
    return enriched


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Create one per-step row from a stepped benchmark index.csv.")
    parser.add_argument("index_csv", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    rows = enrich(args.index_csv, args.output.parent / f"{args.output.stem}_individuals")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=FIELDNAMES)
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
