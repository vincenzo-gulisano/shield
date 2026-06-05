#!/usr/bin/env python3
"""Build a daily aggregated LCL stream CSV from sharded London smart-meter files."""

from __future__ import annotations

import argparse
import csv
import math
import sys
from collections import Counter
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from statistics import median, pstdev
from zoneinfo import ZoneInfo


DEFAULT_HEADER = ["LCLid", "stdorToU", "DateTime", "KWH/hh (per half hour)"]
DEFAULT_OUTPUT_VALUE_COLUMN = "KWH_day"
OUTPUT_MAX_COLUMN = "KWH_max_30min"
OUTPUT_MEDIAN_COLUMN = "KWH_median_30min"
FLOW_FEATURE_HEADER = [
    "timestamp",
    "key",
    "f1_tariff",
    "f2_kwh_day",
    "f3_kwh_max_30min",
    "f4_kwh_median_30min",
    "f5_kwh_p90_30min",
    "f6_kwh_stdev_30min",
    "f7_evening_share",
    "f8_night_share",
    "f9_load_factor",
    "f10_peak_half_hour_slot",
    "f11_zero_half_hour_count",
]


@dataclass
class DailyAggregate:
    stdor_to_u: str
    total_kwh: float = 0.0
    finite_count: int = 0
    row_count: int = 0
    half_hour_values: list[tuple[int, float]] | None = None

    def add_value(self, slot: int, value: float) -> None:
        self.total_kwh += value
        self.finite_count += 1
        if self.half_hour_values is None:
            self.half_hour_values = []
        self.half_hour_values.append((slot, value))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Select a date range from LCL CSV shards, keep the first N LCLid keys, "
            "aggregate half-hour readings into one daily tuple per key, convert dates "
            "to Unix milliseconds, and sort by timestamp."
        )
    )
    parser.add_argument(
        "--input-dir",
        default="Small LCL Data",
        help="Directory containing the LCL CSV shards. Default: %(default)s",
    )
    parser.add_argument(
        "--file-glob",
        default="*.csv",
        help="File glob to use inside --input-dir. Default: %(default)s",
    )
    parser.add_argument(
        "--output",
        default="src/main/resources/datasets/lcl_daily.csv",
        help="Output CSV path. Default: %(default)s",
    )
    parser.add_argument(
        "--start-date",
        help="First calendar day to include, in YYYY-MM-DD format.",
    )
    parser.add_argument(
        "--end-date",
        help="Last calendar day to include, in YYYY-MM-DD format. Defaults to --start-date.",
    )
    parser.add_argument(
        "--date",
        help="Backward-compatible alias for selecting a single day.",
    )
    parser.add_argument(
        "--max-keys",
        type=int,
        help="Number of first distinct LCLid keys to keep.",
    )
    parser.add_argument(
        "--keys-per-tariff",
        type=int,
        help=(
            "Select this many first distinct keys for each stdorToU value. "
            "When provided, --max-keys is ignored."
        ),
    )
    parser.add_argument(
        "--flow-features",
        action="store_true",
        help="Write the richer f1..f11 daily feature schema used by the LCL flow use case.",
    )
    parser.add_argument(
        "--timezone",
        default="Europe/London",
        help="Timezone used to interpret LCL DateTime values. Default: %(default)s",
    )
    parser.add_argument(
        "--timestamp-column",
        default="timestamp",
        help=(
            "Header name for the converted timestamp column. Use 'timestamp' for Shield's "
            "generic CSV loader. Default: %(default)s"
        ),
    )
    parser.add_argument(
        "--value-column",
        default=DEFAULT_OUTPUT_VALUE_COLUMN,
        help="Header name for the daily aggregated kWh column. Default: %(default)s",
    )
    parser.add_argument(
        "--list-days",
        action="store_true",
        help="Print available days and row counts, then exit without writing output.",
    )
    parser.add_argument(
        "--top-days",
        type=int,
        default=20,
        help="How many days to show with --list-days. Use 0 for all. Default: %(default)s",
    )
    return parser.parse_args()


def lcl_files(input_dir: Path, file_glob: str) -> list[Path]:
    files = sorted(input_dir.glob(file_glob), key=lambda p: p.name)
    if not files:
        raise ValueError(f"No files matched {file_glob!r} in {input_dir}")
    return files


