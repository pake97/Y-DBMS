#!/usr/bin/env bash
# RQ3 incremental maintenance (needs WP3). MaintenanceBenchmarks: SnapshotPatcher.apply vs full
# recompile per edit type and batch size {1,10,100,1000} on bnlearn, covid, policy and ER/BA
# n in {1000,10000}, d=4, seed 0; a batch size runs only where the dataset has at least 4x as many
# edges. Then the causal.patch.threshold sweep {1/8,1/4,1/2,1} x |V| on the delete stream, and the
# mixed workload (Rq3Mixed, embedded Neo4j, one edit every k in {1,10,100} queries) on bnlearn,
# covid and policy. Cone sizes and fallbacks per batch go to rq3_edits.csv.
source "$(dirname "$0")/common.sh"
FAM="family=bnlearn,covid,policy"
SYN="family=synthetic n=1000,10000 degree=4 seed=0"
# sel <filters...>: DATASETS_RQ3 (ids) if set, else FAM + SYN, narrowed by the filters
sel() {
  if [ -n "${DATASETS_RQ3:-}" ]; then datasets id="$DATASETS_RQ3" "$@"
  else echo "$(datasets $FAM "$@"),$(datasets $SYN "$@")"; fi | tr , '\n' | grep -v '^$' | paste -sd, - || true
}
for b in ${RQ3_BATCHES:-1 10 100 1000}; do
  DS=$(sel minm=$((4 * b)))
  if [ -n "$DS" ]; then jmh_each "rq3/b$b" MaintenanceBenchmarks "$DS" -p batch="$b"; fi
done
jmh_each rq3_threshold MaintenanceBenchmarks "$(sel minm=40)" -p type=delete -p batch=10 -p threshold=0.125,0.25,0.5,1
mixed() {
  rm -f "$RUN/rq3_mixed.csv.partial"
  java -Xmx8g -cp "$JAR" com.example.causal.bench.run.Rq3Mixed "$RUN/rq3_mixed.csv.partial" \
    "${DATASETS_RQ3_MIXED:-${DATASETS_RQ3:-$(datasets $FAM)}}" ${RQ3_K:-}
  mv "$RUN/rq3_mixed.csv.partial" "$RUN/rq3_mixed.csv"
}
step rq3_mixed.csv mixed
