#!/usr/bin/env python3

import argparse
import csv
from pathlib import Path


def looks_numeric(row: list[str]) -> bool:
    try:
        for value in row:
            float(value)
        return True
    except ValueError:
        return False


def column_index(column: str, headers: list[str]) -> int:
    if column in headers:
        return headers.index(column)
    if column.startswith("c") and column[1:].isdigit():
        index = int(column[1:]) - 1
        if 0 <= index < len(headers):
            return index
    if column.isdigit():
        index = int(column)
        if 0 <= index < len(headers):
            return index
    raise ValueError(f"Unknown column '{column}'. Available columns: {', '.join(headers)}")


def read_points(csv_path: Path, x_column: str, y_column: str) -> tuple[list[float], list[float]]:
    with csv_path.open(newline="") as file:
        rows = list(csv.reader(file))

    rows = [row for row in rows if row]
    if not rows:
        raise ValueError(f"CSV is empty: {csv_path}")

    first_row_is_data = looks_numeric(rows[0])
    if first_row_is_data:
        headers = [f"c{i + 1}" for i in range(len(rows[0]))]
        if len(headers) >= 3:
            headers[0] = "timestamp"
            headers[1] = "f1"
            headers[2] = "f2"
        data_rows = rows
    else:
        headers = [value.strip() for value in rows[0]]
        data_rows = rows[1:]

    x_index = column_index(x_column, headers)
    y_index = column_index(y_column, headers)
    xs = []
    ys = []
    for row in data_rows:
        if len(row) <= max(x_index, y_index):
            continue
        try:
            xs.append(float(row[x_index]))
            ys.append(float(row[y_index]))
        except ValueError:
            continue
    return xs, ys


def main() -> None:
    parser = argparse.ArgumentParser(description="Plot two numeric fields from a tuple CSV as a scatter plot.")
    parser.add_argument("input_csv", type=Path)
    parser.add_argument("output_image", type=Path)
    parser.add_argument("--x-column", default="f1", help="Column name or zero-based index for x values. Default: f1")
    parser.add_argument("--y-column", default="f2", help="Column name or zero-based index for y values. Default: f2")
    parser.add_argument("--title", default="Tuple CSV scatter plot")
    parser.add_argument("--point-size", type=float, default=8.0)
    parser.add_argument("--alpha", type=float, default=0.45)
    args = parser.parse_args()

    import matplotlib.pyplot as plt

    xs, ys = read_points(args.input_csv, args.x_column, args.y_column)
    if not xs:
        raise ValueError("No numeric points found for the selected columns")

    args.output_image.parent.mkdir(parents=True, exist_ok=True)
    plt.figure(figsize=(8, 6))
    plt.scatter(xs, ys, s=args.point_size, alpha=args.alpha, edgecolors="none")
    plt.xlabel(args.x_column)
    plt.ylabel(args.y_column)
    plt.title(args.title)
    plt.grid(True, linewidth=0.4, alpha=0.35)
    plt.tight_layout()
    plt.savefig(args.output_image, dpi=180)


if __name__ == "__main__":
    main()
