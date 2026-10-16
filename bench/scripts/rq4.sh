#!/usr/bin/env bash
# RQ4: Causal4j (index on) tier (a) latency over the whole synthetic grid (3 degrees, seeds 0-4),
# and masked d-separation (connected triples) and decide (tiers b/c) over the same grid.
source "$(dirname "$0")/common.sh"
DS=${DATASETS_RQ4:-$(datasets family=synthetic)}
jmh_each rq4 'TierABenchmarks.(ancestors|descendants|reaches|confounders|mediators)$' "$DS" -p index=on
jmh_each rq4_bc 'TierBCBenchmarks.(dsep|decide)$' "$DS" -p mask=true -p verdict=connected
