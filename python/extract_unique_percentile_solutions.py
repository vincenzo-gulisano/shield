#!/usr/bin/env python3

import argparse
import csv
import math
import re
from dataclasses import dataclass
from pathlib import Path

from graph_printout_to_image import render_graph_printout


GROUPS = [
    (
        "p100_privacy",
        "all→percentile[quality→privacy;100]→quality→privacy",
        "all→percentile[quality→privacy;100]→quality→semantics",
        "all→percentile[quality→privacy;100]→quality→fidelity",
        "all→percentile[quality→privacy;100]→solution",
    ),
    (
        "p100_semantics",
        "all→percentile[quality→semantics;100]→quality→privacy",
        "all→percentile[quality→semantics;100]→quality→semantics",
        "all→percentile[quality→semantics;100]→quality→fidelity",
        "all→percentile[quality→semantics;100]→solution",
    ),
    (
        "p100_fidelity",
        "all→percentile[quality→fidelity;100]→quality→privacy",
        "all→percentile[quality→fidelity;100]→quality→semantics",
        "all→percentile[quality→fidelity;100]→quality→fidelity",
        "all→percentile[quality→fidelity;100]→solution",
    ),
    (
        "p75_privacy",
        "all→percentile[quality→privacy;75]→quality→privacy",
        "all→percentile[quality→privacy;75]→quality→semantics",
        "all→percentile[quality→privacy;75]→quality→fidelity",
        "all→percentile[quality→privacy;75]→solution",
    ),
    (
        "p75_semantics",
        "all→percentile[quality→semantics;75]→quality→privacy",
        "all→percentile[quality→semantics;75]→quality→semantics",
        "all→percentile[quality→semantics;75]→quality→fidelity",
        "all→percentile[quality→semantics;75]→solution",
    ),
    (
        "p75_fidelity",
        "all→percentile[quality→fidelity;75]→quality→privacy",
        "all→percentile[quality→fidelity;75]→quality→semantics",
        "all→percentile[quality→fidelity;75]→quality→fidelity",
        "all→percentile[quality→fidelity;75]→solution",
    ),
]


@dataclass(frozen=True)
class Solution:
    privacy: float
    semantics: float
    fidelity: float
    individual: str
    source_group: str
    source_row: int

    @property
    def distance(self) -> float:
        return math.sqrt(
            (1.0 - self.privacy) ** 2
            + (1.0 - self.semantics) ** 2
            + (1.0 - self.fidelity) ** 2
        )


def read_unique_solutions(input_csv: Path) -> list[Solution]:
    unique_by_individual: dict[str, Solution] = {}
    with input_csv.open(newline="") as f:
        reader = csv.DictReader(f, delimiter=";")
        for source_row, row in enumerate(reader, start=1):
            for group_name, privacy_col, semantics_col, fidelity_col, individual_col in GROUPS:
                individual = row[individual_col].strip()
                if not individual:
                    continue
                solution = Solution(
                    privacy=float(row[privacy_col]),
                    semantics=float(row[semantics_col]),
                    fidelity=float(row[fidelity_col]),
                    individual=individual,
                    source_group=group_name,
                    source_row=source_row,
                )
                previous = unique_by_individual.get(individual)
                if previous is None or solution.distance < previous.distance:
                    unique_by_individual[individual] = solution
    return sorted(unique_by_individual.values(), key=lambda s: (s.distance, s.privacy, s.semantics, s.fidelity))


def image_name(rank: int, solution: Solution) -> str:
    score_part = (
        f"p{format_score(solution.privacy)}"
        f"_s{format_score(solution.semantics)}"
        f"_f{format_score(solution.fidelity)}"
    )
    return f"{rank:04d}_{score_part}.png"


def format_score(value: float) -> str:
    return re.sub(r"[^0-9]+", "_", f"{value:.6f}").strip("_")


def write_solutions_csv(solutions: list[Solution], output_csv: Path, image_names: list[str]) -> None:
    with output_csv.open("w", newline="") as f:
        fieldnames = [
            "rank",
            "distance_from_perfect",
            "privacy",
            "semantics",
            "fidelity",
            "source_group",
            "source_row",
            "image",
            "individual",
        ]
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for rank, (solution, image) in enumerate(zip(solutions, image_names), start=1):
            writer.writerow(
                {
                    "rank": rank,
                    "distance_from_perfect": f"{solution.distance:.12g}",
                    "privacy": f"{solution.privacy:.12g}",
                    "semantics": f"{solution.semantics:.12g}",
                    "fidelity": f"{solution.fidelity:.12g}",
                    "source_group": solution.source_group,
                    "source_row": solution.source_row,
                    "image": image,
                    "individual": solution.individual,
                }
            )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Extract unique percentile solutions and render each solution graph as a PNG."
    )
    parser.add_argument("input_csv", type=Path, help="Path to solutions-percentile.csv.")
    parser.add_argument("output_dir", type=Path, help="Folder where the CSV and PNG files are written.")
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    solutions = read_unique_solutions(args.input_csv)
    image_names = [image_name(rank, solution) for rank, solution in enumerate(solutions, start=1)]

    output_csv = args.output_dir / "unique_solutions.csv"
    write_solutions_csv(solutions, output_csv, image_names)
    for solution, image in zip(solutions, image_names):
        render_graph_printout(solution.individual, args.output_dir / image, verbose=False)

    print(f"Wrote {len(solutions)} unique solutions to {output_csv}")
    print(f"Wrote {len(solutions)} PNG files to {args.output_dir}")


if __name__ == "__main__":
    main()
