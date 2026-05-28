#!/usr/bin/env bash

# macOS helper script for recursively converting SVG files to PNG files.
# It uses rsvg-convert, which is available on macOS through librsvg:
#   brew install librsvg
#
# The input file is the SVG being converted, and the -o argument is the PNG
# output path. Existing PNG files with the same basename are overwritten.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <directory-containing-svg-files>" >&2
  exit 1
fi

input_dir="$1"

if [[ ! -d "$input_dir" ]]; then
  echo "Not a directory: $input_dir" >&2
  exit 1
fi

if ! command -v rsvg-convert >/dev/null 2>&1; then
  echo "rsvg-convert not found. On macOS, install it with: brew install librsvg" >&2
  exit 1
fi

find "$input_dir" -type f -name '*.svg' -print0 | while IFS= read -r -d '' svg_file; do
  png_file="${svg_file%.svg}.png"
  echo "Converting: $svg_file -> $png_file"
  rsvg-convert "$svg_file" -o "$png_file"
done
