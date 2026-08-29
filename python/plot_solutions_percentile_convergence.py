#!/usr/bin/env python3

import argparse
import csv
import math
import os
import statistics
import tempfile
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from ranked_solution_io import raise_csv_field_size_limit, required_float, required_int, sniff_dialect


PLOT_CACHE_DIR = Path(tempfile.gettempdir()) / "shield-python-plot-cache"
PLOT_CACHE_DIR.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("XDG_CACHE_HOME", str(PLOT_CACHE_DIR))
os.environ.setdefault("MPLCONFIGDIR", str(PLOT_CACHE_DIR / "matplotlib"))

SEED_COLUMN = "randomGenerator.seed"
ITERATION_COLUMN = "n.iterations"

PERCENTILE_GROUPS = [
    (
        "p100_privacy",
        "all→percentile[quality→privacy;100]→quality→privacy",
        "all→percentile[quality→privacy;100]→quality→semantics",
        "all→percentile[quality→privacy;100]→quality→fidelity",
    ),
    (
        "p100_semantics",
        "all→percentile[quality→semantics;100]→quality→privacy",
        "all→percentile[quality→semantics;100]→quality→semantics",
        "all→percentile[quality→semantics;100]→quality→fidelity",
    ),
    (
        "p100_fidelity",
        "all→percentile[quality→fidelity;100]→quality→privacy",
        "all→percentile[quality→fidelity;100]→quality→semantics",
        "all→percentile[quality→fidelity;100]→quality→fidelity",
    ),
    (
        "p75_privacy",
        "all→percentile[quality→privacy;75]→quality→privacy",
        "all→percentile[quality→privacy;75]→quality→semantics",
        "all→percentile[quality→privacy;75]→quality→fidelity",
    ),
    (
        "p75_semantics",
        "all→percentile[quality→semantics;75]→quality→privacy",
        "all→percentile[quality→semantics;75]→quality→semantics",
        "all→percentile[quality→semantics;75]→quality→fidelity",
    ),
    (
        "p75_fidelity",
        "all→percentile[quality→fidelity;75]→quality→privacy",
        "all→percentile[quality→fidelity;75]→quality→semantics",
        "all→percentile[quality→fidelity;75]→quality→fidelity",
    ),
]


@dataclass(frozen=True)
class IterationScore:
    seed: str
    iteration: int
    x: float
    min_balanced: float
    max_balanced: float


@dataclass(frozen=True)
class SummaryRow:
    score_kind: str
    x: float
    n_seeds: int
    mean: float
    q1: float
    q3: float
    min_seed_value: float
    max_seed_value: float


def required_columns(x_column: str) -> set[str]:
    columns = {SEED_COLUMN, ITERATION_COLUMN, x_column}
    for _, privacy_col, semantics_col, fidelity_col in PERCENTILE_GROUPS:
        columns.update([privacy_col, semantics_col, fidelity_col])
    return columns


def read_scores(csv_path: Path, x_column: str) -> list[IterationScore]:
    raise_csv_field_size_limit()
    dialect = sniff_dialect(csv_path)
    scores = []
    with csv_path.open(newline="") as file:
        reader = csv.DictReader(file, dialect=dialect)
        fieldnames = {name.strip() for name in reader.fieldnames or []}
        missing_columns = sorted(required_columns(x_column) - fieldnames)
        if missing_columns:
            raise ValueError(f"{csv_path} is missing required columns: {', '.join(missing_columns)}")

        for raw_row in reader:
            row = {
                key.strip(): (value or "").strip()
                for key, value in raw_row.items()
                if key is not None
            }
            balanced_values = []
            for _, privacy_col, semantics_col, fidelity_col in PERCENTILE_GROUPS:
                privacy = required_float(row, privacy_col, csv_path)
                semantics = required_float(row, semantics_col, csv_path)
                fidelity = required_float(row, fidelity_col, csv_path)
                value = min(privacy, semantics, fidelity)
                if math.isfinite(value):
                    balanced_values.append(value)
            if not balanced_values:
                continue
            scores.append(
                IterationScore(
                    seed=row[SEED_COLUMN],
                    iteration=required_int(row, ITERATION_COLUMN, csv_path),
                    x=required_float(row, x_column, csv_path),
                    min_balanced=min(balanced_values),
                    max_balanced=max(balanced_values),
                )
            )
    if not scores:
        raise ValueError(f"No valid convergence scores found in {csv_path}")
    return scores


def cumulative_best(scores: list[IterationScore]) -> list[IterationScore]:
    by_seed: dict[str, list[IterationScore]] = defaultdict(list)
    for score in scores:
        by_seed[score.seed].append(score)

    cumulative = []
    for seed_scores in by_seed.values():
        best_min = -math.inf
        best_max = -math.inf
        for score in sorted(seed_scores, key=lambda item: (item.iteration, item.x)):
            best_min = max(best_min, score.min_balanced)
            best_max = max(best_max, score.max_balanced)
            cumulative.append(
                IterationScore(
                    seed=score.seed,
                    iteration=score.iteration,
                    x=score.x,
                    min_balanced=best_min,
                    max_balanced=best_max,
                )
            )
    return sorted(cumulative, key=lambda item: (item.x, item.seed, item.iteration))


def count_decreases(scores: list[IterationScore], metric: str) -> int:
    by_seed: dict[str, list[IterationScore]] = defaultdict(list)
    for score in scores:
        by_seed[score.seed].append(score)

    decreases = 0
    for seed_scores in by_seed.values():
        previous = None
        for score in sorted(seed_scores, key=lambda item: (item.iteration, item.x)):
            value = getattr(score, metric)
            if previous is not None and value < previous - 1e-12:
                decreases += 1
            previous = value
    return decreases


