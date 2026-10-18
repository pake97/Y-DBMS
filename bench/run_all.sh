#!/usr/bin/env bash
# Every Section VIII measurement (phases 5b-1 and 5b-2), then the LaTeX/figure export.
#   usage: [REUSE_EXTERNAL=<run-id>] [REUSE_RQ5=<run-id>] [RUN_ID=<id>] bench/run_all.sh [--recompute-rq5]
# Groups run in the order the paper needs them, each as its own process: a failing group is
# logged to $RUN/failures.log and the run continues with the next one (inside a group, a failing
# dataset is logged and the next dataset runs). Every dataset's result is written when it finishes,
# and after each group results_to_tex --quick refreshes bench/out/<run-id>/ with what exists so far.
# Steps that run no plugin code are copied from earlier runs (scripts/reuse.sh; provenance in
# reused.txt): REUSE_EXTERNAL supplies Cypher, GDS and the external round trip (datasets it lacks are
# measured at the end), REUSE_RQ5 the timing-independent RQ5 run; --recompute-rq5 ignores the latter.
# Resume an interrupted run with RUN_ID=<id>. Needs the gitignored edge lists: run
# `causal4j-bench fetch` and `causal4j-bench synth` first.
RECOMPUTE_RQ5=
case "${1:-}" in
  --recompute-rq5) RECOMPUTE_RQ5=1 ;;
  "") ;;
  *) echo "usage: bench/run_all.sh [--recompute-rq5]" >&2; exit 2 ;;
esac
source "$(dirname "$0")/scripts/common.sh"
for f in bench/datasets/bnlearn/munin/edges.csv bench/datasets/synthetic/ba-n10000-d8-s0/edges.csv; do
  [ -f "$f" ] || { echo "$f missing: run causal4j-bench fetch && causal4j-bench synth first" >&2; exit 1; }
done
mkdir -p "$RUN/logs"
start=$(date +%s)

# group <name> <script> [part]: run one group; log its end (or failure) and refresh the tables.
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

if [ -n "${REUSE_EXTERNAL:-}" ]; then group reuse-external bench/scripts/reuse.sh "$REUSE_EXTERNAL" cypher gds roundtrip; fi
if [ -n "${REUSE_RQ5:-}" ] && [ -z "$RECOMPUTE_RQ5" ]; then group reuse-rq5 bench/scripts/reuse.sh "$REUSE_RQ5" rq5; fi

group tiers-bc          bench/scripts/rq1_tier_bc.sh tier_bc
group legacy            bench/scripts/rq1_tier_bc.sh legacy
group rq3-patch         bench/scripts/rq3.sh patch
group rq3-mixed         bench/scripts/rq3.sh mixed
group tier-a            bench/scripts/rq1_tier_a.sh tier_a
group procedure         bench/scripts/rq1_tier_a.sh procedure
group rq2               bench/scripts/rq2.sh
group rq4               bench/scripts/rq4.sh
group rq3-threshold     bench/scripts/rq3.sh threshold
group closure-read      bench/scripts/rq1_tier_a.sh closure_read
group roundtrip-bc      bench/scripts/rq1_tier_bc.sh roundtrip
# Reused above; these measure only what the reused runs lack (policy), or everything without reuse.
group cypher            bench/scripts/rq1_tier_a.sh cypher
group gds               bench/scripts/rq1_tier_a.sh gds
group roundtrip         bench/scripts/rq1_external.sh
group rq5               bench/scripts/rq5.sh

echo "$(( $(date +%s) - start )) s" > "$RUN/wall_time.txt"
"$PYTHON" -m causal4j_bench.results_to_tex "$RUN_ID" || fail "final results_to_tex failed (see above)"
echo "run $RUN_ID done in $(cat "$RUN/wall_time.txt"); outputs in bench/out/$RUN_ID"
if [ -s "$RUN/failures.log" ]; then echo "failures:"; cat "$RUN/failures.log"; fi
