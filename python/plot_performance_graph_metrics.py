#!/usr/bin/env python3

import argparse
import csv
from pathlib import Path

from plot_performance_summary import EXPERIMENT_LABELS, EXPERIMENT_ORDER, read_plot_rows


METRICS = [
    ("total_operators", "Total operators", "count"),
    ("stateful_operators", "Stateful operators", "count"),
    ("branches_or_joins", "Branches/joins", "count"),
    ("output_input_ratio", "Output/input ratio", "ratio"),
]


def write_plot_csv(rows: list[dict[str, str]], output_pdf: Path) -> Path:
    csv_path = output_pdf.with_suffix(".csv")
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return csv_path


def metric_values(rows: list[dict[str, str]], metric: str, experiment: str) -> list[float]:
    return [
        float(row[metric])
        for row in rows
        if row["experiment"] == experiment and row.get(metric, "")
    ]


def plot(rows: list[dict[str, str]], output_pdf: Path, title: str | None) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    missing = [metric for metric, _, _ in METRICS if metric not in rows[0]]
    if missing:
        raise ValueError(
            "Missing graph metric column(s): "
            + ", ".join(missing)
            + ". Recreate index_enriched.csv first."
        )

    labels = [EXPERIMENT_LABELS[experiment] for experiment in EXPERIMENT_ORDER]
    colors = ["#4c78a8", "#f58518", "#54a24b", "#b279a2"]
    fig, axes = plt.subplots(2, 2, figsize=(10, 7))
    if title:
        fig.suptitle(title)

    for ax, (metric, metric_title, ylabel) in zip(axes.flat, METRICS):
        values = [metric_values(rows, metric, experiment) for experiment in EXPERIMENT_ORDER]
        boxes = ax.boxplot(values, tick_labels=labels, patch_artist=True, showfliers=True)
        for patch, color in zip(boxes["boxes"], colors):
            patch.set_facecolor(color)
            patch.set_alpha(0.55)
        ax.set_title(metric_title)
        ax.set_ylabel(ylabel)
        ax.grid(axis="y", linewidth=0.4, alpha=0.35)
        ax.tick_params(axis="x", labelsize=8)

    output_pdf.parent.mkdir(parents=True, exist_ok=True)
    fig.tight_layout()
    fig.savefig(output_pdf)
    plt.close(fig)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Plot graph-shape diagnostics from an enriched stepped benchmark CSV."
    )
    parser.add_argument("steps_csv", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--title")
    parser.add_argument(
        "--selection",
        choices=["latency-filtered", "rightmost", "max-throughput"],
        default="max-throughput",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    rows = read_plot_rows(args.steps_csv, args.selection)
    plot(rows, args.output, args.title)
    csv_path = write_plot_csv(rows, args.output)
    print(f"Wrote {args.output}")
    print(f"Wrote {csv_path}")


if __name__ == "__main__":
    main()
