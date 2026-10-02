# Sourced by every bench script. Sets ROOT, JAR, RUN_ID, RUN, PYTHON and helpers.
# RUN_ID=<id> reuses a run directory: steps whose output exists are skipped (resume),
# nothing is ever overwritten. A fresh RUN_ID is UTC timestamp + short git hash.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT"
JAR=bench/target/bench.jar
PYTHON=${PYTHON:-python}
[ -f "$JAR" ] || mvn -B -q package -pl bench -am -DskipTests
export RUN_ID=${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short HEAD)}
RUN=bench/results/$RUN_ID
mkdir -p "$RUN"
[ -f "$RUN/environment.json" ] || java -cp "$JAR" com.example.causal.bench.run.Environment "$RUN/environment.json"

# JMH with the timeout side-CSV wired in (forks inherit -D from this JVM). Extra
# JMH options for quick runs come from $JMH_OPTS.
jmh() { java -Dbench.timeouts="$ROOT/$RUN/timeouts.csv" -jar "$JAR" "$@" ${JMH_OPTS:-}; }
datasets() { java -cp "$JAR" com.example.causal.bench.run.ListDatasets "$@"; }
# jmh_each <dir> <benchmark-regex> <datasets,comma,separated> [jmh args...]: one JMH run per
# dataset into <dir>/<dataset>.json, so an interrupted run resumes per dataset.
jmh_each() {
  local dir="$1" bench="$2" ds="$3"; shift 3
  mkdir -p "$RUN/$dir"
  for d in ${ds//,/ }; do
    step "$dir/$d.json" jmh "$bench" -p dataset="$d" "$@" -rf json -rff "$RUN/$dir/$d.json.partial"
    if [ -f "$RUN/$dir/$d.json.partial" ]; then mv "$RUN/$dir/$d.json.partial" "$RUN/$dir/$d.json"; fi
  done
}
# step <output-file> <command...>: run unless the output already exists in this run.
step() {
  local out="$RUN/$1"; shift
  if [ -s "$out" ]; then echo "skip $out (exists)"; return; fi
  echo "== $out"; "$@"
}