def normalized_header(raw_header: list[str]) -> list[str]:
    return [value.strip() for value in raw_header]


def require_expected_header(header: list[str], path: Path) -> None:
    if header != DEFAULT_HEADER:
        raise ValueError(f"Unexpected header in {path}: {header!r}")


def iter_lcl_rows(path: Path):
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        try:
            header = normalized_header(next(reader))
        except StopIteration:
            return
        require_expected_header(header, path)
        for row_number, row in enumerate(reader, start=2):
            if len(row) != len(DEFAULT_HEADER):
                print(
                    f"Skipping malformed row {row_number} in {path}: expected {len(DEFAULT_HEADER)} fields, got {len(row)}",
                    file=sys.stderr,
                )
                continue
            normalized_row = [value.strip() for value in row]
            if normalized_row[3].lower() == "null":
                normalized_row[3] = "NaN"
            yield normalized_row


def count_available_days(files: list[Path]) -> Counter[str]:
    counts: Counter[str] = Counter()
    for path in files:
        for row in iter_lcl_rows(path):
            date_time = row[2]
            if len(date_time) >= 10:
                counts[date_time[:10]] += 1
    return counts


def list_available_days(files: list[Path], top_days: int) -> None:
    counts = count_available_days(files)
    if not counts:
        raise ValueError("No dated rows found in the input files")

    ranked_days = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    days_to_show = ranked_days if top_days == 0 else ranked_days[:top_days]
    for day, count in days_to_show:
        print(f"{day},{count}")


def parse_iso_date(value: str) -> date:
    try:
        return datetime.strptime(value, "%Y-%m-%d").date()
    except ValueError as exc:
        raise ValueError(f"Invalid date {value!r}; expected YYYY-MM-DD") from exc


def resolve_period(args: argparse.Namespace) -> tuple[date, date]:
    start_value = args.start_date or args.date
    if not start_value:
        raise ValueError("Use --start-date YYYY-MM-DD to choose the first day to include")
    if args.date and args.start_date and args.date != args.start_date:
        raise ValueError("--date and --start-date disagree; use only one of them")

    start_day = parse_iso_date(start_value)
    end_day = parse_iso_date(args.end_date) if args.end_date else start_day
    if end_day < start_day:
        raise ValueError("--end-date must be the same as or later than --start-date")
    return start_day, end_day


def day_start_unix_millis(day: date, timezone: ZoneInfo) -> int:
    return int(round(datetime(day.year, day.month, day.day, tzinfo=timezone).timestamp() * 1000))


def half_hour_slot(date_time: str) -> int:
    hour = int(date_time[11:13])
    minute = int(date_time[14:16])
    return hour * 2 + (1 if minute >= 30 else 0)


def parse_kwh(value: str) -> float | None:
    if value == "" or value.lower() == "nan":
        return None
    return float(value)


def format_number(value: float) -> str:
    return format(value, ".12g")


def percentile(values: list[float], q: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * q))
    return ordered[index]


def tariff_code(stdor_to_u: str) -> str:
    return "0" if stdor_to_u == "Std" else "1"


def flow_feature_row(timestamp_ms: int, lcl_id: str, aggregate: DailyAggregate) -> list[str]:
    if not aggregate.half_hour_values:
        values: list[float] = []
    else:
        values = [value for _, value in aggregate.half_hour_values]

    if not values:
        return [
            str(timestamp_ms),
            lcl_id,
            tariff_code(aggregate.stdor_to_u),
            *["NaN"] * 10,
        ]

    total = aggregate.total_kwh
    max_value = max(values)
    mean_value = total / len(values)
    evening_total = sum(value for slot, value in aggregate.half_hour_values if 34 <= slot <= 45)
    night_total = sum(value for slot, value in aggregate.half_hour_values if 0 <= slot <= 11)
    peak_slot = max(aggregate.half_hour_values, key=lambda item: item[1])[0]
    zero_count = sum(1 for value in values if value == 0.0)

    return [
        str(timestamp_ms),
        lcl_id,
        tariff_code(aggregate.stdor_to_u),
        format_number(total),
        format_number(max_value),
        format_number(median(values)),
        format_number(percentile(values, 0.90)),
        format_number(pstdev(values) if len(values) > 1 else 0.0),
        format_number(evening_total / total if total > 0.0 else 0.0),
        format_number(night_total / total if total > 0.0 else 0.0),
        format_number(mean_value / max_value if max_value > 0.0 else 0.0),
        str(peak_slot),
        str(zero_count),
    ]


