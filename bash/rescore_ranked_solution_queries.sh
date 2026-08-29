#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "Usage: $0 USE_CASE MANIFEST_CSV OUTPUT_CSV [INPUT_CSV_PATH] [PRIVACY_METRIC] [K] [SEMANTICS_F1_THRESHOLD] [LINKAGE_TRUE_RANK_MAX]" >&2
  echo "Example: $0 geolife-mobility outputs/geolife_oldpriv_queries.csv outputs/geolife_oldpriv_true_rank.csv" >&2
  exit 2
fi

use_case="$1"
manifest_csv="$2"
output_csv="$3"
input_csv_path="${4:-}"
privacy_metric="${5:-LINKAGE_ATTACK_TRUE_RANK_SCORE}"
k="${6:-20}"
semantics_f1_threshold="${7:-0.02}"
linkage_true_rank_max="${8:-50}"
jar_path="${SHIELD_JAR:-target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar}"

cmd=(
  java
  -cp "$jar_path"
  usecase.analysis.RescoreIndividuals
  --use-case "$use_case"
  --manifest "$manifest_csv"
  --output "$output_csv"
  --privacy-metric "$privacy_metric"
  --k "$k"
  --semantics-f1-threshold "$semantics_f1_threshold"
  --linkage-true-rank-max "$linkage_true_rank_max"
)

if [ -n "$input_csv_path" ]; then
  cmd+=(--input-csv-path "$input_csv_path")
fi

"${cmd[@]}"
