#!/usr/bin/env bash
# CI subset (< 5 min): asia, alarm, one synthetic graph; tier (a) index on/off, plus Cypher for
# ancestors and reaches (each Cypher benchmark starts its own embedded Neo4j); 1 fork, 1 iteration.
export RUN_ID=${RUN_ID:-quick-$(date -u +%Y%m%dT%H%M%SZ)}
export JMH_OPTS="-f 1 -wi 1 -i 1 -w 1 -r 1"
export DATASETS_A=asia,alarm,er-n100-d4-s0
source "$(dirname "$0")/scripts/common.sh"
step tier_a.json jmh TierABenchmarks -p dataset="$DATASETS_A" -rf json -rff "$RUN/tier_a.json"
step cypher.json jmh 'CypherBenchmarks.(ancestors|reaches)' -p dataset="$DATASETS_A" -rf json -rff "$RUN/cypher.json"
"$PYTHON" -m causal4j_bench.results_to_tex "$RUN_ID" --quick
