#!/usr/bin/env bash
# Every Section VIII measurement (phases 5b-1 and 5b-2), then the LaTeX/figure export.
#   bench/run_all.sh [--recompute-rq5]               everything in one run
#   bench/run_all.sh --part 1|2|3                    one third, its own run id (run on separate days)
#   bench/run_all.sh --part 4 [--allow-commit <sha> --reason "..."]
#                                                    a rerun of the RQ3 mixed workload alone
#   bench/run_all.sh --assemble <p1> <p2> <p3> [<p4>]  one run id from the parts, then results_to_tex
# Environment: REUSE_EXTERNAL=<run-id> copies Cypher, GDS and the external round trip from an earlier
# run (datasets it lacks are measured); REUSE_RQ5=<run-id> copies the timing-independent RQ5 run
# (--recompute-rq5, or --assemble without it, measures RQ5). RUN_ID=<id> resumes an interrupted run
# or part. Needs the gitignored edge lists: run `causal4j-bench fetch` and `causal4j-bench synth` first.
#
# Groups run in the order the paper needs them, each as its own process: a failing group is logged to
# $RUN/failures.log and the run continues with the next (inside a group, a failing dataset is logged
# and the next dataset runs). Every dataset's result is written when it finishes, groups.log records
# each group's end, and after each group results_to_tex --quick refreshes bench/out/<run-id>/.
#
# Parts (no group is split; estimated hours at 2 forks on the M3 Max):
#   1  tiers (b)/(c), July enumeration, RQ3 patch vs recompile                                ~8.6 h
#   2  RQ3 mixed workload, tier (a), procedures, closure read, balanced round trips,
#      Cypher / GDS / round trip for what REUSE_EXTERNAL lacks (policy)                       ~7.8 h
#   3  RQ2 build / heap / pruned latency, RQ4 grid (seed 0), RQ3 threshold sweep              ~8.6 h
#   4  RQ3 mixed workload only (a rerun; at assembly it replaces part 2's rq3_mixed)          ~0.5 h
# Each part rebuilds bench.jar from the checkout and records the git commit, the dirty flag and the
# JVM in part.txt; parts 2-4 refuse to start unless these equal part 1's (the newest run with
# part=1 under bench/results, or PART1_RUN=<run-id>). The only exception is explicit:
# --allow-commit <sha> --reason "..." lets a part run on a different commit when <sha> is the
# checked-out commit and the reason is non-empty; the JVM and the clean tree must still match, and
# part.txt records part 1's commit and the reason. --assemble checks the parts again the same way.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MODE=all PART= RECOMPUTE_RQ5= PARTS=() ALLOW_COMMIT= REASON=
case "${1:-}" in
  "") ;;
  --recompute-rq5) RECOMPUTE_RQ5=1 ;;
  --part) MODE=part; PART=${2:-}
          case "$PART" in 1|2|3|4) ;; *) echo "usage: bench/run_all.sh --part 1|2|3|4 [--allow-commit <sha> --reason \"...\"]" >&2; exit 2 ;; esac
          shift 2
          while [ $# -gt 0 ]; do
            case "$1" in
              --allow-commit) ALLOW_COMMIT=${2:?--allow-commit needs a sha}; shift 2 ;;
              --reason) REASON=${2:?--reason needs text}; shift 2 ;;
              *) echo "unknown option $1" >&2; exit 2 ;;
            esac
          done
          if [ -n "$ALLOW_COMMIT" ] && [ -z "$REASON" ]; then echo "--allow-commit needs --reason \"...\"" >&2; exit 2; fi
          if [ -n "$REASON" ] && [ -z "$ALLOW_COMMIT" ]; then echo "--reason only goes with --allow-commit" >&2; exit 2; fi ;;
  --assemble) MODE=assemble; shift; PARTS=("$@")
          case ${#PARTS[@]} in 3|4) ;; *) echo "usage: bench/run_all.sh --assemble <p1> <p2> <p3> [<p4>]" >&2; exit 2 ;; esac ;;
  *) echo "usage: bench/run_all.sh [--recompute-rq5 | --part 1|2|3|4 [--allow-commit <sha> --reason \"...\"] | --assemble <p1> <p2> <p3> [<p4>]]" >&2; exit 2 ;;
