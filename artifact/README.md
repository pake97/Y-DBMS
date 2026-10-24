# Reproducing the paper

The paper's sources live in their own repository (`janez87/Causal4j`, built as `main.tex`, the
landscape version, `full_version.tex` and `technical.tex`). Every number, table and figure in its
evaluation comes from **one benchmark run of this repository**, turned into LaTeX by
`bench/py/causal4j_bench/results_to_tex.py`. This folder says how to rerun it. The code itself is
the rest of this repository: `plugin/` (the DBMS extension), `dsl/` and `bench/` (datasets,
harness, scripts). [`bench/README.md`](../bench/README.md) maps every table, figure and `\macro`
of the paper to the script and the run files that produce it.

## The measured run

Run id **`20261022T211009Z-5c77f7c-final`**, assembled from four parts:

| part | run id | build (git tag) | groups | wall time |
|---|---|---|---|---|
| 1 | `20261019T161814Z-f6e4b64-p1` | `bench-final` (f6e4b64) | tiers (b)/(c), July enumeration, RQ3 patch vs recompile | 6.6 h |
| 2 | `20261020T204410Z-f6e4b64-p2` | `bench-final` | RQ3 mixed workload (replaced by part 4), tier (a), procedures, closure read, balanced round trips, Cypher / GDS / round trip | 4.9 h |
| 3 | `20261021T165159Z-f6e4b64-p3` | `bench-final` | RQ2, RQ4 grid, RQ3 threshold sweep | 4.2 h |
| 4 | `20261022T202333Z-5c77f7c-p4` | `bench-final-p4` (5c77f7c) | RQ3 mixed workload, rerun¹ | 0.8 h |

¹ Part 2's RQ3 mixed workload paid the INFO structure dump inside timed recompiles; part 4 reran
it on `bench-final` plus that fix (`--allow-commit`, recorded in its `part.txt`).

Reused, not re-measured (they run no plugin code or do not depend on timing): Cypher, GDS and
the external round trip from the Phase 1 run `20261002T201303Z-4fb92af`, and RQ5 from
`rq5-20261016T092241Z-2db519e` (provenance per file in each run's `reused.txt`).

Machine: Apple M3 Max, 16 cores, 128 GB, macOS 26.5.1, Oracle JDK 21.0.2, JMH forks at
`-Xmx4g`, Neo4j 2025.05.0, GDS 2.19.0 embedded. Timings are single-machine: nothing else ran
during the parts.

The raw run directories (about 10 GB, `bench/results/`, gitignored) are kept by the authors and
are not in this repository; `results_to_tex` needs only the assembled run (397 MB).

## Requirements

- JDK 21 (the code needs 17 or later) and Maven 3.9.
- conda or mamba, for R + dagitty + pgmpy (reference answers, external baselines).
- About 0.5 GB of disk per run (the assembled run is 397 MB); about 17 h for the four parts.
- LaTeX (`latexmk`) and a checkout of the paper repository only for the last step.

## Steps

```bash
# 0. The build that was measured (main has later changes, e.g. WP4's derived worlds and the
#    union threshold of the incremental patch, which change RQ3's fallback counts)
git checkout bench-final

# 1. Python environment (bench/README.md)
mamba create -n causal4j-bench -c conda-forge python=3.11 r-base r-dagitty rpy2 pgmpy networkx numpy pytest
mamba activate causal4j-bench
pip install -e 'bench/py[r,test,plots,external]'

# 2. Inputs: the gitignored edge lists (workloads and reference answers are committed)
causal4j-bench fetch
causal4j-bench synth

# 3. Checks: every test (about 40 min), then the benchmark pipeline end to end (< 5 min)
mvn test
bench/run_quick.sh

# 4. The measured run, one part per session, nothing else on the machine
bench/run_all.sh --part 1
REUSE_EXTERNAL=<phase-1 run> bench/run_all.sh --part 2
bench/run_all.sh --part 3
git checkout bench-final-p4
bench/run_all.sh --part 4 --allow-commit <part-1 commit> --reason "RQ3 mixed workload rerun"

# 5. One run id from the parts, then tables, figures and numbers.tex in bench/out/<run-id>/
REUSE_RQ5=<rq5 run> bench/run_all.sh --assemble <p1> <p2> <p3> <p4>

# 6. Into the paper checkout (never commits), then latexmk on the three versions
bench/export_paper.sh <run-id> <paper checkout>
```

Without the Phase 1 and RQ5 runs, leave `REUSE_EXTERNAL` / `REUSE_RQ5` unset: part 2 then
measures Cypher, GDS and the external round trip (add about 1.6 h) and the assembly measures RQ5. `bench/README.md` documents every flag, the run layout and what each part
contains.

To regenerate the tables from the measured run without rerunning anything:

```bash
python -m causal4j_bench.results_to_tex 20261022T211009Z-5c77f7c-final --paper-dir <paper checkout>
```
