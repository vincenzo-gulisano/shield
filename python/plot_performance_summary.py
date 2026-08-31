#!/usr/bin/env python3

import argparse
import csv
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
EXPERIMENT_ORDER = ["01", "02", "04", "08"]
EXPERIMENT_LABELS = {
    "01": "01\nShield 1\nold privacy",
    "02": "02\nShield 1\nnew privacy",
    "04": "04\nDAG\nnew privacy",
    "08": "08\nDAG + prov\nnew privacy",
}


def read_plot_rows(index_csv: Path) -> list[dict[str, str]]:
    rows = []
    with index_csv.open(newline="") as file:
        for row in csv.DictReader(file):
            if row["status"] != "ok":
                continue
            experiment = ID_TO_EXPERIMENT.get(row["id"], row["id"])
            rows.append(
                {
                    "experiment": experiment,
                    "id": row["id"],
                    "run": row["run"],
                    "ranking_mode": row["ranking_mode"],
                    "selection_rank": row["selection_rank"],
                    "seed": row["seed"],
                    "repetition": row["repetition"],
                    "input_throughput_per_s": row["input_throughput_per_s"],
                    "avg_latency_ms": row["avg_latency_ms"],
                }
            )
    return rows


def write_summary_csv(rows: list[dict[str, str]], output_pdf: Path) -> Path:
    csv_path = output_pdf.with_suffix(".csv")
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return csv_path


def plot(rows: list[dict[str, str]], output_pdf: Path, title: str | None) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    labels = [EXPERIMENT_LABELS[experiment] for experiment in EXPERIMENT_ORDER]
    throughput = [
        [float(row["input_throughput_per_s"]) for row in rows if row["experiment"] == experiment]
        for experiment in EXPERIMENT_ORDER
    ]
    latency = [
        [float(row["avg_latency_ms"]) for row in rows if row["experiment"] == experiment]
        for experiment in EXPERIMENT_ORDER
    ]
    colors = ["#4c78a8", "#f58518", "#54a24b", "#b279a2"]

    fig, axes = plt.subplots(1, 2, figsize=(10, 4.2))
    if title:
        fig.suptitle(title)

    boxes = axes[0].boxplot(throughput, tick_labels=labels, patch_artist=True, showfliers=True)
    for patch, color in zip(boxes["boxes"], colors):
        patch.set_facecolor(color)
        patch.set_alpha(0.55)
    axes[0].set_title("Input throughput")
    axes[0].set_ylabel("tuples/s")

    boxes = axes[1].boxplot(latency, tick_labels=labels, patch_artist=True, showfliers=True)
    for patch, color in zip(boxes["boxes"], colors):
        patch.set_facecolor(color)
        patch.set_alpha(0.55)
    axes[1].set_title("Latency")
    axes[1].set_ylabel("ms")

    for ax in axes:
        ax.grid(axis="y", linewidth=0.4, alpha=0.35)
        ax.tick_params(axis="x", labelsize=9)

    output_pdf.parent.mkdir(parents=True, exist_ok=True)
    fig.tight_layout()
    fig.savefig(output_pdf)
    plt.close(fig)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Plot average input throughput and latency from a benchmark index.csv."
    )
    parser.add_argument("index_csv", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--title")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    rows = read_plot_rows(args.index_csv)
    plot(rows, args.output, args.title)
    csv_path = write_summary_csv(rows, args.output)
    print(f"Wrote {args.output}")
    print(f"Wrote {csv_path}")


if __name__ == "__main__":
    main()
