#!/usr/bin/env bash
# Every Section VIII measurement (phases 5b-1 and 5b-2), then the LaTeX/figure export.
#   usage: [BASE_RUN=<id>] [RUN_ID=<id>] bench/run_all.sh [--recompute-rq5]
# RQ5 does not depend on timing: with BASE_RUN=<a run that has rq5/> it is reused, not recomputed
# (results_to_tex merges the runs); --recompute-rq5 measures it again (the artifact run).
# Resume an interrupted run with RUN_ID=<id>. Needs the dataset edge lists, which are gitignored:
# run `causal4j-bench fetch` and `causal4j-bench synth` first (see bench/README.md).
case "${1:-}" in
  --recompute-rq5) export FORCE_STEPS=${FORCE_STEPS:+$FORCE_STEPS,}rq5 ;;
  "") ;;
  *) echo "usage: bench/run_all.sh [--recompute-rq5]" >&2; exit 2 ;;
esac
source "$(dirname "$0")/scripts/common.sh"
for f in bench/datasets/bnlearn/munin/edges.csv bench/datasets/synthetic/ba-n10000-d8-s4/edges.csv; do
  [ -f "$f" ] || { echo "$f missing: run causal4j-bench fetch && causal4j-bench synth first" >&2; exit 1; }
done
start=$(date +%s)
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
