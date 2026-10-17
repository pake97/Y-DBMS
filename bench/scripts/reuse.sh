#!/usr/bin/env bash
# reuse.sh <run-id> <step>...: copy the outputs of each <step> (a directory such as cypher/, or a
# file such as rq2.csv) from <run-id> and the runs in its base_runs.txt chain (newest first, per
# file name) into this run, unless this run already has them, with each file's source run in
# reused.txt and the step's timeouts.csv rows (system = step). For steps whose code did not change;
# datasets the source runs lack (e.g. policy) are then measured as usual. Sources: $REUSE_DIR
# (default bench/results).
source "$(dirname "$0")/common.sh"
SRC=${REUSE_DIR:-bench/results}
id=${1:?usage: reuse.sh <run-id> <step>...}; shift
[ -d "$SRC/$id" ] || { echo "reuse: $SRC/$id not found" >&2; exit 1; }
chain() {
  echo "$1"
  if [ -f "$SRC/$1/base_runs.txt" ]; then
    while read -r b; do [ -n "$b" ] && chain "$b"; done < "$SRC/$1/base_runs.txt"
  fi
}
runs=$(chain "$id" | awk '!seen[$0]++')
for step in "$@"; do
  for r in $runs; do
    src="$SRC/$r/$step"
    if [ -d "$src" ]; then
      mkdir -p "$RUN/$step"
      for f in "$src"/*; do
        name=$(basename "$f")
        case "$name" in *.partial|'*') continue ;; esac
        [ -e "$RUN/$step/$name" ] && continue
        cp "$f" "$RUN/$step/$name" && echo "$step/$name $r" >> "$RUN/reused.txt"
      done
    elif [ -f "$src" ] && [ ! -e "$RUN/$step" ]; then
      cp "$src" "$RUN/$step" && echo "$step $r" >> "$RUN/reused.txt"
    fi
    if [ -f "$SRC/$r/timeouts.csv" ]; then
      [ -f "$RUN/timeouts.csv" ] || head -1 "$SRC/$r/timeouts.csv" > "$RUN/timeouts.csv"
      awk -F, -v s="$step" 'NR > 1 && $1 == s' "$SRC/$r/timeouts.csv" >> "$RUN/timeouts.csv"
    fi
  done
  echo "reuse $step: $(grep -c "^$step" "$RUN/reused.txt" 2>/dev/null || echo 0) files from $id"
done
