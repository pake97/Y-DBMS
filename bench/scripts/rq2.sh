#!/usr/bin/env bash
# RQ2: build time (JMH CompileBenchmarks, both branches), heap per branch (Rq2 -> rq2.csv), and
# layer-pruned latency from TierABenchmarks with budget=0; the closure-branch latency of the same
# datasets comes from the tier (a) and RQ4 runs of the same JMH class.
source "$(dirname "$0")/common.sh"
DS=${DATASETS_RQ2:-$(datasets family=bnlearn,covid),$(datasets family=synthetic seed=0)}
jmh_each compile CompileBenchmarks "$DS"
jmh_each rq2_pruned 'TierABenchmarks.(ancestors|reaches)$' "$DS" -p index=on -p budget=0
step rq2.csv java -Xmx4g -cp "$JAR" com.example.causal.bench.run.Rq2 "$RUN/rq2.csv" "$DS"
