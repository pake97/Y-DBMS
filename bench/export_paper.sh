#!/usr/bin/env bash
# Copy a run's generated tables, figures and numbers.tex into the paper repository.
#   usage: bench/export_paper.sh <run-id> [paper-dir=../Causal4j-paper]
# Pulls the paper repo first, copies, prints its status and stops: a human reviews,
# commits and pushes (then pulls in Overleaf). This script never commits.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUN_ID=${1:?usage: export_paper.sh <run-id> [paper-dir]}
PAPER=${2:-$ROOT/../Causal4j-paper}
OUT=$ROOT/bench/out/$RUN_ID
[ -f "$OUT/numbers.tex" ] || { echo "$OUT/numbers.tex missing: run python -m causal4j_bench.results_to_tex $RUN_ID" >&2; exit 1; }
[ -d "$PAPER/.git" ] || { echo "$PAPER is not a git checkout" >&2; exit 1; }
git -C "$PAPER" pull --rebase
mkdir -p "$PAPER/tables" "$PAPER/figures"
cp "$OUT"/tables/*.tex "$PAPER/tables/"
if compgen -G "$OUT/figures/*.pdf" > /dev/null; then cp "$OUT"/figures/*.pdf "$PAPER/figures/"; fi
cp "$OUT/numbers.tex" "$PAPER/numbers.tex"
echo "copied $OUT into $PAPER; review, then commit and push by hand:"
git -C "$PAPER" status --short
