#!/usr/bin/env bash
# CI subset (< 5 min): asia, alarm, one synthetic graph; tier (a) index on/off, plus Cypher for
# ancestors and reaches (each Cypher benchmark starts its own embedded Neo4j); one masked dsep
# (connected triples) and one decide on asia (tiers b/c); 1 fork, 1 iteration.
export RUN_ID=${RUN_ID:-quick-$(date -u +%Y%m%dT%H%M%SZ)}
export JMH_OPTS="-f 1 -wi 1 -i 1 -w 1 -r 1"
export DATASETS_A=asia,alarm,er-n100-d4-s0
source "$(dirname "$0")/scripts/common.sh"
jmh_each tier_a 'TierABenchmarks.(ancestors|descendants|reaches|confounders|mediators)$' "$DATASETS_A"
jmh_each cypher 'CypherBenchmarks.(ancestors|reaches)' "$DATASETS_A"
jmh_each tier_bc 'TierBCBenchmarks.(dsep|decide)$' asia -p mask=true -p verdict=connected
"$PYTHON" -m causal4j_bench.results_to_tex "$RUN_ID" --quick
