#!/usr/bin/env bash
# Every Section VIII measurement of phase 5b-1, then the LaTeX/figure export.
# Resume an interrupted run with RUN_ID=<id> bench/run_all.sh.
source "$(dirname "$0")/scripts/common.sh"
start=$(date +%s)
bench/scripts/rq1_tier_a.sh
bench/scripts/rq2.sh
bench/scripts/rq4.sh
bench/scripts/rq1_external.sh
"$PYTHON" -m causal4j_bench.results_to_tex "$RUN_ID"
echo "$(( $(date +%s) - start )) s" > "$RUN/wall_time.txt"
echo "run $RUN_ID done in $(cat "$RUN/wall_time.txt"); outputs in bench/out/$RUN_ID"