def quantile(values: list[float], q: float) -> float:
    if not values:
        raise ValueError("Cannot compute quantile of an empty list")
    sorted_values = sorted(values)
    position = (len(sorted_values) - 1) * q
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    weight = position - lower
    return sorted_values[lower] * (1.0 - weight) + sorted_values[upper] * weight


def summarize(scores: list[IterationScore], metric: str, score_kind: str) -> list[SummaryRow]:
    by_x: dict[float, list[float]] = defaultdict(list)
    for score in scores:
        by_x[score.x].append(getattr(score, metric))

    rows = []
    for x in sorted(by_x):
        values = by_x[x]
        rows.append(
            SummaryRow(
                score_kind=score_kind,
                x=x,
                n_seeds=len(values),
                mean=statistics.fmean(values),
                q1=quantile(values, 0.25),
                q3=quantile(values, 0.75),
                min_seed_value=min(values),
                max_seed_value=max(values),
            )
        )
    return rows


def output_csv_path(output_path: Path) -> Path:
    return output_path.with_suffix(".csv")


def write_summary_csv(
    summary_rows: list[SummaryRow],
    csv_path: Path,
    input_csv: Path,
    x_column: str,
    cumulative: bool,
) -> None:
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    with csv_path.open("w", newline="") as file:
        fieldnames = [
            "input_csv",
            "x_column",
            "mode",
            "score_kind",
            "x",
            "n_seeds",
            "mean",
            "q1",
            "q3",
            "min_seed_value",
            "max_seed_value",
        ]
        writer = csv.DictWriter(file, fieldnames=fieldnames)
        writer.writeheader()
        for row in summary_rows:
            writer.writerow(
                {
                    "input_csv": input_csv,
                    "x_column": x_column,
                    "mode": "cumulative" if cumulative else "raw",
                    "score_kind": row.score_kind,
                    "x": f"{row.x:.12g}",
                    "n_seeds": row.n_seeds,
                    "mean": f"{row.mean:.12g}",
                    "q1": f"{row.q1:.12g}",
                    "q3": f"{row.q3:.12g}",
                    "min_seed_value": f"{row.min_seed_value:.12g}",
                    "max_seed_value": f"{row.max_seed_value:.12g}",
                }
            )


def plot_summary(
    min_rows: list[SummaryRow],
    max_rows: list[SummaryRow],
    output_path: Path,
    x_column: str,
    title: str | None,
    cumulative: bool,
) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    panels = [
        ("Minimum of selected balanced scores", min_rows, "#1f77b4"),
        ("Maximum of selected balanced scores", max_rows, "#2ca02c"),
    ]
    figure, axes = plt.subplots(1, 2, figsize=(12, 4.8), sharey=True)
    mode_label = "cumulative best-so-far" if cumulative else "raw per-iteration"

    for ax, (panel_title, rows, color) in zip(axes, panels):
        xs = [row.x for row in rows]
        means = [row.mean for row in rows]
        q1s = [row.q1 for row in rows]
        q3s = [row.q3 for row in rows]
        ax.fill_between(xs, q1s, q3s, color=color, alpha=0.2, linewidth=0.0, label="Q1-Q3")
        ax.plot(xs, means, color=color, linewidth=1.8, label="mean")
        ax.set_title(panel_title)
        ax.set_xlabel(x_column)
        ax.set_ylim(-0.02, 1.02)
        ax.grid(True, linewidth=0.4, alpha=0.35)
        ax.legend(loc="lower right", frameon=False)

    axes[0].set_ylabel("min(privacy, semantics, fidelity)")
    if title:
        figure.suptitle(f"{title} ({mode_label})")
    else:
        figure.suptitle(f"Balanced-score convergence ({mode_label})")
    figure.tight_layout(rect=(0, 0, 1, 0.94))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output_path, dpi=200)
    plt.close(figure)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Plot convergence from solutions-percentile.csv using the min and max "
            "of min(privacy, semantics, fidelity) across the recorded percentile "
            "representative solutions at each seed iteration."
        )
    )
    parser.add_argument(
        "input_csv",
        type=Path,
        help="Input solutions-percentile.csv.",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        required=True,
        help="Output plot path, for example convergence.svg or convergence.pdf.",
    )
    parser.add_argument(
        "--x-column",
        default=ITERATION_COLUMN,
        help=f"CSV column to use on the x-axis. Default: {ITERATION_COLUMN}.",
    )
    parser.add_argument(
        "--raw",
        action="store_true",
        help="Plot raw per-iteration values instead of cumulative best-so-far values.",
    )
    parser.add_argument(
        "--write-csv",
        action="store_true",
        help="Also write the plotted summary data next to the output using a .csv extension.",
    )
    parser.add_argument(
        "--title",
        help="Optional figure title prefix.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    raw_scores = read_scores(args.input_csv, args.x_column)
    cumulative = not args.raw
    scores = cumulative_best(raw_scores) if cumulative else raw_scores

    min_rows = summarize(scores, "min_balanced", "min_balanced")
    max_rows = summarize(scores, "max_balanced", "max_balanced")
    plot_summary(min_rows, max_rows, args.output, args.x_column, args.title, cumulative)

    csv_path = None
    if args.write_csv:
        csv_path = output_csv_path(args.output)
        write_summary_csv(min_rows + max_rows, csv_path, args.input_csv, args.x_column, cumulative)

    print(
        f"Read {len(raw_scores)} rows from {args.input_csv}; "
        f"min decreases={count_decreases(raw_scores, 'min_balanced')}, "
        f"max decreases={count_decreases(raw_scores, 'max_balanced')}"
    )
    print(f"Wrote convergence plot to {args.output}")
    if csv_path is not None:
        print(f"Wrote convergence data to {csv_path}")


if __name__ == "__main__":
    main()
