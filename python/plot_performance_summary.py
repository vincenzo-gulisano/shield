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
QUERY_KEY_FIELDS = ["id", "ranking_mode", "selection_rank", "seed", "graph_hash"]
AVERAGE_FIELDS = [
    "target_rate_per_s",
    "input_throughput_per_s",
    "output_throughput_per_s",
    "output_input_ratio",
    "avg_latency_ms",
    "max_latency_ms",
    "max_latency_minus_avg_latency_ms",
    "latency_slope_ms_per_s",
    "total_operators",
    "stateful_operators",
    "branches_or_joins",
]


def read_plot_rows(steps_csv: Path, selection: str) -> list[dict[str, str]]:
    by_repetition = {}
    with steps_csv.open(newline="") as file:
        for row in csv.DictReader(file):
            key = query_key(row) + (row["run"], row["repetition"])
            by_repetition.setdefault(key, []).append(row)

    by_query = {}
    for rows in by_repetition.values():
        selected = select_step(rows, selection)
        if selected is None:
            continue
        by_query.setdefault(query_key(selected), []).append(selected)

    rows = [average_repetitions(rows, selection) for rows in by_query.values()]
    return sorted(rows, key=plot_row_key)


def query_key(row: dict[str, str]) -> tuple[str, ...]:
    return tuple(row[field] for field in QUERY_KEY_FIELDS)


def select_step(rows: list[dict[str, str]], selection: str) -> dict[str, str] | None:
    selected = None
    for row in rows:
        if selection == "latency-filtered" and (
                row["crosses_warmup"] == "true" or not acceptable_latency(row)):
            continue
        if selected is None or selection_key(row, selection) > selection_key(selected, selection):
            selected = row
    return selected


def selection_key(row: dict[str, str], selection: str) -> tuple[float, int]:
    if selection == "max-throughput":
        return float(row["input_throughput_per_s"]), int(row["step"])
    return float(row["step"]), int(row["step"])


def average_repetitions(rows: list[dict[str, str]], selection: str) -> dict[str, str]:
    rows = sorted(rows, key=lambda row: (int(row["run"]), int(row["repetition"])))
    first = rows[0]
    experiment = ID_TO_EXPERIMENT.get(first["id"], first["id"])
    averaged = {
        "experiment": experiment,
        "selection": selection,
        "id": first["id"],
        "ranking_mode": first["ranking_mode"],
        "selection_rank": first["selection_rank"],
        "seed": first["seed"],
        "graph_hash": first["graph_hash"],
        "repetitions": str(len(rows)),
        "runs": "|".join(row["run"] for row in rows),
        "selected_steps": "|".join(row["step"] for row in rows),
        "selected_step": average(rows, "step"),
        "crosses_cooldown": str(any(row["crosses_cooldown"] == "true" for row in rows)).lower(),
    }
    for field in AVERAGE_FIELDS:
        if field in first:
            averaged[field] = average(rows, field)
    return averaged


def average(rows: list[dict[str, str]], field: str) -> str:
    values = [float(row[field]) for row in rows if row[field]]
    if not values:
        return ""
    return f"{sum(values) / len(values):.6f}"


def plot_row_key(row: dict[str, str]) -> tuple[int, str, int, int, str]:
    experiment_index = EXPERIMENT_ORDER.index(row["experiment"]) if row["experiment"] in EXPERIMENT_ORDER else 999
    return (
        experiment_index,
        row["id"],
        int(row["selection_rank"]),
        int(row["seed"]),
        row["graph_hash"],
    )


def acceptable_latency(row: dict[str, str]) -> bool:
    if not row["avg_latency_ms"] or not row["max_latency_ms"]:
        return False
    return float(row["max_latency_ms"]) <= 3.0 * float(row["avg_latency_ms"])


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
        description="Plot throughput and latency from an enriched stepped benchmark CSV."
    )
    parser.add_argument("steps_csv", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--title")
    parser.add_argument(
        "--selection",
        choices=["latency-filtered", "rightmost", "max-throughput"],
        default="latency-filtered",
        help=(
            "latency-filtered keeps the current max_latency <= 3 * avg_latency rule; "
            "rightmost takes the last step; max-throughput takes the step with highest input throughput."
        ),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    rows = read_plot_rows(args.steps_csv, args.selection)
    plot(rows, args.output, args.title)
    csv_path = write_summary_csv(rows, args.output)
    print(f"Wrote {args.output}")
    print(f"Wrote {csv_path}")


if __name__ == "__main__":
    main()
