#!/usr/bin/env bash
# RQ1 tier (a): Causal4j index vs DFS (TierABenchmarks), Cypher, GDS on bnlearn, covid,
# synthetic d=4 seed 0 (every n, ER and BA) and ER n=1000 at d=2 and d=8.
source "$(dirname "$0")/common.sh"
DS=$(datasets family=bnlearn,covid),$(datasets family=synthetic degree=4 seed=0),$(datasets family=synthetic model=er n=1000 degree=2,8 seed=0)
DS=${DATASETS_A:-$DS}
step tier_a.json jmh TierABenchmarks -p dataset="$DS" -rf json -rff "$RUN/tier_a.json"
step cypher.json jmh CypherBenchmarks -p dataset="$DS" -rf json -rff "$RUN/cypher.json"
step gds.json    jmh GdsBenchmarks    -p dataset="$DS" -rf json -rff "$RUN/gds.json"
