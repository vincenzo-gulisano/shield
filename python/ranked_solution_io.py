#!/usr/bin/env python3

import csv
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


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


@dataclass(frozen=True)
class IndividualScores:
    rank: int
    seed: str
    source_group: str
    source_row: int
    min_metric: float
    distance: float
    privacy: float
    semantics: float
    fidelity: float
    individual: str


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
                    source_group=row["source_group"],
                    source_row=required_int(row, "source_row", csv_path),
                    min_metric=required_float(row, "min_privacy_semantics_fidelity", csv_path),
                    distance=required_float(row, "distance_from_perfect", csv_path),
                    privacy=required_float(row, "privacy", csv_path),
                    semantics=required_float(row, "semantics", csv_path),
                    fidelity=required_float(row, "fidelity", csv_path),
                    individual=row["individual"],
                )
            )
    return individuals


def min_metric_rank_key(item: IndividualScores) -> tuple[float, float, float, float, float, int]:
    return (
        -item.min_metric,
        item.distance,
        -item.privacy,
        -item.semantics,
        -item.fidelity,
        item.rank,
    )


def distance_rank_key(item: IndividualScores) -> tuple[float, float, float, float, float, int]:
    return (
        item.distance,
        -item.min_metric,
        -item.privacy,
        -item.semantics,
        -item.fidelity,
        item.rank,
    )


def top_one_per_seed(
    individuals: list[IndividualScores],
    limit: int,
    rank_key: Callable[[IndividualScores], tuple],
    csv_path: Path,
    ranking_name: str,
) -> list[IndividualScores]:
    best_by_seed: dict[str, IndividualScores] = {}
    for individual in individuals:
        previous = best_by_seed.get(individual.seed)
        if previous is None or rank_key(individual) < rank_key(previous):
            best_by_seed[individual.seed] = individual

    if len(best_by_seed) < limit:
        raise ValueError(
            f"{csv_path} has {len(best_by_seed)} seeds, fewer than requested "
            f"{limit} for {ranking_name}"
        )
    return sorted(best_by_seed.values(), key=rank_key)[:limit]


def top_by_min_metric(individuals: list[IndividualScores], limit: int) -> list[IndividualScores]:
    return sorted(individuals, key=min_metric_rank_key)[:limit]


def top_by_distance(individuals: list[IndividualScores], limit: int) -> list[IndividualScores]:
    return sorted(individuals, key=distance_rank_key)[:limit]


def select_by_min_metric(
    dataset: DatasetScores,
    limit: int,
    top_one_per_seed_enabled: bool,
) -> list[IndividualScores]:
    if top_one_per_seed_enabled:
        return top_one_per_seed(
            dataset.individuals,
            limit,
            min_metric_rank_key,
            dataset.csv_path,
            "min metric",
        )
    return top_by_min_metric(dataset.individuals, limit)


def select_by_distance(
    dataset: DatasetScores,
    limit: int,
    top_one_per_seed_enabled: bool,
) -> list[IndividualScores]:
    if top_one_per_seed_enabled:
        return top_one_per_seed(
            dataset.individuals,
            limit,
            distance_rank_key,
            dataset.csv_path,
            "distance from perfect",
        )
    return top_by_distance(dataset.individuals, limit)


def select_by_ranking(
    dataset: DatasetScores,
    ranking: str,
    limit: int,
    top_one_per_seed_enabled: bool,
) -> list[IndividualScores]:
    match ranking:
        case "top_min" | "min" | "min_metric":
            return select_by_min_metric(dataset, limit, top_one_per_seed_enabled)
        case "top_distance" | "distance":
            return select_by_distance(dataset, limit, top_one_per_seed_enabled)
        case _:
            raise ValueError(f"Unknown ranking mode: {ranking}")
