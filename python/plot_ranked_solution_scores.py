#!/usr/bin/env python3

import argparse
import csv
import os
import tempfile
from pathlib import Path

from ranked_solution_io import (
    DatasetScores,
    IndividualScores,
    read_individuals,
    select_by_min_metric,
)


PLOT_CACHE_DIR = Path(tempfile.gettempdir()) / "shield-python-plot-cache"
PLOT_CACHE_DIR.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("XDG_CACHE_HOME", str(PLOT_CACHE_DIR))
os.environ.setdefault("MPLCONFIGDIR", str(PLOT_CACHE_DIR / "matplotlib"))


SERIES_STYLES = [
    ("#1f77b4", "o"),
    ("#ff7f0e", "s"),
    ("#2ca02c", "^"),
    ("#d62728", "D"),
    ("#9467bd", "P"),
    ("#8c564b", "X"),
    ("#e377c2", "v"),
    ("#17becf", "*"),
]


def scatter_panel(ax, datasets, selected_by_label, y_metric, title):
    for index, dataset in enumerate(datasets):
        color, marker = SERIES_STYLES[index]
        selected = selected_by_label[dataset.label]
        xs = [item.privacy for item in selected]
        ys = [getattr(item, y_metric) for item in selected]
        ax.scatter(
            xs,
            ys,
            label=dataset.label,
            marker=marker,
            color=color,
            s=42,
            alpha=0.78,
            edgecolors="white",
            linewidths=0.35,
        )

    ax.set_title(title)
    ax.set_xlabel("privacy")
    ax.set_ylabel(y_metric)
    ax.set_xlim(-0.02, 1.02)
    ax.set_ylim(-0.02, 1.02)
    ax.grid(True, linewidth=0.4, alpha=0.35)


def plot_points_csv_path(output_path: Path) -> Path:
    return output_path.with_suffix(".csv")


def write_plot_points_csv(
    datasets: list[DatasetScores],
    top_min: dict[str, list[IndividualScores]],
    output_path: Path,
) -> Path:
    csv_path = plot_points_csv_path(output_path)
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", newline="") as file:
        fieldnames = [
            f"{dataset.label}_{metric}"
            for dataset in datasets
            for metric in ("privacy", "semantics", "fidelity")
        ]
        writer = csv.DictWriter(file, fieldnames=fieldnames)
        writer.writeheader()
        row_count = max(len(top_min[dataset.label]) for dataset in datasets)
        for row_index in range(row_count):
            row = {}
            for dataset in datasets:
                if row_index >= len(top_min[dataset.label]):
                    continue
                item = top_min[dataset.label][row_index]
                row[f"{dataset.label}_privacy"] = f"{item.privacy:.12g}"
                row[f"{dataset.label}_semantics"] = f"{item.semantics:.12g}"
                row[f"{dataset.label}_fidelity"] = f"{item.fidelity:.12g}"
            writer.writerow(row)
    return csv_path


def plot_scores(
    datasets: list[DatasetScores],
    limit: int,
    output_path: Path,
    write_csv: bool,
    top_one_per_seed_enabled: bool,
) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    top_min = {
        dataset.label: select_by_min_metric(dataset, limit, top_one_per_seed_enabled)
        for dataset in datasets
    }

    selection_label = f"Top {limit} seed representatives" if top_one_per_seed_enabled else f"Top {limit}"
    figure, axes = plt.subplots(1, 2, figsize=(12, 4.8), sharex=True, sharey=True)
    scatter_panel(
        axes[0],
        datasets,
        top_min,
        "fidelity",
        f"{selection_label} by min metric: privacy vs fidelity",
    )
    scatter_panel(
        axes[1],
        datasets,
        top_min,
        "semantics",
        f"{selection_label} by min metric: privacy vs semantics",
    )

    handles, labels = axes[0].get_legend_handles_labels()
    figure.legend(
        handles,
        labels,
        loc="upper center",
        ncol=min(len(labels), 4),
        bbox_to_anchor=(0.5, 0.995),
        frameon=False,
    )
    figure.tight_layout(rect=(0, 0, 1, 0.95))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output_path, dpi=200)
    plt.close(figure)

    written_csv_path = write_plot_points_csv(datasets, top_min, output_path) if write_csv else None
    for dataset in datasets:
        print(
            f"{dataset.label}: plotted {len(top_min[dataset.label])} by min metric "
            f"from {dataset.csv_path}"
        )
    print(f"Wrote plot to {output_path}")
    if written_csv_path is not None:
        print(f"Wrote plot data to {written_csv_path}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Compare ranked unique-solution CSVs using privacy/fidelity and "
            "privacy/semantics scatter plots for the top min-metric solutions."
        )
    )
    parser.add_argument(
        "--csvs",
        nargs="+",
        type=Path,
        required=True,
        help="Input CSV files, typically individuals/unique_solutions.csv.",
    )
    parser.add_argument(
        "--ids",
        nargs="+",
        required=True,
        help="Legend IDs, one per CSV file.",
    )
    parser.add_argument(
        "-i",
        "--individuals",
        type=int,
        required=True,
        help="Number of best min-metric individuals to plot from each CSV.",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        required=True,
        help="Output image path, for example outputs/comparison.png or .svg.",
    )
    parser.add_argument(
        "--write-csv",
        action="store_true",
        help="Also write the plotted data points next to the output using a .csv extension.",
    )
    parser.add_argument(
        "--top-one-per-seed",
        action="store_true",
        help=(
            "Select the best individual from each seed first, then plot the top -i "
            "seed representatives."
        ),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if len(args.csvs) != len(args.ids):
        raise ValueError("--csvs and --ids must have the same number of values")
    if len(args.csvs) > len(SERIES_STYLES):
        raise ValueError(f"At most {len(SERIES_STYLES)} CSV files are supported")
    if args.individuals <= 0:
        raise ValueError("--individuals must be positive")

    datasets = [
        DatasetScores(label=label, csv_path=csv_path, individuals=read_individuals(csv_path))
        for csv_path, label in zip(args.csvs, args.ids)
    ]
    plot_scores(datasets, args.individuals, args.output, args.write_csv, args.top_one_per_seed)


if __name__ == "__main__":
    main()
