#!/usr/bin/env bash
# Every Section VIII measurement (phases 5b-1 and 5b-2), then the LaTeX/figure export.
#   usage: [REUSE_EXTERNAL=<run-id>] [REUSE_RQ5=<run-id>] [RUN_ID=<id>] bench/run_all.sh [--recompute-rq5]
# Steps that run no plugin code are copied from earlier runs instead of re-measured
# (scripts/reuse.sh; provenance in reused.txt): REUSE_EXTERNAL supplies the Cypher, GDS and external
# round-trip results (datasets it lacks are measured), REUSE_RQ5 the RQ5 correctness run, which
# does not depend on timing. --recompute-rq5 ignores REUSE_RQ5 (the artifact run). Without either
# variable everything is measured. Resume an interrupted run with RUN_ID=<id>. Needs the dataset
# edge lists, which are gitignored: run `causal4j-bench fetch` and `causal4j-bench synth` first.
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
start=$(date +%s)
if [ -n "${REUSE_EXTERNAL:-}" ]; then bench/scripts/reuse.sh "$REUSE_EXTERNAL" cypher gds roundtrip; fi
if [ -n "${REUSE_RQ5:-}" ] && [ -z "$RECOMPUTE_RQ5" ]; then bench/scripts/reuse.sh "$REUSE_RQ5" rq5; fi
bench/scripts/rq1_tier_a.sh
bench/scripts/rq1_tier_bc.sh
bench/scripts/rq2.sh
bench/scripts/rq3.sh
bench/scripts/rq4.sh
bench/scripts/rq1_external.sh
bench/scripts/rq5.sh
echo "$(( $(date +%s) - start )) s" > "$RUN/wall_time.txt"
"$PYTHON" -m causal4j_bench.results_to_tex "$RUN_ID"
echo "run $RUN_ID done in $(cat "$RUN/wall_time.txt"); outputs in bench/out/$RUN_ID"
