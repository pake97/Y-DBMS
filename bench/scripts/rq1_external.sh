#!/usr/bin/env bash
# RQ1 external round-trip (baseline d): Bolt export + pgmpy, dagitty, causal-learn, DoWhy,
# on bnlearn, covid and ER n=1000 seed 0 at each degree. Needs the WP5a conda env (R + dagitty).
source "$(dirname "$0")/common.sh"
DS=${DATASETS_EXT:-$(datasets family=bnlearn,covid),$(datasets family=synthetic model=er n=1000 seed=0)}
if [ -s "$RUN/roundtrip.csv" ]; then echo "skip $RUN/roundtrip.csv (exists)"; exit 0; fi
for ds in ${DS//,/ }; do
  java -Xmx4g -cp "$JAR" com.example.causal.bench.run.ServeBolt "$ds" \
    "$PYTHON" -m causal4j_bench.roundtrip "$ds" "$RUN/roundtrip.partial.csv"
done
mv "$RUN/roundtrip.partial.csv" "$RUN/roundtrip.csv"
