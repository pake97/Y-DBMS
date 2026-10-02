#!/usr/bin/env bash
# RQ2: build time (JMH CompileBenchmarks, both branches) and heap / latency per branch (Rq2 -> rq2.csv).
source "$(dirname "$0")/common.sh"
DS=${DATASETS_RQ2:-$(datasets family=bnlearn,covid),$(datasets family=synthetic seed=0)}
jmh_each compile CompileBenchmarks "$DS"
step rq2.csv java -Xmx4g -cp "$JAR" com.example.causal.bench.run.Rq2 "$RUN/rq2.csv" "$DS"
