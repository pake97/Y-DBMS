# WP5a: datasets, synthetic generator, workloads, and the reference answers

Instructions for Claude Code (owner: A). This is the data half of CODE-PLAN.md WP5. It
depends on nothing in the Java modules, so it can run on any branch; create it on
`feat/bench-data` from `feat/index` and open a PR against `feat/index`. Everything here is
Python plus data files, under a new top-level `bench/` directory. The Java harness (JMH,
baselines) is WP5b and comes later.

## Goal

Produce, reproducibly and from fixed seeds, every input the evaluation of
`paper/sections/08_evaluation.tex` needs: the eleven bnlearn DAGs as edge lists, the
synthetic ER/BA DAG grid, the per-dataset query workloads, Table III's numbers, and the
reference answers from dagitty and pgmpy that RQ5 compares against and that WP2's tests
read as a fixture.

## Layout

```
bench/
  README.md                  what each command produces, and which paper table/figure uses it
  py/
    pyproject.toml           package causal4j_bench; deps: networkx, numpy, pandas, pgmpy, pyyaml; extras: [r] rpy2 for dagitty
    causal4j_bench/
      bif.py                 structure-only .bif parser -> networkx.DiGraph
      synthetic.py           ER / BA DAG generator
      workloads.py           pairs and triples
      stats.py               |V|, |E|, longest path, closure bytes, density
      reference.py           dagitty + pgmpy answers (d-separation verdicts, canonical and minimal adjustment sets)
      cli.py                 `causal4j-bench fetch|synth|workloads|stats|reference|all`
    tests/                   pytest: parser on asia; generator acyclic + reproducible; workloads reproducible; reference on 3 toy DAGs
  datasets/
    fetch.sh                 downloads the .bif.gz files, verifies SHA-256 from checksums.txt
    checksums.txt            recorded on first successful download
    bnlearn/<name>/edges.csv, meta.json                 (gitignored except meta.json)
    synthetic/<model>-n<N>-d<D>-s<S>/edges.csv, meta.json (gitignored except meta.json)
    covid/edges.csv, meta.json                          (committed; parsed from dev/seed-dag.cypher)
    datasets.csv             Table III
  workloads/<dataset>/pairs.csv, triples.csv            (committed; small)
  reference/<dataset>/dsep.json, adjustment.json        (committed; the RQ5 oracle and WP2's fixture)
```

`edges.csv` has a header `from,to` and one edge per line, ids as strings. `meta.json`
records source, version/URL or generator parameters, seed, and the git commit of the
generator. Nothing in `bench/datasets/**/edges.csv` for bnlearn or synthetic is
committed; `fetch.sh` and `causal4j-bench synth` regenerate them byte-identically.

## Step 1: bnlearn networks

`fetch.sh` downloads from `https://www.bnlearn.com/bnrepository/<name>/<name>.bif.gz`
for: asia, sachs, child, alarm, hepar2, win95pts, pathfinder, andes, pigs, link, munin.
On first run it writes `checksums.txt`; on later runs it verifies. Fail loudly on a
mismatch. `bif.py` parses only `variable <name>` and `probability ( <child> | <p1>, <p2>, ... )`
headers (the `.bif` grammar puts parents after the bar; roots have no bar); ignore the
tables entirely. Write `edges.csv` and `meta.json` (`{"source":"bnlearn","name":...,"url":...,"sha256":...}`).

Check: `asia` must give 8 nodes and 8 edges; the paper's Table III currently lists
`sachs 11/17, child 20/25, alarm 37/46, hepar2 70/123, win95pts 76/112, pathfinder 109/195,
andes 223/338, pigs 441/592, link 724/1125, munin 1041/1397`. Those were written from
memory. Print a diff between the parsed counts and that list; the parsed counts win, and
`stats.py` output is what goes into the paper.

## Step 2: COVID DAG

Parse `dev/seed-dag.cypher` with two regexes (`{id: '...'`, `{from: '...', to: '...'`)
into `datasets/covid/edges.csv`. Assert 25 nodes and 78 edges. Commit it.

## Step 3: synthetic generator

`synthetic.py`:

```
generate(n, avg_degree, model, seed) -> DiGraph
  rng = numpy default_rng(seed)
  order = rng.permutation(n)                      # position in `order` is the topological rank
  er: m = round(avg_degree * n / 2) edges: sample m distinct unordered pairs uniformly, orient low rank -> high rank
  ba: Barabási–Albert with m = round(avg_degree / 2) edges per new node (networkx.barabasi_albert_graph(n, m, seed)),
      orient low rank -> high rank using `order`
  node ids "v0".."v{n-1}" assigned by rank, so edge (vi -> vj) always has i < j
```

Grid: `n ∈ {100, 316, 1000, 3162, 10000}`, `avg_degree ∈ {2, 4, 8}`, `model ∈ {er, ba}`,
`seed ∈ {0,1,2,3,4}`: 150 graphs. `meta.json` holds all four parameters. Test: regenerate
twice and compare bytes; assert acyclic (trivially true by construction, test it anyway);
assert `|E|` within 5% of `avg_degree * n / 2`.

