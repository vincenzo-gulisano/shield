#!/usr/bin/env python3

import argparse
import csv
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path

from ranked_solution_io import raise_csv_field_size_limit, required_float, required_int, sniff_dialect


PLOT_CACHE_DIR = Path(tempfile.gettempdir()) / "shield-python-plot-cache"
PLOT_CACHE_DIR.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("XDG_CACHE_HOME", str(PLOT_CACHE_DIR))
os.environ.setdefault("MPLCONFIGDIR", str(PLOT_CACHE_DIR / "matplotlib"))


REQUIRED_COLUMNS = {
    "id",
    "source_csv",
    "ranking_mode",
    "selection_rank",
    "seed",
    "source_group",
    "source_row",
    "original_rank",
    "privacy",
    "semantics",
    "fidelity",
    "min_privacy_semantics_fidelity",
    "distance_from_perfect",
    "rescored_privacy",
    "rescored_semantics",
    "rescored_fidelity",
    "rescored_min_privacy_semantics_fidelity",
    "rescored_distance_from_perfect",
}

BOX_COLORS = ["#4c78a8", "#f58518"]


@dataclass(frozen=True)
class RescoredIndividual:
    label: str
    source_csv: str
    ranking_mode: str
    selection_rank: int
    seed: str
    source_group: str
    source_row: int
    original_rank: int
    privacy: float
    semantics: float
    fidelity: float
    min_metric: float
    distance: float
    rescored_privacy: float
    rescored_semantics: float
    rescored_fidelity: float
    rescored_min_metric: float
    rescored_distance: float


@dataclass(frozen=True)
class Dataset:
    label: str
    csv_path: Path
    individuals: list[RescoredIndividual]


def read_rescored_rows(csv_path: Path, label_override: str | None) -> list[RescoredIndividual]:
    raise_csv_field_size_limit()
    dialect = sniff_dialect(csv_path)
    rows = []
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
            rows.append(
                RescoredIndividual(
                    label=label_override if label_override is not None else row["id"],
                    source_csv=row["source_csv"],
                    ranking_mode=row["ranking_mode"],
                    selection_rank=required_int(row, "selection_rank", csv_path),
                    seed=row["seed"],
                    source_group=row["source_group"],
                    source_row=required_int(row, "source_row", csv_path),
                    original_rank=required_int(row, "original_rank", csv_path),
                    privacy=required_float(row, "privacy", csv_path),
                    semantics=required_float(row, "semantics", csv_path),
                    fidelity=required_float(row, "fidelity", csv_path),
                    min_metric=required_float(row, "min_privacy_semantics_fidelity", csv_path),
                    distance=required_float(row, "distance_from_perfect", csv_path),
                    rescored_privacy=required_float(row, "rescored_privacy", csv_path),
                    rescored_semantics=required_float(row, "rescored_semantics", csv_path),
                    rescored_fidelity=required_float(row, "rescored_fidelity", csv_path),
                    rescored_min_metric=required_float(
                        row,
                        "rescored_min_privacy_semantics_fidelity",
                        csv_path,
                    ),
                    rescored_distance=required_float(row, "rescored_distance_from_perfect", csv_path),
                )
            )
    return rows


def build_datasets(csv_paths: list[Path], labels: list[str] | None) -> list[Dataset]:
    if labels is not None and len(csv_paths) != len(labels):
        raise ValueError("--csvs and --ids must have the same number of values")

    if labels is not None:
        return [
            Dataset(label=label, csv_path=csv_path, individuals=read_rescored_rows(csv_path, label))
            for csv_path, label in zip(csv_paths, labels)
        ]

    grouped: dict[str, list[RescoredIndividual]] = {}
    source_paths: dict[str, Path] = {}
    for csv_path in csv_paths:
        for row in read_rescored_rows(csv_path, None):
            grouped.setdefault(row.label, []).append(row)
            source_paths.setdefault(row.label, csv_path)
    return [
        Dataset(label=label, csv_path=source_paths[label], individuals=rows)
        for label, rows in grouped.items()
    ]


def all_individuals(datasets: list[Dataset]) -> list[RescoredIndividual]:
    return [
        item
        for dataset in datasets
        for item in dataset.individuals
    ]


def output_csv_path(output_path: Path) -> Path:
    return output_path.with_suffix(".csv")


def write_plot_points_csv(datasets: list[Dataset], output_path: Path) -> Path:
    csv_path = output_csv_path(output_path)
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=["old_privacy", "new_privacy"])
        writer.writeheader()
        for dataset in datasets:
            for item in dataset.individuals:
                writer.writerow({
                    "old_privacy": f"{item.privacy:.12g}",
                    "new_privacy": f"{item.rescored_privacy:.12g}",
                })
    return csv_path


def plot_scores(datasets: list[Dataset], output_path: Path, write_csv: bool, log_y: bool) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    individuals = all_individuals(datasets)
    if not individuals:
        raise ValueError("No rescored individuals to plot")

    values = [
        [item.privacy for item in individuals],
        [item.rescored_privacy for item in individuals],
    ]
    if log_y and any(value <= 0.0 for group in values for value in group):
        raise ValueError("Cannot use a log y-axis with non-positive privacy scores; pass --no-log-y")

    figure, ax = plt.subplots(figsize=(6.5, 4.2))
    boxes = ax.boxplot(
        values,
        tick_labels=["old privacy", "new privacy"],
        patch_artist=True,
        showfliers=True,
    )
    for patch, color in zip(boxes["boxes"], BOX_COLORS):
        patch.set_facecolor(color)
        patch.set_alpha(0.55)
    ax.set_title("Privacy scores for the same selected individuals")
    ax.set_ylabel("privacy")
    if log_y:
        ax.set_yscale("log")
        ax.set_ylim(min(value for group in values for value in group) * 0.8, 1.02)
    else:
        ax.set_ylim(-0.02, 1.02)
    ax.grid(axis="y", linewidth=0.4, alpha=0.35)

    figure.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output_path, dpi=200)
    plt.close(figure)

    written_csv_path = write_plot_points_csv(datasets, output_path) if write_csv else None
    print(f"Plotted {len(individuals)} individuals as old and new privacy scores")
    print(f"Wrote plot to {output_path}")
    if written_csv_path is not None:
        print(f"Wrote plot data to {written_csv_path}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Plot old and new privacy distributions for rescored ranked "
            "solutions produced by usecase.analysis.RescoreIndividuals."
        )
    )
    parser.add_argument(
        "--csvs",
        nargs="+",
        type=Path,
        required=True,
        help="Input rescored CSV files.",
    )
    parser.add_argument(
        "--ids",
        nargs="+",
        help=(
            "Optional legend IDs, one per CSV. If omitted, the id column in the "
            "rescored CSV is used."
        ),
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        required=True,
        help="Output PDF path, for example outputs/rescored_privacy.pdf.",
    )
    parser.add_argument(
        "--write-csv",
        action="store_true",
        help="Also write the plotted privacy values next to the output using a .csv extension.",
    )
    parser.add_argument(
        "--log-y",
        action=argparse.BooleanOptionalAction,
        default=False,
        help="Use a log-scaled y-axis. Default: false.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    datasets = build_datasets(args.csvs, args.ids)
    plot_scores(datasets, args.output, args.write_csv, args.log_y)


if __name__ == "__main__":
    main()
