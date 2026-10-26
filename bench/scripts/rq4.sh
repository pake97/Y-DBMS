#!/usr/bin/env bash
# RQ4: Y-DBMS (index on) tier (a) latency over the synthetic grid, one seed per (model, |V|, d):
# seed 0, 30 graphs (correctness, RQ5, covers all 150), and masked d-separation (connected triples)
# and decide (tiers b/c) over the same 30 graphs.
source "$(dirname "$0")/common.sh"
DS=${DATASETS_RQ4:-$(datasets family=synthetic seed=0)}
jmh_each rq4 'TierABenchmarks.(ancestors|descendants|reaches|confounders|mediators)$' "$DS" -p index=on
jmh_each rq4_bc 'TierBCBenchmarks.(dsep|decide)$' "$DS" -p mask=true -p verdict=connected
