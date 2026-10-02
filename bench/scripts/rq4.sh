#!/usr/bin/env bash
# RQ4: Causal4j (index on) tier (a) latency over the whole synthetic grid (3 degrees, seeds 0-4).
source "$(dirname "$0")/common.sh"
jmh_each rq4 TierABenchmarks "${DATASETS_RQ4:-$(datasets family=synthetic)}" -p index=on
