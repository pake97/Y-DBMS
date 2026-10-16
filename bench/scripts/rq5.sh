#!/usr/bin/env bash
# RQ5: Causal4j (in process) against bench/reference for every dataset that has one
# (DATASETS_RQ5=a,b to restrict), then dagitty validation of the sets streamed for pairs whose
# reference family exceeded WP5a's limits. Outputs in $RUN/rq5/: rq5.csv, rq5_disagreements.csv,
# rq5_to_validate.json, rq5_validated.json. Needs the WP5a conda env (R + dagitty).
source "$(dirname "$0")/common.sh"
mkdir -p "$RUN/rq5"
step rq5/rq5_to_validate.json java -Xmx8g -cp "$JAR" com.example.causal.bench.run.Rq5Runner "$RUN/rq5" ${DATASETS_RQ5:-}
step rq5/rq5_validated.json "$PYTHON" -m causal4j_bench.cli reference --validate "$RUN/rq5/rq5_to_validate.json" \
  --workers "${RQ5_WORKERS:-12}"
