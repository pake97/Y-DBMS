#!/usr/bin/env bash
# RQ1 tiers (b) and (c): TierBCBenchmarks (masked/unmasked/reference d-separation by verdict,
# decide, findMinimal, listMinimal100) on the tier (a) datasets; the July enumeration
# (decideReference, 60 s cap per pair, censored pairs in timeouts.csv, per-pair timings in
# legacy_pairs.csv) on bnlearn, covid and policy; pgmpy, dagitty and DoWhy round trips for dsep
# (balanced triples) and adjustment on the same three families. External steps need the WP5a
# conda env (R + dagitty).
source "$(dirname "$0")/common.sh"
REAL=$(datasets family=bnlearn,covid,policy)
DS=$REAL,$(datasets family=synthetic degree=4 seed=0),$(datasets family=synthetic model=er n=1000 degree=2,8 seed=0)
DS=${DATASETS_BC:-$DS}
jmh_each tier_bc 'TierBCBenchmarks.(dsep|dsepReference|decide|findMinimal|listMinimal100)$' "$DS"
jmh_each legacy 'TierBCBenchmarks.decideReference$' "${DATASETS_LEGACY:-$REAL}"
mkdir -p "$RUN/roundtrip_bc"
for ds in $(echo "${DATASETS_EXT_BC:-$REAL}" | tr , ' '); do
  out="$RUN/roundtrip_bc/$ds.csv"
  if have "roundtrip_bc/$ds.csv"; then echo "skip roundtrip_bc/$ds.csv (exists)"; continue; fi
  rm -f "$out.partial"
  java -Xmx4g -cp "$JAR" com.example.causal.bench.run.ServeBolt "$ds" \
    "$PYTHON" -m causal4j_bench.roundtrip "$ds" "$out.partial" --balanced --libs pgmpy,dagitty,dowhy
  mv "$out.partial" "$out"
done
