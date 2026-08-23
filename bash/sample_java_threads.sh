#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  bash bash/sample_java_threads.sh <pid> [interval_seconds] [samples] [output_dir]

Example:
  bash bash/sample_java_threads.sh 12345 5 12

What it writes:
  output_dir/
    sample-001/
      process.txt       process, load, memory, and JVM summary
      thread-cpu.tsv    per-thread CPU measured over the interval
      top-threads.txt   top -H snapshot, if top is available
      thread-dump.txt   Java thread dump from jcmd or jstack
      hot-stacks.txt    stack excerpts for the hottest threads
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

PID="${1:?Missing pid. Run with --help for usage.}"
INTERVAL="${2:-5}"
SAMPLES="${3:-12}"
OUT_DIR="${4:-java-thread-samples-${PID}-$(date '+%Y%m%d-%H%M%S')}"

if [[ ! -d "/proc/${PID}" ]]; then
  echo "No such process: ${PID}" >&2
  exit 1
fi

HZ="$(getconf CLK_TCK)"
mkdir -p "${OUT_DIR}"

snapshot_ticks() {
  local out="$1"
  : > "${out}"
  for stat_file in "/proc/${PID}/task/"*/stat; do
    [[ -r "${stat_file}" ]] || continue
    local tid line rest state utime stime ticks name
    tid="$(basename "$(dirname "${stat_file}")")"
    line="$(<"${stat_file}")"
    rest="${line##*) }"
    read -r state _ _ _ _ _ _ _ _ _ _ utime stime _ <<<"${rest}"
    ticks=$((utime + stime))
    name="$(<"/proc/${PID}/task/${tid}/comm")"
    printf '%s\t%s\t%s\t%s\n' "${tid}" "${ticks}" "${name}" "${state}" >> "${out}"
  done
}

dump_process_info() {
  local out="$1"
  {
    echo "timestamp: $(date -Ins)"
    echo
    echo "== ps =="
    ps -p "${PID}" -o pid,ppid,user,stat,etime,time,pcpu,pmem,rss,vsz,nlwp,comm,args || true
    echo
    echo "== /proc/${PID}/status =="
    grep -E '^(Name|State|Pid|PPid|Threads|Vm|voluntary_ctxt_switches|nonvoluntary_ctxt_switches):' "/proc/${PID}/status" || true
    echo
    echo "== load =="
    uptime || true
    echo
    echo "== memory =="
    command -v free >/dev/null 2>&1 && free -h || true
    echo
    if command -v jcmd >/dev/null 2>&1; then
      echo "== jcmd VM.uptime =="
      jcmd "${PID}" VM.uptime || true
      echo
      echo "== jcmd VM.command_line =="
      jcmd "${PID}" VM.command_line || true
      echo
      echo "== jcmd GC.heap_info =="
      jcmd "${PID}" GC.heap_info || true
    else
      echo "jcmd not found"
    fi
  } > "${out}" 2>&1
}

dump_thread_dump() {
  local out="$1"
  if command -v jcmd >/dev/null 2>&1; then
    jcmd "${PID}" Thread.print -l > "${out}" 2>&1 || true
  elif command -v jstack >/dev/null 2>&1; then
    jstack -l "${PID}" > "${out}" 2>&1 || true
  else
    echo "Neither jcmd nor jstack found" > "${out}"
  fi
}

write_thread_cpu() {
  local sample="$1"
  local before="$2"
  local after="$3"
  local out="$4"
  {
    printf 'sample\ttid\tnid_hex\tthread_name\tstate\tcpu_pct\tdelta_ticks\ttotal_ticks\n'
    awk -v sample="${sample}" -v elapsed="${INTERVAL}" -v hz="${HZ}" -F '\t' '
      NR == FNR {
        previous[$1] = $2
        next
      }
      {
        tid = $1
        ticks = $2
        name = $3
        state = $4
        delta = (tid in previous) ? ticks - previous[tid] : 0
        cpu = elapsed > 0 ? (delta / hz / elapsed) * 100.0 : 0.0
        printf "%s\t%s\t0x%x\t%s\t%s\t%.2f\t%d\t%d\n", sample, tid, tid, name, state, cpu, delta, ticks
      }
    ' "${before}" "${after}" | sort -t $'\t' -k6,6nr
  } > "${out}"
}

dump_top_threads() {
  local out="$1"
  if command -v top >/dev/null 2>&1; then
    top -H -b -n 1 -p "${PID}" > "${out}" 2>&1 || true
  else
    echo "top not found" > "${out}"
  fi
}

write_hot_stacks() {
  local thread_cpu="$1"
  local thread_dump="$2"
  local out="$3"
  {
    echo "Top threads by measured CPU in this interval"
    echo
    head -n 21 "${thread_cpu}"
    echo
    tail -n +2 "${thread_cpu}" | head -n 10 | while IFS=$'\t' read -r sample tid nid name state cpu delta total; do
      echo
      echo "===== tid=${tid} nid=${nid} cpu_pct=${cpu} name=${name} state=${state} ====="
      awk -v nid="${nid}" '
        index($0, "nid=" nid) {printing = 1}
        printing {print}
        printing && /^$/ {printing = 0; exit}
      ' "${thread_dump}"
    done
  } > "${out}" 2>&1
}

echo "Sampling PID ${PID}: ${SAMPLES} samples, ${INTERVAL}s interval"
echo "Writing to ${OUT_DIR}"

for sample in $(seq 1 "${SAMPLES}"); do
  sample_label="$(printf '%03d' "${sample}")"
  sample_dir="${OUT_DIR}/sample-${sample_label}"
  mkdir -p "${sample_dir}"

  before="${sample_dir}/ticks-before.tsv"
  after="${sample_dir}/ticks-after.tsv"
  snapshot_ticks "${before}"
  sleep "${INTERVAL}"
  snapshot_ticks "${after}"

  write_thread_cpu "${sample_label}" "${before}" "${after}" "${sample_dir}/thread-cpu.tsv"
  dump_process_info "${sample_dir}/process.txt"
  dump_top_threads "${sample_dir}/top-threads.txt"
  dump_thread_dump "${sample_dir}/thread-dump.txt"
  write_hot_stacks "${sample_dir}/thread-cpu.tsv" "${sample_dir}/thread-dump.txt" "${sample_dir}/hot-stacks.txt"
  rm -f "${before}" "${after}"

  hottest="$(tail -n +2 "${sample_dir}/thread-cpu.tsv" | head -n 1)"
  echo "sample ${sample_label}/${SAMPLES}: ${hottest}"
done

echo "Done. Start with:"
echo "  ${OUT_DIR}/sample-001/thread-cpu.tsv"
echo "  ${OUT_DIR}/sample-001/hot-stacks.txt"
