#!/usr/bin/env python3

import argparse
import csv
from pathlib import Path

from ranked_solution_io import (
    DatasetScores,
    IndividualScores,
    read_individuals,
    select_by_ranking,
)


FIELDNAMES = [
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
    "individual",
]
RANKINGS = ["top_min", "top_distance"]


def row_for(
    dataset: DatasetScores,
    ranking_mode: str,
    selection_rank: int,
    individual: IndividualScores,
) -> dict[str, str]:
    return {
        "id": dataset.label,
        "source_csv": str(dataset.csv_path),
        "ranking_mode": ranking_mode,
        "selection_rank": str(selection_rank),
        "seed": individual.seed,
        "source_group": individual.source_group,
        "source_row": str(individual.source_row),
        "original_rank": str(individual.rank),
        "privacy": f"{individual.privacy:.12g}",
        "semantics": f"{individual.semantics:.12g}",
        "fidelity": f"{individual.fidelity:.12g}",
        "min_privacy_semantics_fidelity": f"{individual.min_metric:.12g}",
        "distance_from_perfect": f"{individual.distance:.12g}",
        "individual": individual.individual,
    }


def write_manifest(
    datasets: list[DatasetScores],
    rankings: list[str],
    limit: int,
    top_one_per_seed_enabled: bool,
    output_path: Path,
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    total = 0
    with output_path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=FIELDNAMES)
        writer.writeheader()
        for dataset in datasets:
            for ranking_mode in rankings:
                selected = select_by_ranking(
                    dataset,
                    ranking_mode,
                    limit,
                    top_one_per_seed_enabled,
                )
                for selection_rank, individual in enumerate(selected, start=1):
                    writer.writerow(row_for(dataset, ranking_mode, selection_rank, individual))
                    total += 1
                print(
                    f"{dataset.label}: wrote {len(selected)} {ranking_mode} "
                    f"individuals from {dataset.csv_path}"
                )
    print(f"Wrote {total} query rows to {output_path}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Extract ranked solution graph strings into a rescoring manifest CSV."
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
        help="IDs, one per CSV file.",
    )
    parser.add_argument(
        "-i",
        "--individuals",
        type=int,
        required=True,
        help="Number of best individuals to extract from each CSV in each ranking.",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        required=True,
        help="Output manifest CSV path.",
    )
    parser.add_argument(
        "--top-one-per-seed",
        action="store_true",
        help=(
            "Select the best individual from each seed first, then extract the top -i "
            "seed representatives for each ranking."
        ),
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if len(args.csvs) != len(args.ids):
        raise ValueError("--csvs and --ids must have the same number of values")
    if args.individuals <= 0:
        raise ValueError("--individuals must be positive")

    datasets = [
        DatasetScores(label=label, csv_path=csv_path, individuals=read_individuals(csv_path))
        for csv_path, label in zip(args.csvs, args.ids)
    ]
    write_manifest(
        datasets,
        RANKINGS,
        args.individuals,
        args.top_one_per_seed,
        args.output,
    )


if __name__ == "__main__":
    main()