## Step 4: workloads

For every dataset directory (bnlearn, synthetic, covid), from `seed = 20261111`:

- `pairs.csv` (`t,y`): 200 pairs with `y` a descendant of `t` (networkx
  `descendants`); sample `t` uniformly among nodes with at least one descendant, then
  `y` uniformly among its descendants; no duplicate pairs; if fewer than 200 distinct
  pairs exist (small networks), take all of them.
- `triples.csv` (`x,y,z`; `z` as `;`-separated ids, empty allowed): 50 triples for each
  `|z| ∈ {0, 1, 3, 10}` with `x ≠ y`, `z` disjoint from `{x,y}`, uniform; for networks
  with fewer than 12 nodes use the largest feasible `|z|` and record it in `meta.json`.

Commit the workload files (they are small; ~150 × 2 files for synthetic is fine).

## Step 5: dataset statistics (Table III)

`stats.py` writes `datasets/datasets.csv` with columns
`dataset, family, n, m, density, longest_path, closure_bytes` where
`closure_bytes = 2 * n * ceil(n/64) * 8`. Also emit `paper/tables/datasets.tex` (a
`tabular` body, one row per bnlearn network plus covid plus one summary row for
synthetic) so Table III is generated, not typed. Do not edit
`paper/sections/08_evaluation.tex` in this WP; the paper will `\input` the generated
table in the results pass.

## Step 6: reference answers (RQ5 oracle, WP2 fixture)

`reference.py` computes, for every dataset and its workload:

- `dsep.json`: for each triple, `{"x":..,"y":..,"z":[..],"pgmpy": bool, "dagitty": bool}`
  using `pgmpy.models.BayesianNetwork.is_dconnected` (negated) and dagitty's `dseparated`
  through `rpy2` when R and the `dagitty` package are available; otherwise write `null`
  for dagitty and record in `meta.json` that dagitty was unavailable. The two verdicts
  must agree wherever both exist; assert it.
- `adjustment.json`: for each pair,
  `{"t":..,"y":..,"canonical":[..] | null, "minimal":[[..],..], "identifiable": bool}`.
  Canonical and minimal sets from dagitty (`adjustmentSets(g, t, y, type="canonical")`
  and `type="minimal"`, `effect="total"`); when dagitty is unavailable, compute the
  canonical set directly in Python from the definition (`an(t∪y) \ (forb ∪ {t,y})`,
  `forb = de(cn) ∪ cn ∪ {t}`, `cn = (de(t) ∩ an(y)) ∪ {y}`), test it with pgmpy's
  d-separation in the proper backdoor graph, and leave `minimal` as `null`. State the
  provenance per file in `meta.json`.

The JSON format is the one `plans/WP2.md` §6.3 expects; once this lands, Kirollos
replaces his hand-made `dagitty-adjustment.json` with `bench/reference/alarm/adjustment.json`
and `bench/reference/sachs/adjustment.json` (copy or symlink into test resources).

Cap the reference computation for synthetic graphs at `n ≤ 1000` (dagitty's R
implementation is slow on larger graphs; note the cap in `README.md` and in the paper's
RQ5 paragraph when the results are written).

## Step 7: wiring

- `causal4j-bench all` runs fetch → covid → synth → workloads → stats → reference, idempotently.
- Root `.gitignore`: `bench/datasets/bnlearn/*/edges.csv`, `bench/datasets/synthetic/*/edges.csv`,
  `bench/py/**/__pycache__/`, `bench/py/*.egg-info/`.
- CI: a second job `bench-data` that installs `bench/py`, runs its pytest, and runs
  `causal4j-bench synth --only n=100` and `workloads` on asia and covid (no downloads of
  the large networks in CI; `fetch.sh` is exercised only for asia).
- `bench/README.md`: one table mapping each output to the paper element that uses it
  (Table III, RQ1/RQ4 workloads, RQ5 reference, WP2 fixture).

## Done when

- `causal4j-bench all` from a clean checkout (with R + dagitty installed) produces every
  file above; a second run changes no committed file (`git status` clean).
- `datasets.csv` has 11 bnlearn rows, 1 covid row, 150 synthetic rows; the bnlearn `|V|`/`|E|`
  are the parsed ones and the diff against the paper's Table III is printed in the PR
  description.
- `pytest bench/py` green; CI `bench-data` job green.
- `reference/alarm/adjustment.json` and `reference/sachs/adjustment.json` exist with
  non-null `minimal` families (so dagitty was actually used) and are referenced from
  `plans/WP2.md`'s test section by path.

## Out of scope

The Java harness (JMH module, DFS/Cypher/GDS baselines, external round-trip timing,
RQ1–RQ4 scripts, `results_to_tex.py`): WP5b, after WP2 merges.
