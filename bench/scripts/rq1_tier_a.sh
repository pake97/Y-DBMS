#!/usr/bin/env bash
# RQ1 tier (a): Causal4j in-process index vs DFS (TierABenchmarks), the closure read alone,
# Causal4j through its procedures, Cypher and GDS on bnlearn, covid, policy,
# synthetic d=4 seed 0 (every n, ER and BA) and ER n=1000 at d=2 and d=8.
source "$(dirname "$0")/common.sh"
DS=$(datasets family=bnlearn,covid,policy),$(datasets family=synthetic degree=4 seed=0),$(datasets family=synthetic model=er n=1000 degree=2,8 seed=0)
DS=${DATASETS_A:-$DS}
jmh_each tier_a 'TierABenchmarks.(ancestors|descendants|reaches|confounders|mediators)$' "$DS"
jmh_each closure_read 'TierABenchmarks.ancestors(Bits|Count)$' "$DS" -p index=on
jmh_each procedure ProcedureBenchmarks "$DS"
jmh_each cypher CypherBenchmarks "$DS"
jmh_each gds GdsBenchmarks "$DS"
