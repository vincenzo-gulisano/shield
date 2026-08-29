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


def selected_for_panel(dataset: Dataset, ranking_mode: str) -> list[RescoredIndividual]:
    return sorted(
        [item for item in dataset.individuals if item.ranking_mode == ranking_mode],
        key=lambda item: item.selection_rank,
    )


def scatter_panel(ax, datasets: list[Dataset], ranking_mode: str, y_metric: str, title: str) -> None:
    for index, dataset in enumerate(datasets):
        color, marker = SERIES_STYLES[index]
        selected = selected_for_panel(dataset, ranking_mode)
        original_xs = [item.privacy for item in selected]
        original_ys = [getattr(item, y_metric) for item in selected]
        rescored_xs = [item.rescored_privacy for item in selected]
        rescored_ys = [getattr(item, f"rescored_{y_metric}") for item in selected]
        for item in selected:
            ax.plot(
                [item.privacy, item.rescored_privacy],
                [getattr(item, y_metric), getattr(item, f"rescored_{y_metric}")],
                color=color,
                linewidth=0.6,
                alpha=0.22,
                zorder=1,
            )
        ax.scatter(
            original_xs,
            original_ys,
            label=f"{dataset.label} original",
            marker=marker,
            facecolors="none",
            edgecolors=color,
            s=44,
            alpha=0.78,
            linewidths=0.9,
            zorder=2,
        )
        ax.scatter(
            rescored_xs,
            rescored_ys,
            label=f"{dataset.label} rescored",
            marker=marker,
            color=color,
            s=42,
            alpha=0.78,
            edgecolors="white",
            linewidths=0.35,
            zorder=3,
        )

    ax.set_title(title)
    ax.set_xlabel("privacy")
    ax.set_ylabel(y_metric)
    ax.set_xlim(-0.02, 1.02)
    ax.set_ylim(-0.02, 1.02)
    ax.grid(True, linewidth=0.4, alpha=0.35)


def output_csv_path(output_path: Path) -> Path:
    return output_path.with_suffix(".csv")


def write_plot_points_csv(datasets: list[Dataset], output_path: Path) -> Path:
    csv_path = output_csv_path(output_path)
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", newline="") as file:
        fieldnames = [
            "id",
            "source_csv",
            "ranking_mode",
            "panel",
            "score_set",
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
            "x_metric",
            "y_metric",
            "x",
            "y",
        ]
        writer = csv.DictWriter(file, fieldnames=fieldnames)
        writer.writeheader()
        panels = [
            ("top_min", "top_min_privacy_fidelity", "fidelity"),
            ("top_min", "top_min_privacy_semantics", "semantics"),
            ("top_distance", "top_distance_privacy_fidelity", "fidelity"),
            ("top_distance", "top_distance_privacy_semantics", "semantics"),
        ]
        for dataset in datasets:
            for ranking_mode, panel, y_metric in panels:
                for item in selected_for_panel(dataset, ranking_mode):
                    common = {
                        "id": dataset.label,
                        "source_csv": item.source_csv,
                        "ranking_mode": ranking_mode,
                        "panel": panel,
                        "selection_rank": item.selection_rank,
                        "seed": item.seed,
                        "source_group": item.source_group,
                        "source_row": item.source_row,
                        "original_rank": item.original_rank,
                        "privacy": f"{item.privacy:.12g}",
                        "semantics": f"{item.semantics:.12g}",
                        "fidelity": f"{item.fidelity:.12g}",
                        "min_privacy_semantics_fidelity": f"{item.min_metric:.12g}",
                        "distance_from_perfect": f"{item.distance:.12g}",
                        "rescored_privacy": f"{item.rescored_privacy:.12g}",
                        "rescored_semantics": f"{item.rescored_semantics:.12g}",
                        "rescored_fidelity": f"{item.rescored_fidelity:.12g}",
                        "rescored_min_privacy_semantics_fidelity": f"{item.rescored_min_metric:.12g}",
                        "rescored_distance_from_perfect": f"{item.rescored_distance:.12g}",
                    }
                    writer.writerow(
                        common | {
                            "score_set": "original",
                            "x_metric": "privacy",
                            "y_metric": y_metric,
                            "x": f"{item.privacy:.12g}",
                            "y": f"{getattr(item, y_metric):.12g}",
                        }
                    )
                    writer.writerow(
                        common | {
                            "score_set": "rescored",
                            "x_metric": "rescored_privacy",
                            "y_metric": f"rescored_{y_metric}",
                            "x": f"{item.rescored_privacy:.12g}",
                            "y": f"{getattr(item, f'rescored_{y_metric}'):.12g}",
                        }
                    )
    return csv_path


def plot_scores(datasets: list[Dataset], output_path: Path, write_csv: bool) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    if len(datasets) > len(SERIES_STYLES):
        raise ValueError(f"At most {len(SERIES_STYLES)} data series are supported")

    figure, axes = plt.subplots(2, 2, figsize=(12, 9), sharex=True, sharey=True)
    scatter_panel(
        axes[0, 0],
        datasets,
        "top_min",
        "fidelity",
        "Selected by original min metric: privacy vs fidelity",
    )
    scatter_panel(
        axes[0, 1],
        datasets,
        "top_min",
        "semantics",
        "Selected by original min metric: privacy vs semantics",
    )
    scatter_panel(
        axes[1, 0],
        datasets,
        "top_distance",
        "fidelity",
        "Selected by original distance: privacy vs fidelity",
    )
    scatter_panel(
        axes[1, 1],
        datasets,
        "top_distance",
        "semantics",
        "Selected by original distance: privacy vs semantics",
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

    written_csv_path = write_plot_points_csv(datasets, output_path) if write_csv else None
    for dataset in datasets:
        print(
            f"{dataset.label}: plotted "
            f"{len(selected_for_panel(dataset, 'top_min'))} top_min and "
            f"{len(selected_for_panel(dataset, 'top_distance'))} top_distance rows "
            f"from {dataset.csv_path} as original and rescored points"
        )
    print(f"Wrote plot to {output_path}")
    if written_csv_path is not None:
        print(f"Wrote plot data to {written_csv_path}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Plot original and rescored ranked solutions produced by "
            "usecase.analysis.RescoreIndividuals."
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
        help="Output image path, for example outputs/rescored.pdf.",
    )
    parser.add_argument(
        "--write-csv",
        action="store_true",
        help="Also write the plotted original and rescored data points next to the output using a .csv extension.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    datasets = build_datasets(args.csvs, args.ids)
    plot_scores(datasets, args.output, args.write_csv)


if __name__ == "__main__":
    main()
