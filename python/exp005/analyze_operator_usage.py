#!/usr/bin/env python3

import argparse
import csv
import math
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


OPERATOR_RE = re.compile(
    r"\b(QueryConditionFork|ConditionalFork|FilterQueryCondition|FilterOperator|MapAggregate|"
    r"MapConditionPreservingRIR|MapConditionPreservingNoise|MapRIR|MapNoise|"
    r"MapConditionPartitionShuffle|MapConditionPairwiseSwap|"
    r"MapTimestampGroupShuffle|MapTimestampPairwiseSwap|MapDuplicate|Union|Sink|Source)\[id="
)
FIELD_RE = re.compile(
    r"(QueryConditionFork|ConditionalFork|FilterQueryCondition|FilterOperator|MapAggregate|"
    r"MapConditionPreservingRIR|MapConditionPreservingNoise|MapRIR|MapNoise|"
    r"MapConditionPartitionShuffle|MapConditionPairwiseSwap|"
    r"MapTimestampGroupShuffle|MapTimestampPairwiseSwap|MapDuplicate)\[id=[^\]]+?field=(f\d+)"
)


@dataclass(frozen=True)
class Solution:
    rank: int
    seed: str
    min_metric: float
    distance: float
    privacy: float
    semantics: float
    fidelity: float
    source_group: str
    source_row: str
    individual: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Analyze operator and field usage in extracted LCL-flow solutions, "
            "with focus on privacy under utility constraints."
        )
    )
    parser.add_argument("solutions_percentile_csv", type=Path)
    parser.add_argument("unique_solutions_csv", type=Path)
    parser.add_argument(
        "output_report",
        type=Path,
        help="Markdown report path to write, for example outputs/exp005/operator_usage.md.",
    )
    parser.add_argument(
        "--top-n",
        type=int,
        default=20,
        help="Number of individuals used for each top cohort (default: 20).",
    )
    return parser.parse_args()


def read_solutions_percentile(path: Path) -> list[dict[str, str]]:
    with path.open(newline="") as f:
        return list(csv.DictReader(f, delimiter=";"))


def read_unique_solutions(path: Path) -> list[Solution]:
    with path.open(newline="") as f:
        rows = list(csv.DictReader(f))
    return [
        Solution(
            rank=int(row["rank"]),
            seed=row["seed"],
            min_metric=float(row["min_privacy_semantics_fidelity"]),
            distance=float(row["distance_from_perfect"]),
            privacy=float(row["privacy"]),
            semantics=float(row["semantics"]),
            fidelity=float(row["fidelity"]),
            source_group=row["source_group"],
            source_row=row["source_row"],
            individual=row["individual"],
        )
        for row in rows
    ]


def nodes_section(individual: str) -> str:
    match = re.search(r"nodes=\[(.*?)]\s*, arcs=", individual)
    return match.group(1) if match else individual


def operators(solution: Solution) -> list[str]:
    return OPERATOR_RE.findall(nodes_section(solution.individual))


def operator_fields(solution: Solution) -> list[tuple[str, str]]:
    return FIELD_RE.findall(nodes_section(solution.individual))


def operator_pattern(solution: Solution) -> str:
    return " -> ".join(operators(solution))


def distance_from_perfect(solution: Solution) -> float:
    return math.sqrt(
        (1.0 - solution.privacy) ** 2
        + (1.0 - solution.semantics) ** 2
        + (1.0 - solution.fidelity) ** 2
    )


def balanced_key(solution: Solution) -> tuple[float, float, float, float, float]:
    return (
        -solution.min_metric,
        solution.distance,
        -solution.privacy,
        -solution.semantics,
        -solution.fidelity,
    )


def privacy_key(solution: Solution) -> tuple[float, float, float, float, float]:
    return (
        -solution.privacy,
        -solution.min_metric,
        solution.distance,
        -solution.semantics,
        -solution.fidelity,
    )


def metric_summary(solutions: list[Solution]) -> str:
    if not solutions:
        return "No solutions."
    best_balanced = min(solutions, key=balanced_key)
    best_privacy = min(solutions, key=privacy_key)
    return (
        f"count={len(solutions)}, "
        f"best_min={best_balanced.min_metric:.6g}, "
        f"best_min_p/s/f={best_balanced.privacy:.6g}/"
        f"{best_balanced.semantics:.6g}/{best_balanced.fidelity:.6g}, "
        f"best_privacy={best_privacy.privacy:.6g}, "
        f"best_privacy_s/f={best_privacy.semantics:.6g}/{best_privacy.fidelity:.6g}"
    )


def counter_table(counter: Counter, limit: int | None = None) -> str:
    rows = counter.most_common(limit)
    if not rows:
        return "_none_\n"
    lines = ["| item | count |", "|---|---:|"]
    lines.extend(f"| `{item}` | {count} |" for item, count in rows)
    return "\n".join(lines) + "\n"


def top_solutions_table(solutions: list[Solution], limit: int) -> str:
    if not solutions:
        return "_none_\n"
    lines = [
        "| rank | seed | privacy | semantics | fidelity | min | source | operators |",
        "|---:|---:|---:|---:|---:|---:|---|---|",
    ]
    for solution in solutions[:limit]:
        ops = ", ".join(operators(solution))
        lines.append(
            f"| {solution.rank} | {solution.seed} | {solution.privacy:.6g} | "
            f"{solution.semantics:.6g} | {solution.fidelity:.6g} | "
            f"{solution.min_metric:.6g} | {solution.source_group}:{solution.source_row} | "
            f"`{ops}` |"
        )
    return "\n".join(lines) + "\n"


