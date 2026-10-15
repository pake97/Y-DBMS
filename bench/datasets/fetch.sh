#!/usr/bin/env bash
# Download bnlearn .bif.gz networks into bench/datasets/bnlearn/<name>/ and verify SHA-256.
# First download of a network records its hash in checksums.txt; later runs verify it.
#   usage: fetch.sh [name ...]   (default: all eleven)
set -euo pipefail
cd "$(dirname "$0")"
names=("$@")
[ ${#names[@]} -eq 0 ] && names=(asia sachs child alarm hepar2 win95pts pathfinder andes pigs link munin)
touch checksums.txt

sha() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

for n in "${names[@]}"; do
  f="bnlearn/$n/$n.bif.gz"
  mkdir -p "bnlearn/$n"
  [ -s "$f" ] || curl -fsSL "https://www.bnlearn.com/bnrepository/$n/$n.bif.gz" -o "$f"
  got=$(sha "$f")
  want=$(awk -v f="$n.bif.gz" '$2 == f {print $1}' checksums.txt)
  if [ -z "$want" ]; then
    echo "$got  $n.bif.gz" >> checksums.txt
    sort -k2 -o checksums.txt checksums.txt
    echo "recorded $n.bif.gz $got"
  elif [ "$got" != "$want" ]; then
    echo "SHA-256 MISMATCH for $f: expected $want, got $got" >&2
    exit 1
  fi
done
