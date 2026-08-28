#!/usr/bin/env python3

import argparse
import csv
import os
import re
import sys
import tempfile
from collections import Counter
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

STRUCTURAL_NODES = {"Source", "Sink", "Union"}
OPERATOR_RE = re.compile(r"\b([A-Za-z][A-Za-z0-9]*)\[id=")

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


@dataclass(frozen=True)
class Individual:
    rank: int
    seed: str
    min_metric: float
    distance: float
    privacy: float
    semantics: float
    fidelity: float
    individual: str


@dataclass(frozen=True)
class Dataset:
    label: str
    csv_path: Path
    individuals: list[Individual]


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


def read_individuals(csv_path: Path) -> list[Individual]:
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
                Individual(
                    rank=required_int(row, "rank", csv_path),
                    seed=row["seed"],
                    min_metric=required_float(row, "min_privacy_semantics_fidelity", csv_path),
                    distance=required_float(row, "distance_from_perfect", csv_path),
                    privacy=required_float(row, "privacy", csv_path),
                    semantics=required_float(row, "semantics", csv_path),
                    fidelity=required_float(row, "fidelity", csv_path),
                    individual=row["individual"],
                )
            )
    return individuals


def top_by_min_metric(individuals: list[Individual], limit: int) -> list[Individual]:
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


def nodes_section(individual: str) -> str:
    match = re.search(r"nodes=\[(.*?)]\s*, arcs=", individual)
    return match.group(1) if match else individual


def operator_types(individual: Individual) -> list[str]:
    return [
        operator
        for operator in OPERATOR_RE.findall(nodes_section(individual.individual))
        if operator not in STRUCTURAL_NODES
    ]


def operator_counts(dataset: Dataset, limit: int) -> Counter[str]:
    counts: Counter[str] = Counter()
    for individual in top_by_min_metric(dataset.individuals, limit):
        counts.update(operator_types(individual))
    return counts


def operator_order(counts_by_label: dict[str, Counter[str]]) -> list[str]:
    totals = Counter()
    for counts in counts_by_label.values():
        totals.update(counts)
    return [
        operator
        for operator, _ in sorted(totals.items(), key=lambda item: (-item[1], item[0]))
    ]


def plot_counts(datasets: list[Dataset], limit: int, output_path: Path) -> None:
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
    plot_counts(datasets, args.individuals, args.output)


if __name__ == "__main__":
    main()
