#!/usr/bin/env bash
# Copy a run's generated tables, figures and numbers.tex into the paper repository.
#   usage: bench/export_paper.sh <run-id> [paper-dir=../Causal4j-paper]
# Pulls the paper repo first, copies, prints its status, then builds the paper with
# latexmk and prints the page count (fails on LaTeX errors). A human reviews, commits
# and pushes (then pulls in Overleaf). This script never commits.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUN_ID=${1:?usage: export_paper.sh <run-id> [paper-dir]}
PAPER=${2:-$ROOT/../Causal4j-paper}
OUT=$ROOT/bench/out/$RUN_ID
[ -f "$OUT/numbers.tex" ] || { echo "$OUT/numbers.tex missing: run python -m causal4j_bench.results_to_tex $RUN_ID" >&2; exit 1; }
[ -d "$PAPER/.git" ] || { echo "$PAPER is not a git checkout" >&2; exit 1; }
git -C "$PAPER" pull --rebase --autostash  # keeps local edits (e.g. a new numbers-defaults line)
mkdir -p "$PAPER/tables" "$PAPER/figures"
cp "$OUT"/tables/*.tex "$PAPER/tables/"
if compgen -G "$OUT/figures/*.pdf" > /dev/null; then cp "$OUT"/figures/*.pdf "$PAPER/figures/"; fi
cp "$OUT/numbers.tex" "$PAPER/numbers.tex"
echo "copied $OUT into $PAPER; review, then commit and push by hand:"
git -C "$PAPER" status --short
echo "== latexmk in $PAPER"
if ! (cd "$PAPER" && latexmk -pdf -interaction=nonstopmode -halt-on-error main.tex > /dev/null 2>&1); then
  echo "latexmk failed; first errors:" >&2
  grep -A3 '^!' "$PAPER/main.log" | head -20 >&2
  exit 1
fi
pages=$(sed -n 's/^Output written on main.pdf (\([0-9]*\) pages.*/\1/p' "$PAPER/main.log" | tail -1)
echo "main.pdf: ${pages:-?} pages, no LaTeX errors"
