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
# BASE_RUN=<id>[,<id>...]: reuse every output an earlier run already has (step skips it here)
# and record it in base_runs.txt, which results_to_tex reads to merge the runs (newest wins).
if [ -n "${BASE_RUN:-}" ]; then
  for b in ${BASE_RUN//,/ }; do
    [ -d "bench/results/$b" ] || { echo "BASE_RUN $b not found under bench/results" >&2; exit 1; }
    grep -qx "$b" "$RUN/base_runs.txt" 2>/dev/null || echo "$b" >> "$RUN/base_runs.txt"
  done
fi

# JMH with the timeout side-CSV wired in (forks inherit -D from this JVM). Extra
# JMH options for quick runs come from $JMH_OPTS.
jmh() { java -Dbench.timeouts="$ROOT/$RUN/timeouts.csv" -jar "$JAR" "$@" ${JMH_OPTS:-}; }
datasets() { java -cp "$JAR" com.example.causal.bench.run.ListDatasets "$@"; }
# fail <message>: log a failure to $RUN/failures.log (and stderr); the caller continues.
fail() { echo "$(date -u +%FT%TZ) $*" | tee -a "$ROOT/$RUN/failures.log" >&2; }
# jmh_each <dir> <benchmark-regex> <datasets,comma,separated> [jmh args...]: one JMH run per
# dataset into <dir>/<dataset>.json, written as soon as that dataset finishes, so an interrupted
# run resumes per dataset. JMH output goes to logs/; a dataset whose JMH run fails, or whose
# output reports a benchmark <failure> (JMH then still exits 0 and writes the other results), is
# logged with fail and the loop goes on to the next dataset.
jmh_each() {
  local dir="$1" bench="$2" ds="$3"; shift 3
  mkdir -p "$RUN/$dir" "$RUN/logs"
  for d in ${ds//,/ }; do
    if have "$dir/$d.json"; then echo "skip $dir/$d.json (exists)"; continue; fi
    echo "== $RUN/$dir/$d.json"
    local log="$RUN/logs/${dir//\//_}-$d.log"
    if ! jmh "$bench" -p dataset="$d" "$@" -rf json -rff "$RUN/$dir/$d.json.partial" 2>&1 | tee "$log"; then
      fail "$dir/$d: JMH exited non-zero (see ${log#"$RUN"/})"
    elif grep -q '<failure>' "$log"; then
      fail "$dir/$d: a benchmark failed, the others are kept (see ${log#"$RUN"/})"
    fi
    if [ -s "$RUN/$dir/$d.json.partial" ]; then mv "$RUN/$dir/$d.json.partial" "$RUN/$dir/$d.json"; fi
  done
}
# part <name>: true if the script was called with no part argument (run everything) or with <name>.
part() { [ -z "${PART:-}" ] || [ "$PART" = "$1" ]; }
# have <relative-path>: true if this run or one of its base runs already has the output.
# FORCE_STEPS=<dir>[,<dir>...] re-measures those steps even when a base run has them
# (e.g. after a plugin change); results_to_tex then prefers this run's files.
have() {
  if [ -s "$RUN/$1" ]; then return 0; fi
  case ",${FORCE_STEPS:-}," in *",${1%%/*},"*) return 1 ;; esac
  if [ -f "$RUN/base_runs.txt" ]; then
    while read -r b; do [ -s "bench/results/$b/$1" ] && return 0; done < "$RUN/base_runs.txt"
  fi
  return 1
}
# step <output-file> <command...>: run unless the output exists in this run or a base run.
step() {
  local rel="$1"; shift
  if have "$rel"; then echo "skip $rel (exists)"; return; fi
  echo "== $RUN/$rel"; "$@"
}
