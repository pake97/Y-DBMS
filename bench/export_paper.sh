#!/usr/bin/env bash
# Copy a run's generated tables, figures and numbers.tex into the paper repository.
#   usage: bench/export_paper.sh <run-id> [paper-dir=../Causal4j-paper]
# Pulls the paper repo first, copies, prints its status, then builds main.tex,
# full_version.tex and technical.tex with latexmk and prints each page count (fails on
# LaTeX errors). A human reviews, commits and pushes (then pulls in Overleaf). This script
# never commits.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUN_ID=${1:?usage: export_paper.sh <run-id> [paper-dir]}
PAPER=${2:-$ROOT/../Causal4j-paper}
OUT=$ROOT/bench/out/$RUN_ID
[ -f "$OUT/numbers.tex" ] || { echo "$OUT/numbers.tex missing: run python -m ydbms_bench.results_to_tex $RUN_ID" >&2; exit 1; }
[ -d "$PAPER/.git" ] || { echo "$PAPER is not a git checkout" >&2; exit 1; }
git -C "$PAPER" pull --rebase --autostash  # keeps local edits (e.g. a new numbers-defaults line)
mkdir -p "$PAPER/tables" "$PAPER/figures"
cp "$OUT"/tables/*.tex "$PAPER/tables/"
if compgen -G "$OUT/figures/*.pdf" > /dev/null; then cp "$OUT"/figures/*.pdf "$PAPER/figures/"; fi
cp "$OUT/numbers.tex" "$PAPER/numbers.tex"
echo "copied $OUT into $PAPER; review, then commit and push by hand:"
git -C "$PAPER" status --short
# main.tex is the landscape version; full_version.tex and technical.tex share its sections.
for doc in main full_version technical; do
  echo "== latexmk $doc.tex in $PAPER"
  if ! (cd "$PAPER" && latexmk -pdf -interaction=nonstopmode -halt-on-error "$doc.tex" > /dev/null 2>&1); then
    echo "latexmk $doc.tex failed; first errors:" >&2
    grep -A3 '^!' "$PAPER/$doc.log" | head -20 >&2
    exit 1
  fi
  pages=$(sed -n "s/^Output written on $doc.pdf (\([0-9]*\) pages.*/\1/p" "$PAPER/$doc.log" | tail -1)
  echo "$doc.pdf: ${pages:-?} pages, no LaTeX errors"
done
