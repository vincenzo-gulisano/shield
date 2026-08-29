#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "Usage: $0 USE_CASE MANIFEST_CSV OUTPUT_DIR [REPETITIONS] [MIN_RUN_SECONDS] [WARM_UP_MILLIS] [COOL_DOWN_MILLIS] [INPUT_CSV_PATH]" >&2
  exit 2
fi

use_case="$1"
manifest_csv="$2"
output_dir="$3"
repetitions="${4:-3}"
min_run_seconds="${5:-30}"
warm_up_millis="${6:-0}"
cool_down_millis="${7:-0}"
input_csv_path="${8:-}"
jar_path="${SHIELD_JAR:-target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar}"

cmd=(
  java
  -cp "$jar_path"
  usecase.analysis.performance.BenchmarkRankedSolutionQueries
  --use-case "$use_case"
  --manifest "$manifest_csv"
  --output-dir "$output_dir"
  --repetitions "$repetitions"
  --min-run-seconds "$min_run_seconds"
  --warm-up-millis "$warm_up_millis"
  --cool-down-millis "$cool_down_millis"
)

if [ -n "$input_csv_path" ]; then
  cmd+=(--input-csv-path "$input_csv_path")
fi

"${cmd[@]}"
