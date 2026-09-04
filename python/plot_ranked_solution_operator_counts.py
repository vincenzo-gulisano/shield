#!/usr/bin/env python3

import argparse
import csv
import os
import tempfile
from collections import Counter
from pathlib import Path

from ranked_solution_io import (
    DatasetScores as Dataset,
    IndividualScores as Individual,
    read_individuals,
    top_by_min_metric,
)
from solution_graph_metrics import operator_types as solution_operator_types


PLOT_CACHE_DIR = Path(tempfile.gettempdir()) / "shield-python-plot-cache"
PLOT_CACHE_DIR.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("XDG_CACHE_HOME", str(PLOT_CACHE_DIR))
os.environ.setdefault("MPLCONFIGDIR", str(PLOT_CACHE_DIR / "matplotlib"))


OPERATOR_GROUPS = [
    (
        "shield1",
        [
            "FilterOperator",
            "MapDuplicate",
            "MapNoise",
            "MapAggregate",
        ],
    ),
    (
        "dag",
        [
            "ConditionalFork",
            "Fork",
            "MapRIR",
            "MapTimestampPairwiseSwap",
            "MapTimestampGroupShuffle",
        ],
    ),
    (
        "provenance",
        [
            "FilterQueryCondition",
            "QueryConditionFork",
            "MapConditionPreservingNoise",
            "MapConditionPreservingRIR",
            "MapConditionPairwiseSwap",
            "MapConditionPartitionShuffle",
        ],
    ),
]
OPERATOR_ORDER = [operator for _, operators in OPERATOR_GROUPS for operator in operators]

SERIES_COLORS = [
    "#1f77b4",
    "#ff7f0e",
    "#2ca02c",
    "#d62728",
    "#9467bd",
    "#8c564b",
    "#e377c2",
    "#17becf",
]


def operator_types(individual: Individual) -> list[str]:
    operators = solution_operator_types(individual.individual)
    unexpected = sorted(set(operators) - set(OPERATOR_ORDER))
    if unexpected:
        raise ValueError(
            f"Unexpected operator type(s) in rank {individual.rank}, seed "
            f"{individual.seed}: {', '.join(unexpected)}"
        )
    return operators


def operator_counts(dataset: Dataset, limit: int) -> Counter[str]:
    counts: Counter[str] = Counter()
    for individual in top_by_min_metric(dataset.individuals, limit):
        counts.update(operator_types(individual))
    return counts


def operator_order(counts_by_label: dict[str, Counter[str]]) -> list[str]:
    totals = Counter()
    for counts in counts_by_label.values():
        totals.update(counts)
    unexpected = sorted(set(totals) - set(OPERATOR_ORDER))
    if unexpected:
        raise ValueError(f"Unexpected operator type(s): {', '.join(unexpected)}")
    return [operator for operator in OPERATOR_ORDER if operator in totals]


def plot_counts_csv_path(output_path: Path) -> Path:
    return output_path.with_suffix(".csv")


def write_counts_csv(
    datasets: list[Dataset],
    limit: int,
    counts_by_label: dict[str, Counter[str]],
    operators: list[str],
    output_path: Path,
) -> Path:
    csv_path = plot_counts_csv_path(output_path)
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", newline="") as file:
        fieldnames = ["id"] + operators
        writer = csv.DictWriter(file, fieldnames=fieldnames)
        writer.writeheader()
        for dataset in datasets:
            writer.writerow(
                {"id": dataset.label}
                | {
                    operator: counts_by_label[dataset.label].get(operator, 0)
                    for operator in operators
                }
            )
    return csv_path


def plot_counts(datasets: list[Dataset], limit: int, output_path: Path, write_csv: bool) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    counts_by_label = {
        dataset.label: operator_counts(dataset, limit)
        for dataset in datasets
    }
    operators = operator_order(counts_by_label)
    if not operators:
        raise ValueError("No operator types found in the selected individuals")

    n_datasets = len(datasets)
    group_width = 0.82
    bar_width = group_width / n_datasets
    x_positions = list(range(len(operators)))

    figure_width = max(10.0, 0.75 * len(operators) + 1.25 * n_datasets)
    figure, ax = plt.subplots(figsize=(figure_width, 6.5))

    for dataset_index, dataset in enumerate(datasets):
        offset = (dataset_index - (n_datasets - 1) / 2.0) * bar_width
        heights = [counts_by_label[dataset.label].get(operator, 0) for operator in operators]
        ax.bar(
            [x + offset for x in x_positions],
            heights,
            width=bar_width,
            label=dataset.label,
            color=SERIES_COLORS[dataset_index],
            edgecolor="white",
            linewidth=0.5,
        )

    ax.set_title(f"Operator counts in top {limit} solutions by min metric")
    ax.set_xlabel("operator type")
    ax.set_ylabel("count across selected solutions")
    ax.set_xticks(x_positions)
    ax.set_xticklabels(operators, rotation=40, ha="right")
    ax.grid(axis="y", linewidth=0.4, alpha=0.35)
    ax.legend(loc="upper right", frameon=False, ncol=min(n_datasets, 4))

    figure.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output_path, dpi=200)
    plt.close(figure)

    written_csv_path = write_counts_csv(datasets, limit, counts_by_label, operators, output_path) if write_csv else None
    for dataset in datasets:
        selected = top_by_min_metric(dataset.individuals, limit)
        counts_text = ", ".join(
            f"{operator}={counts_by_label[dataset.label].get(operator, 0)}"
            for operator in operators
            if counts_by_label[dataset.label].get(operator, 0) > 0
        )
        print(f"{dataset.label}: counted {len(selected)} individuals from {dataset.csv_path}")
        print(f"  {counts_text}")
    print(f"Wrote plot to {output_path}")
    if written_csv_path is not None:
        print(f"Wrote plot data to {written_csv_path}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Compare ranked unique-solution CSVs by counting operator types in "
            "the top individuals and plotting grouped bars."
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
        help="Number of best individuals to count from each CSV.",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        required=True,
        help="Output image path, for example outputs/operator_counts.png or .svg.",
    )
    parser.add_argument(
        "--write-csv",
        action="store_true",
        help="Also write the plotted operator counts next to the output using a .csv extension.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if len(args.csvs) != len(args.ids):
        raise ValueError("--csvs and --ids must have the same number of values")
    if len(args.csvs) > len(SERIES_COLORS):
        raise ValueError(f"At most {len(SERIES_COLORS)} CSV files are supported")
    if args.individuals <= 0:
        raise ValueError("--individuals must be positive")

    datasets = [
        Dataset(label=label, csv_path=csv_path, individuals=read_individuals(csv_path))
        for csv_path, label in zip(args.csvs, args.ids)
    ]
    plot_counts(datasets, args.individuals, args.output, args.write_csv)


if __name__ == "__main__":
    main()
