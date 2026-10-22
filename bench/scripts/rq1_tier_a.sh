#!/usr/bin/env bash
# RQ1 tier (a): Causal4j in-process index vs DFS (TierABenchmarks), the closure read alone,
# Causal4j through its procedures, Cypher and GDS on bnlearn, covid, policy,
# synthetic d=4 seed 0 (every n, ER and BA) and ER n=1000 at d=2 and d=8.
PART=${1:-}  # tier_a | closure_read | procedure | cypher | gds; none: all
source "$(dirname "$0")/common.sh"
DS=$(datasets family=bnlearn,covid,policy),$(datasets family=synthetic degree=4 seed=0),$(datasets family=synthetic model=er n=1000 degree=2,8 seed=0)
DS=${DATASETS_A:-$DS}
if part tier_a; then jmh_each tier_a 'TierABenchmarks.(ancestors|descendants|reaches|confounders|mediators)$' "$DS"; fi
if part closure_read; then jmh_each closure_read 'TierABenchmarks.ancestors(Bits|Count)$' "$DS" -p index=on; fi
if part procedure; then jmh_each procedure ProcedureBenchmarks "$DS"; fi
if part cypher; then jmh_each cypher CypherBenchmarks "$DS"; fi
if part gds; then jmh_each gds GdsBenchmarks "$DS"; fi