def summarize_cohort(name: str, solutions: list[Solution], top_n: int) -> str:
    operator_counts = Counter()
    field_counts = Counter()
    operator_field_counts = Counter()
    pattern_counts = Counter()
    for solution in solutions:
        operator_counts.update(operators(solution))
        pairs = operator_fields(solution)
        field_counts.update(field for _, field in pairs)
        operator_field_counts.update(f"{operator}:{field}" for operator, field in pairs)
        pattern_counts.update([operator_pattern(solution)])

    return "\n".join(
        [
            f"## {name}",
            "",
            metric_summary(solutions),
            "",
            "### Top Solutions",
            "",
            top_solutions_table(solutions, min(top_n, len(solutions))),
            "### Operator Counts",
            "",
            counter_table(operator_counts),
            "### Field Counts",
            "",
            counter_table(field_counts),
            "### Operator-Field Counts",
            "",
            counter_table(operator_field_counts, limit=30),
            "### Operator Patterns",
            "",
            counter_table(pattern_counts, limit=15),
        ]
    )


def final_seed_rows(solutions_percentile_rows: list[dict[str, str]]) -> list[dict[str, str]]:
    if not solutions_percentile_rows:
        return []
    by_seed: dict[str, list[dict[str, str]]] = {}
    for row in solutions_percentile_rows:
        by_seed.setdefault(row.get("randomGenerator.seed", ""), []).append(row)
    return [
        sorted(rows, key=lambda r: int(float(r.get("n.evals", "0"))))[-1]
        for _, rows in sorted(by_seed.items(), key=lambda item: item[0])
    ]


def final_seed_table(rows: list[dict[str, str]]) -> str:
    if not rows:
        return "_none_\n"
    fields = [
        "randomGenerator.seed",
        "n.evals",
        "firsts\u2192size",
        "lasts\u2192size",
        "all\u2192each[solution]\u2192uniqueness",
        "all\u2192each[quality]\u2192uniqueness",
    ]
    lines = [
        "| seed | evals | firsts | lasts | solution uniqueness | quality uniqueness |",
        "|---:|---:|---:|---:|---:|---:|",
    ]
    for row in rows:
        lines.append(
            "| "
            + " | ".join(row.get(field, "") for field in fields)
            + " |"
        )
    return "\n".join(lines) + "\n"


def constrained_cohorts(solutions: list[Solution], top_n: int) -> list[tuple[str, list[Solution]]]:
    constraints = [
        ("Constrained Privacy: semantics >= 0.80 and fidelity >= 0.95", 0.80, 0.95),
        ("Constrained Privacy: semantics >= 0.70 and fidelity >= 0.95", 0.70, 0.95),
        ("Constrained Privacy: semantics >= 0.80 and fidelity >= 0.90", 0.80, 0.90),
        ("Constrained Privacy: semantics >= 0.60 and fidelity >= 0.95", 0.60, 0.95),
    ]
    cohorts = []
    for name, min_semantics, min_fidelity in constraints:
        filtered = [
            solution
            for solution in solutions
            if solution.semantics >= min_semantics and solution.fidelity >= min_fidelity
        ]
        cohorts.append((name, sorted(filtered, key=privacy_key)[:top_n]))
    return cohorts


def write_report(
    output_path: Path,
    solutions_percentile_path: Path,
    unique_solutions_path: Path,
    solutions_percentile_rows: list[dict[str, str]],
    unique_solutions: list[Solution],
    top_n: int,
) -> None:
    top_balanced = sorted(unique_solutions, key=balanced_key)[:top_n]
    top_privacy = sorted(unique_solutions, key=privacy_key)[:top_n]
    cohorts = [
        ("All Logged Unique Solutions", unique_solutions),
        (f"Top {top_n} Balanced Solutions", top_balanced),
        (f"Top {top_n} Privacy-Only Solutions", top_privacy),
    ]
    cohorts.extend(constrained_cohorts(unique_solutions, top_n))

    parts = [
        "# Experiment 005 Operator Usage Analysis",
        "",
        f"- `solutions-percentile.csv`: `{solutions_percentile_path}`",
        f"- `unique_solutions.csv`: `{unique_solutions_path}`",
        f"- percentile rows: `{len(solutions_percentile_rows)}`",
        f"- unique solution rows: `{len(unique_solutions)}`",
        f"- top N per cohort: `{top_n}`",
        "",
        "## Final Rows By Seed",
        "",
        final_seed_table(final_seed_rows(solutions_percentile_rows)),
    ]
    for name, cohort in cohorts:
        parts.append(summarize_cohort(name, cohort, top_n))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n\n".join(parts), encoding="utf-8")


def main() -> None:
    args = parse_args()
    if args.top_n <= 0:
        raise ValueError("--top-n must be positive")
    percentile_rows = read_solutions_percentile(args.solutions_percentile_csv)
    unique_solutions = read_unique_solutions(args.unique_solutions_csv)
    write_report(
        args.output_report,
        args.solutions_percentile_csv,
        args.unique_solutions_csv,
        percentile_rows,
        unique_solutions,
        args.top_n,
    )
    print(f"Wrote report to {args.output_report}")


if __name__ == "__main__":
    main()
