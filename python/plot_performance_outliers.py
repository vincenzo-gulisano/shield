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

ID_TO_EXPERIMENT = {
    "shield1-oldPriv": "01",
    "shield1-newPriv": "02",
    "shield2": "04",
    "shield2_prov": "08",
}


def read_index(index_csv: Path, limit: int) -> list[dict[str, str]]:
    with index_csv.open(newline="") as file:
        rows = [row for row in csv.DictReader(file) if row["status"] == "ok"]
    rows.sort(key=lambda row: float(row["avg_latency_ms"]), reverse=True)
    return rows[:limit]


def resolve_per_second_path(index_csv: Path, path_text: str) -> Path:
    path = Path(path_text)
    candidates = [
        path,
        Path.cwd() / path,
        index_csv.parent / path,
        index_csv.parent / path.name,
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return candidates[-1]


def read_series(path: Path) -> list[dict[str, str]]:
    with path.open(newline="") as file:
        return list(csv.DictReader(file))


def write_plot_csv(rows: list[dict[str, str]], output_pdf: Path) -> Path:
    csv_path = output_pdf.with_suffix(".csv")
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "outlier_rank",
        "run",
        "experiment",
        "id",
        "ranking_mode",
        "selection_rank",
        "seed",
        "repetition",
        "graph_hash",
        "summary_input_throughput_per_s",
        "summary_output_throughput_per_s",
        "summary_avg_latency_ms",
        "second",
        "input_count",
        "output_count",
        "avg_latency_ms",
        "min_latency_ms",
        "max_latency_ms",
        "per_second_csv",
    ]
    with csv_path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    return csv_path


def plot_outliers(index_csv: Path, output_pdf: Path, limit: int) -> list[dict[str, str]]:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    selected = read_index(index_csv, limit)
    figure, axes = plt.subplots(len(selected), 2, figsize=(11, 3.4 * len(selected)), squeeze=False)
    plot_rows = []

    for outlier_rank, row in enumerate(selected, start=1):
        per_second_path = resolve_per_second_path(index_csv, row["per_second_csv"])
        series = read_series(per_second_path)
        seconds = [int(item["second"]) for item in series]
        inputs = [int(item["input_count"]) for item in series]
        outputs = [int(item["output_count"]) for item in series]
        latencies = [
            float(item["avg_latency_ms"]) if item["avg_latency_ms"] else math.nan
            for item in series
        ]

        experiment = ID_TO_EXPERIMENT.get(row["id"], row["id"])
        title = (
            f"#{outlier_rank} exp {experiment}, {row['ranking_mode']} r{row['selection_rank']} "
            f"rep {row['repetition']}, avg latency {float(row['avg_latency_ms']):.1f} ms"
        )

        throughput_ax = axes[outlier_rank - 1][0]
        latency_ax = axes[outlier_rank - 1][1]
        throughput_ax.plot(seconds, inputs, label="input", linewidth=1.2)
        throughput_ax.plot(seconds, outputs, label="output", linewidth=1.2)
        throughput_ax.set_title(title)
        throughput_ax.set_ylabel("tuples/s")
        throughput_ax.legend()

        latency_ax.plot(seconds, latencies, color="#d62728", linewidth=1.2)
        latency_ax.set_title("latency")
        latency_ax.set_ylabel("ms")

        for ax in (throughput_ax, latency_ax):
            ax.set_xlabel("second")
            ax.grid(True, linewidth=0.4, alpha=0.35)
            mark_window(ax, row, seconds)

        for item in series:
            plot_rows.append(
                {
                    "outlier_rank": str(outlier_rank),
                    "run": row["run"],
                    "experiment": experiment,
                    "id": row["id"],
                    "ranking_mode": row["ranking_mode"],
                    "selection_rank": row["selection_rank"],
                    "seed": row["seed"],
                    "repetition": row["repetition"],
                    "graph_hash": row["graph_hash"],
                    "summary_input_throughput_per_s": row["input_throughput_per_s"],
                    "summary_output_throughput_per_s": row["output_throughput_per_s"],
                    "summary_avg_latency_ms": row["avg_latency_ms"],
                    "second": item["second"],
                    "input_count": item["input_count"],
                    "output_count": item["output_count"],
                    "avg_latency_ms": item["avg_latency_ms"],
                    "min_latency_ms": item["min_latency_ms"],
                    "max_latency_ms": item["max_latency_ms"],
                    "per_second_csv": str(per_second_path),
                }
            )

    output_pdf.parent.mkdir(parents=True, exist_ok=True)
    figure.tight_layout()
    figure.savefig(output_pdf)
    plt.close(figure)
    return plot_rows


def mark_window(ax, row: dict[str, str], seconds: list[int]) -> None:
    if not seconds:
        return
    warm_up = int(row.get("warm_up_millis", "0") or "0") / 1000.0
    cool_down = int(row.get("cool_down_millis", "0") or "0") / 1000.0
    if warm_up > 0:
        ax.axvline(warm_up, color="black", linestyle=":", linewidth=0.8)
    if cool_down > 0:
        ax.axvline(max(seconds) + 1 - cool_down, color="black", linestyle=":", linewidth=0.8)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Plot per-second throughput and latency for worst-latency benchmark runs."
    )
    parser.add_argument("index_csv", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("-n", "--outliers", type=int, default=3)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    rows = plot_outliers(args.index_csv, args.output, args.outliers)
    csv_path = write_plot_csv(rows, args.output)
    print(f"Wrote {args.output}")
    print(f"Wrote {csv_path}")


if __name__ == "__main__":
    main()
