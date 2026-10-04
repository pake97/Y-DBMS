#!/usr/bin/env bash
# RQ1 external round-trip (baseline d): Bolt export + pgmpy, dagitty, causal-learn, DoWhy,
# on bnlearn, covid and ER n=1000 seed 0 at each degree. Needs the WP5a conda env (R + dagitty).
source "$(dirname "$0")/common.sh"
DS=${DATASETS_EXT:-$(datasets family=bnlearn,covid),$(datasets family=synthetic model=er n=1000 seed=0)}
# One CSV per dataset (roundtrip/<ds>.csv) so an interrupted run resumes per dataset.
mkdir -p "$RUN/roundtrip"
for ds in ${DS//,/ }; do
  out="$RUN/roundtrip/$ds.csv"
  if [ -s "$out" ]; then echo "skip $out (exists)"; continue; fi
  rm -f "$out.partial"
  java -Xmx4g -cp "$JAR" com.example.causal.bench.run.ServeBolt "$ds" \
    "$PYTHON" -m causal4j_bench.roundtrip "$ds" "$out.partial"
  mv "$out.partial" "$out"
done