def collect_daily_rows(
    files: list[Path],
    start_day,
    end_day,
    max_keys: int,
    keys_per_tariff: int | None,
    timezone: ZoneInfo,
    flow_features: bool,
) -> tuple[list[tuple[int, str, list[str]]], list[str]]:
    start_day_text = start_day.isoformat()
    end_day_text = end_day.isoformat()
    selected_keys: list[str] = []
    selected_key_set: set[str] = set()
    selected_by_tariff: Counter[str] = Counter()
    aggregates: dict[tuple[str, str], DailyAggregate] = {}

    for path in files:
        for row in iter_lcl_rows(path):
            lcl_id = row[0]
            row_day = row[2][:10]
            if row_day < start_day_text or row_day > end_day_text:
                continue

            if lcl_id not in selected_key_set:
                if keys_per_tariff is None:
                    if len(selected_keys) >= max_keys:
                        continue
                elif selected_by_tariff[row[1]] >= keys_per_tariff:
                    continue
                selected_keys.append(lcl_id)
                selected_key_set.add(lcl_id)
                selected_by_tariff[row[1]] += 1

            aggregate_key = (row_day, lcl_id)
            aggregate = aggregates.get(aggregate_key)
            if aggregate is None:
                aggregate = DailyAggregate(row[1])
                aggregates[aggregate_key] = aggregate
            aggregate.row_count += 1

            value = parse_kwh(row[3])
            if value is not None:
                aggregate.add_value(half_hour_slot(row[2]), value)

    rows: list[tuple[int, str, list[str]]] = []
    for (row_day, lcl_id), aggregate in aggregates.items():
        timestamp_ms = day_start_unix_millis(parse_iso_date(row_day), timezone)
        if flow_features:
            rows.append((timestamp_ms, lcl_id, flow_feature_row(timestamp_ms, lcl_id, aggregate)))
            continue
        if not aggregate.half_hour_values:
            daily_value = "NaN"
            max_value = "NaN"
            median_value = "NaN"
        else:
            values = [value for _, value in aggregate.half_hour_values]
            daily_value = format_number(aggregate.total_kwh)
            max_value = format_number(max(values))
            median_value = format_number(median(values))
        rows.append(
            (
                timestamp_ms,
                lcl_id,
                [lcl_id, aggregate.stdor_to_u, str(timestamp_ms), daily_value, max_value, median_value],
            )
        )
    return rows, selected_keys


def write_output(
    rows: list[tuple[int, str, list[str]]],
    output_path: Path,
    timestamp_column: str,
    value_column: str,
    flow_features: bool,
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    header = FLOW_FEATURE_HEADER if flow_features else [
        "LCLid", "stdorToU", timestamp_column, value_column, OUTPUT_MAX_COLUMN, OUTPUT_MEDIAN_COLUMN]
    with output_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(header)
        for _, _, row in rows:
            writer.writerow(row)


def main() -> int:
    args = parse_args()
    input_dir = Path(args.input_dir)
    output_path = Path(args.output)
    try:
        timezone = ZoneInfo(args.timezone)
        files = lcl_files(input_dir, args.file_glob)
        if args.list_days:
            list_available_days(files, args.top_days)
            return 0
        if args.keys_per_tariff is not None and args.keys_per_tariff < 1:
            raise ValueError("--keys-per-tariff must be a positive integer")
        if args.keys_per_tariff is None and (args.max_keys is None or args.max_keys < 1):
            raise ValueError("Use --max-keys with a positive integer")

        start_day, end_day = resolve_period(args)
        rows, selected_keys = collect_daily_rows(
            files,
            start_day,
            end_day,
            args.max_keys or 0,
            args.keys_per_tariff,
            timezone,
            args.flow_features,
        )
        if not rows:
            raise ValueError(
                f"No rows collected for {start_day.isoformat()} through {end_day.isoformat()} "
                f"with the first {args.max_keys} keys"
            )
        rows.sort(key=lambda item: (item[0], item[1]))
        write_output(rows, output_path, args.timestamp_column, args.value_column, args.flow_features)
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    print(
        f"Wrote {len(rows)} daily rows for {len(selected_keys)} keys "
        f"from {start_day.isoformat()} through {end_day.isoformat()} to {output_path}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
