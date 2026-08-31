#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 4 ]; then
  echo "Usage: $0 USE_CASE MANIFEST_CSV RATE_PLAN_CSV OUTPUT_DIR [REPETITIONS] [WARM_UP_MILLIS] [COOL_DOWN_MILLIS] [TIMEOUT_EXTRA_SECONDS] [INPUT_CSV_PATH]" >&2
  exit 2
fi

use_case="$1"
manifest_csv="$2"
rate_plan_csv="$3"
output_dir="$4"
repetitions="${5:-3}"
warm_up_millis="${6:-0}"
cool_down_millis="${7:-0}"
timeout_extra_seconds="${8:-60}"
input_csv_path="${9:-}"
jar_path="${SHIELD_JAR:-target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar}"

cmd=(
  java
  -cp "$jar_path"
  usecase.analysis.performance.BenchmarkRankedSolutionQueries
  --use-case "$use_case"
  --manifest "$manifest_csv"
  --rate-plan "$rate_plan_csv"
  --output-dir "$output_dir"
  --repetitions "$repetitions"
  --warm-up-millis "$warm_up_millis"
  --cool-down-millis "$cool_down_millis"
  --timeout-extra-seconds "$timeout_extra_seconds"
)

if [ -n "$input_csv_path" ]; then
  cmd+=(--input-csv-path "$input_csv_path")
fi

"${cmd[@]}"
