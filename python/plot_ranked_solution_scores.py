#!/usr/bin/env python3

import argparse
import csv
import os
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path


PLOT_CACHE_DIR = Path(tempfile.gettempdir()) / "shield-python-plot-cache"
PLOT_CACHE_DIR.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("XDG_CACHE_HOME", str(PLOT_CACHE_DIR))
os.environ.setdefault("MPLCONFIGDIR", str(PLOT_CACHE_DIR / "matplotlib"))


REQUIRED_COLUMNS = {
    "rank",
    "seed",
    "min_privacy_semantics_fidelity",
    "distance_from_perfect",
    "privacy",
    "semantics",
    "fidelity",
    "source_group",
    "source_row",
    "image",
    "individual",
}

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


@dataclass(frozen=True)
class IndividualScores:
    rank: int
    seed: str
    min_metric: float
    distance: float
    privacy: float
    semantics: float
    fidelity: float


@dataclass(frozen=True)
class DatasetScores:
    label: str
    csv_path: Path
    individuals: list[IndividualScores]


def sniff_dialect(csv_path: Path) -> csv.Dialect:
    with csv_path.open(newline="") as file:
        sample = file.read(8192)
    try:
        return csv.Sniffer().sniff(sample, delimiters=",;\t")
    except csv.Error:
        return csv.get_dialect("excel")


def raise_csv_field_size_limit() -> None:
    limit = sys.maxsize
    while True:
        try:
            csv.field_size_limit(limit)
            return
        except OverflowError:
            limit //= 10


def required_float(row: dict[str, str], column: str, csv_path: Path) -> float:
    value = row.get(column, "").strip()
    try:
        return float(value)
    except ValueError as error:
        raise ValueError(f"Invalid numeric value for column '{column}' in {csv_path}: {value!r}") from error


def required_int(row: dict[str, str], column: str, csv_path: Path) -> int:
    value = row.get(column, "").strip()
    try:
        return int(value)
    except ValueError as error:
        raise ValueError(f"Invalid integer value for column '{column}' in {csv_path}: {value!r}") from error


def read_individuals(csv_path: Path) -> list[IndividualScores]:
    raise_csv_field_size_limit()
    dialect = sniff_dialect(csv_path)
    individuals = []
    with csv_path.open(newline="") as file:
        reader = csv.DictReader(file, dialect=dialect)
        fieldnames = {name.strip() for name in reader.fieldnames or []}
        missing_columns = sorted(REQUIRED_COLUMNS - fieldnames)
        if missing_columns:
            raise ValueError(f"{csv_path} is missing required columns: {', '.join(missing_columns)}")

        for raw_row in reader:
            row = {
                key.strip(): (value or "").strip()
                for key, value in raw_row.items()
                if key is not None
            }
            individuals.append(
                IndividualScores(
                    rank=required_int(row, "rank", csv_path),
                    seed=row["seed"],
                    min_metric=required_float(row, "min_privacy_semantics_fidelity", csv_path),
                    distance=required_float(row, "distance_from_perfect", csv_path),
                    privacy=required_float(row, "privacy", csv_path),
                    semantics=required_float(row, "semantics", csv_path),
                    fidelity=required_float(row, "fidelity", csv_path),
                )
            )
    return individuals


def top_by_min_metric(individuals: list[IndividualScores], limit: int) -> list[IndividualScores]:
    return sorted(
        individuals,
        key=lambda item: (
            -item.min_metric,
            item.distance,
            -item.privacy,
            -item.semantics,
            -item.fidelity,
            item.rank,
        ),
    )[:limit]


def top_by_distance(individuals: list[IndividualScores], limit: int) -> list[IndividualScores]:
    return sorted(
        individuals,
        key=lambda item: (
            item.distance,
            -item.min_metric,
            -item.privacy,
            -item.semantics,
            -item.fidelity,
            item.rank,
        ),
    )[:limit]


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


def plot_scores(datasets: list[DatasetScores], limit: int, output_path: Path) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    top_min = {
        dataset.label: top_by_min_metric(dataset.individuals, limit)
        for dataset in datasets
    }
    top_distance = {
        dataset.label: top_by_distance(dataset.individuals, limit)
        for dataset in datasets
    }

    figure, axes = plt.subplots(2, 2, figsize=(12, 9), sharex=True, sharey=True)
    scatter_panel(
        axes[0, 0],
        datasets,
        top_min,
        "fidelity",
        f"Top {limit} by min metric: privacy vs fidelity",
    )
    scatter_panel(
        axes[0, 1],
        datasets,
        top_min,
        "semantics",
        f"Top {limit} by min metric: privacy vs semantics",
    )
    scatter_panel(
        axes[1, 0],
        datasets,
        top_distance,
        "fidelity",
        f"Top {limit} by distance: privacy vs fidelity",
    )
    scatter_panel(
        axes[1, 1],
        datasets,
        top_distance,
        "semantics",
        f"Top {limit} by distance: privacy vs semantics",
    )

    handles, labels = axes[0, 0].get_legend_handles_labels()
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

    for dataset in datasets:
        print(
            f"{dataset.label}: plotted {len(top_min[dataset.label])} by min metric "
            f"and {len(top_distance[dataset.label])} by distance from {dataset.csv_path}"
        )
    print(f"Wrote plot to {output_path}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Compare ranked unique-solution CSVs using privacy/fidelity and "
            "privacy/semantics scatter plots."
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
        help="Number of best individuals to plot from each CSV in each ranking.",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        required=True,
        help="Output image path, for example outputs/comparison.png or .svg.",
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
    plot_scores(datasets, args.individuals, args.output)


if __name__ == "__main__":
    main()