esac

# identity: what must match across parts.
identity() {
  local dirty=0
  git -C "$ROOT" diff --quiet HEAD -- bench plugin pom.xml || dirty=1
  echo "commit=$(git -C "$ROOT" rev-parse HEAD)"
  echo "dirty=$dirty"
  echo "jvm=$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.vm.vendor|java.runtime.version/ {printf "%s ", $2}')"
}

if [ "$MODE" = part ]; then
  if [ "$PART" != 1 ]; then
    P1=${PART1_RUN:-}
    if [ -z "$P1" ]; then  # the newest run directory whose part.txt says part=1 (run ids sort by time)
      for f in "$ROOT"/bench/results/*/part.txt; do
        if grep -qx 'part=1' "$f" 2>/dev/null; then P1=$(basename "$(dirname "$f")"); fi
      done
    fi
    [ -n "$P1" ] && [ -f "$ROOT/bench/results/$P1/part.txt" ] || { echo "part $PART: no part 1 run found (set PART1_RUN=<run-id>)" >&2; exit 1; }
    P1_FILE="$ROOT/bench/results/$P1/part.txt"
    if [ -n "$ALLOW_COMMIT" ]; then
      HEAD_SHA=$(git -C "$ROOT" rev-parse HEAD)
      case "$HEAD_SHA" in "$ALLOW_COMMIT"*) ;; *)
        echo "part $PART refuses to start: --allow-commit $ALLOW_COMMIT is not the checked-out commit $HEAD_SHA" >&2; exit 1 ;; esac
      # everything but the commit must still match part 1
      if ! diff <(grep -E '^(dirty|jvm)=' "$P1_FILE") <(identity | grep -E '^(dirty|jvm)='); then
        echo "part $PART refuses to start: dirty flag or JVM differs from part 1 ($P1) (diff above)" >&2; exit 1
      fi
      echo "part $PART: JVM matches part 1 ($P1); commit $HEAD_SHA differs from part 1's $(sed -n 's/^commit=//p' "$P1_FILE"), allowed: $REASON"
    else
      if ! diff <(grep -E '^(commit|dirty|jvm)=' "$P1_FILE") <(identity); then
        echo "part $PART refuses to start: git commit or JVM differs from part 1 ($P1) (diff above)" >&2; exit 1
      fi
      echo "part $PART: commit and JVM match part 1 ($P1)"
    fi
  fi
  (cd "$ROOT" && mvn -B -q package -pl bench -am -DskipTests)  # the jar of this commit, not a stale one
  export RUN_ID=${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$(git -C "$ROOT" rev-parse --short HEAD)-p$PART}
fi
if [ "$MODE" = assemble ]; then
  for p in "${PARTS[@]}"; do
    [ -f "$ROOT/bench/results/$p/part.txt" ] || { echo "assemble: $p is not a part run (no part.txt)" >&2; exit 1; }
  done
  P1_FILE="$ROOT/bench/results/${PARTS[0]}/part.txt"
  for ((i = 1; i < ${#PARTS[@]}; i++)); do
    f="$ROOT/bench/results/${PARTS[$i]}/part.txt"
    if grep -q '^allowed_commit_reason=' "$f"; then  # ran on another commit by explicit --allow-commit
      diff <(grep -E '^(dirty|jvm)=' "$P1_FILE") <(grep -E '^(dirty|jvm)=' "$f") \
        || { echo "assemble: ${PARTS[$i]} has a different JVM or dirty flag than ${PARTS[0]}" >&2; exit 1; }
      echo "assemble: ${PARTS[$i]} ran on another commit: $(sed -n 's/^allowed_commit_reason=//p' "$f")"
    else
      diff <(grep -E '^(commit|dirty|jvm)=' "$P1_FILE") <(grep -E '^(commit|dirty|jvm)=' "$f") \
        || { echo "assemble: ${PARTS[$i]} has a different commit or JVM than ${PARTS[0]}" >&2; exit 1; }
    fi
  done
  export RUN_ID=${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$(git -C "$ROOT" rev-parse --short HEAD)-final}
fi

source "$ROOT/bench/scripts/common.sh"
for f in bench/datasets/bnlearn/munin/edges.csv bench/datasets/synthetic/ba-n10000-d8-s0/edges.csv; do
  [ -f "$f" ] || { echo "$f missing: run causal4j-bench fetch && causal4j-bench synth first" >&2; exit 1; }
done
mkdir -p "$RUN/logs"
if [ "$MODE" = part ]; then
  { echo "part=$PART"; identity
    if [ -n "$ALLOW_COMMIT" ]; then
      echo "allowed_commit_differs_from_part1=$(sed -n 's/^commit=//p' "$P1_FILE")"
      echo "allowed_commit_reason=$REASON"
    fi; } > "$RUN/part.txt"
fi
echo "run $RUN_ID ($MODE${PART:+ $PART})"
start=$(date +%s)

# group <name> <script> [args]: run one group; log its end (or failure) and refresh the tables.
group() {
  local name="$1"; shift
  local t0; t0=$(date +%s)
  echo "=== group $name ($(date -u +%FT%TZ))"
  if bash "$@"; then
    echo "$(date -u +%FT%TZ) $name done in $(( $(date +%s) - t0 )) s" >> "$RUN/groups.log"
  else
    fail "group $name failed (exit $?) after $(( $(date +%s) - t0 )) s; continuing"
    echo "$(date -u +%FT%TZ) $name FAILED" >> "$RUN/groups.log"
  fi
  "$PYTHON" -m causal4j_bench.results_to_tex "$RUN_ID" --quick > "$RUN/logs/results_to_tex.log" 2>&1 \
    || fail "results_to_tex --quick failed after group $name (see logs/results_to_tex.log)"
}
in_part() {  # part 4 exists only as an explicit part, never in a full run
  if [ "$MODE" = all ]; then [ "$1" != 4 ]; else [ "$MODE" = part ] && [ "$PART" = "$1" ]; fi
}

if [ "$MODE" = assemble ]; then
  # Directories and single-writer files by reuse.sh; side CSVs that several parts append to
  # (timeouts, rq3_edits: patch in part 1, threshold sweep in part 3) are concatenated.
  STEPS="tier_bc legacy legacy_pairs.csv rq3 rq3_mixed tier_a procedure closure_read roundtrip_bc
         cypher gds roundtrip compile rq2_pruned rq2.csv rq4 rq4_bc rq3_threshold"
  for ((i = 0; i < ${#PARTS[@]}; i++)); do
    steps=$STEPS
    # a part 4 replaces part 2's mixed workload: part 2 contributes everything else
    if [ ${#PARTS[@]} -eq 4 ] && [ $i -eq 1 ]; then steps=$(echo $STEPS | tr ' ' '\n' | grep -vx rq3_mixed | tr '\n' ' '); fi
    group "reuse-${PARTS[$i]}" bench/scripts/reuse.sh "${PARTS[$i]}" $steps
  done
  for f in timeouts.csv rq3_edits.csv; do
    : > "$RUN/$f.tmp"
    for p in "${PARTS[@]}"; do
      src="bench/results/$p/$f"
      [ -f "$src" ] || continue
      [ -s "$RUN/$f.tmp" ] || head -1 "$src" > "$RUN/$f.tmp"
      tail -n +2 "$src" >> "$RUN/$f.tmp"
    done
    if [ -s "$RUN/$f.tmp" ]; then mv "$RUN/$f.tmp" "$RUN/$f"; else rm -f "$RUN/$f.tmp"; fi
  done
  printf '%s\n' "${PARTS[@]}" > "$RUN/parts.txt"
  { echo "part=assembled"; grep -E '^(commit|dirty|jvm)=' "bench/results/${PARTS[0]}/part.txt"
    for p in "${PARTS[@]}"; do
      r=$(sed -n 's/^allowed_commit_reason=//p' "bench/results/$p/part.txt")
      if [ -n "$r" ]; then echo "part_on_other_commit=$p $(sed -n 's/^commit=//p' "bench/results/$p/part.txt"): $r"; fi
    done; } > "$RUN/part.txt"
  if [ -n "${REUSE_RQ5:-}" ]; then group reuse-rq5 bench/scripts/reuse.sh "$REUSE_RQ5" rq5
  else group rq5 bench/scripts/rq5.sh; fi
  total=0
  for p in "${PARTS[@]}"; do
    s=$(sed -n 's/^\([0-9]*\) s$/\1/p' "bench/results/$p/wall_time.txt" 2>/dev/null || true)
    total=$(( total + ${s:-0} ))
  done
  echo "$total s" > "$RUN/wall_time.txt"
else
  if [ -n "${REUSE_EXTERNAL:-}" ] && in_part 2; then
    group reuse-external bench/scripts/reuse.sh "$REUSE_EXTERNAL" cypher gds roundtrip
  fi
  if [ "$MODE" = all ] && [ -n "${REUSE_RQ5:-}" ] && [ -z "$RECOMPUTE_RQ5" ]; then
    group reuse-rq5 bench/scripts/reuse.sh "$REUSE_RQ5" rq5
  fi
  if in_part 1; then
    group tiers-bc      bench/scripts/rq1_tier_bc.sh tier_bc
    group legacy        bench/scripts/rq1_tier_bc.sh legacy
    group rq3-patch     bench/scripts/rq3.sh patch
  fi
  if in_part 4; then
    group rq3-mixed     bench/scripts/rq3.sh mixed
  fi
  if in_part 2; then
    group rq3-mixed     bench/scripts/rq3.sh mixed
    group tier-a        bench/scripts/rq1_tier_a.sh tier_a
    group procedure     bench/scripts/rq1_tier_a.sh procedure
  fi
  if in_part 3; then
    group rq2           bench/scripts/rq2.sh
    group rq4           bench/scripts/rq4.sh
    group rq3-threshold bench/scripts/rq3.sh threshold
  fi
  if in_part 2; then
    group closure-read  bench/scripts/rq1_tier_a.sh closure_read
    group roundtrip-bc  bench/scripts/rq1_tier_bc.sh roundtrip
    # with REUSE_EXTERNAL these measure only what the reused run lacks (policy)
    group cypher        bench/scripts/rq1_tier_a.sh cypher
    group gds           bench/scripts/rq1_tier_a.sh gds
    group roundtrip     bench/scripts/rq1_external.sh
  fi
  if [ "$MODE" = all ]; then group rq5 bench/scripts/rq5.sh; fi
  echo "$(( $(date +%s) - start )) s" > "$RUN/wall_time.txt"
fi

if [ "$MODE" = part ]; then
  echo "part $PART ($RUN_ID) done in $(cat "$RUN/wall_time.txt"); partial tables in bench/out/$RUN_ID"
else
  "$PYTHON" -m causal4j_bench.results_to_tex "$RUN_ID" || fail "final results_to_tex failed (see above)"
  echo "run $RUN_ID done; measured $(cat "$RUN/wall_time.txt"); outputs in bench/out/$RUN_ID"
fi
if [ -s "$RUN/failures.log" ]; then echo "failures:"; cat "$RUN/failures.log"; fi
