#!/usr/bin/env python3

import argparse
import csv
from collections import defaultdict
from pathlib import Path


FIELDNAMES = [
    "id",
    "start_rate_per_s",
    "final_rate_per_s",
    "steps",
    "step_seconds",
    "bucket_millis",
]
ID_ORDER = ["shield1-oldPriv", "shield1-newPriv", "shield2", "shield2_prov"]


def read_rates(index_csv: Path) -> dict[str, list[float]]:
    rates = defaultdict(list)
    with index_csv.open(newline="") as file:
        for row in csv.DictReader(file):
            if row["status"] != "ok":
                continue
            input_rate = float(row["input_throughput_per_s"])
            output_rate = float(row["output_throughput_per_s"])
            rates[row["id"]].append(min(input_rate, output_rate))
    return rates


def rows_for_rate_plan(
    rates: dict[str, list[float]],
    steps: int,
    step_seconds: int,
    bucket_millis: int,
) -> list[dict[str, str]]:
    ordered_ids = [id_ for id_ in ID_ORDER if id_ in rates]
    ordered_ids.extend(sorted(id_ for id_ in rates if id_ not in ID_ORDER))
    rows = []
    for id_ in ordered_ids:
        final_rate = sum(rates[id_]) / len(rates[id_])
        start_rate = max(1.0, final_rate / steps)
        rows.append(
            {
                "id": id_,
                "start_rate_per_s": f"{start_rate:.3f}",
                "final_rate_per_s": f"{final_rate:.3f}",
                "steps": str(steps),
                "step_seconds": str(step_seconds),
                "bucket_millis": str(bucket_millis),
            }
        )
    return rows


def write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=FIELDNAMES)
        writer.writeheader()
        writer.writerows(rows)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Suggest stepped input rates from previous benchmark throughput.")
    parser.add_argument("index_csv", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--steps", type=int, default=6)
    parser.add_argument("--step-seconds", type=int, default=30)
    parser.add_argument("--bucket-millis", type=int, default=50)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    rates = read_rates(args.index_csv)
    rows = rows_for_rate_plan(rates, args.steps, args.step_seconds, args.bucket_millis)
    write_csv(args.output, rows)
    print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
