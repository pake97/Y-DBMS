# WP5b: the benchmark harness

Instructions for Claude Code (owner: A). Read `CLAUDE.md`, `CODE-PLAN.md`, `plans/WP1.md`,
`plans/WP5a.md`, and `paper/sections/08_evaluation.tex` in the paper repository
(`../Causal4j-paper`, Overleaf-linked; never edit it from this repo except through the
export script of Step 9). Two phases: **5b-1** runs now against `feat/index` and needs
nothing from WP2; **5b-2** is wired after WP2 (tiers b and c) and WP3 (RQ3) merge. Branch
`feat/bench-harness` from `feat/index`; one PR per phase against `feat/index`.

## Goal

Produce every number and figure of Section VIII from a clean checkout with one command,
and push the generated tables and the result macros into the paper repository. No number
in the paper is typed by hand. The research questions, baselines, metrics, workloads and
the coverage matrix are already fixed in the paper; this harness measures them.

## House rules

- New Maven module `bench/`, declared in the root `pom.xml` reactor, Java 17, depends on
  `causal4j-plugin` and on `neo4j-harness` (same version as the plugin's tests) for
  embedded Neo4j. JMH 1.37 via the `jmh-core` / `jmh-generator-annprocess` dependencies;
  build a shaded `bench/target/bench.jar` with the JMH main.
- Every measurement writes machine-readable output under `bench/results/<run-id>/`
  (`run-id` = UTC timestamp + short git hash): JMH JSON (`-rf json`), CSVs for the
  non-JMH scripts, and `environment.json` (CPU model and count, RAM, OS, JVM vendor and
  version and `-Xmx`, Neo4j version, GDS version if loaded, git commit, dirty flag). Never
  overwrite a previous run.
- Inputs are only the files WP5a produced under `bench/datasets/`, `bench/workloads/`,
  `bench/reference/`. If a dataset directory is missing, fail with the command that
  regenerates it; do not synthesize data in Java.
- JMH defaults: pure-Java operators `@BenchmarkMode(SampleTime)`, 3 forks, 5 warm-up and
  10 measurement iterations of 1 s; DBMS baselines (embedded Neo4j in process) 1 fork,
  3 warm-up and 5 measurement iterations, because the embedded database is expensive to
  start. Report median and $p_{99}$ from the sample histograms; never means alone.
- A per-query timeout of 60 s for the Cypher and GDS baselines; a timed-out query is
  recorded as `timeout`, not dropped, and the figure scripts draw it as a censored point.
- `bench/run_all.sh` runs everything in order and is idempotent; `bench/run_quick.sh`
  runs a 2-minute subset (asia, alarm, one synthetic graph) for CI.

---

# Phase 5b-1 (now)

## Step 1: module skeleton and data access

`bench/pom.xml`; package `com.example.causal.bench`.

- `data/EdgeListLoader`: reads `edges.csv` (`from,to`) and `meta.json` into an
  `EdgeList(List<String> ids, int[][] edges, Set<String> latent)`; latent ids come from
  `dev/seed-dag.cypher` for covid (parse the `latent: true` flags) and are empty elsewhere.
- `data/Workloads`: reads `pairs.csv`, `triples.csv`, `triples_balanced.csv`
  (`x,y,z` with `;`-separated `z`), and `bench/reference/<ds>/{dsep,adjustment}.json`.
- `data/Datasets`: enumerates datasets by family (`bnlearn`, `covid`, `synthetic`) and
  filters by `n`, `avg_degree`, `model`, `seed` from `meta.json`.
- `graph/SnapshotFactory.fromEdgeList(EdgeList)`: builds a `CompiledCausalGraph` without
  Neo4j. The plugin's constructor is package-private, so add to the plugin a public
  factory `com.example.causal.runtime.compile.GraphBuilder.build(Collection<Variable>,
  Collection<String[]> edges, Map<String[],Double> weights)` that performs the same
  steps as `GraphLoader` after the two Neo4j passes (topological sort, constructor).
  `GraphLoader` and the test `Graphs` builder should call it too, so there is one
  construction path. This is the only plugin change of Step 1.
- `db/EmbeddedNeo4j`: starts `neo4j-harness` with the plugin's procedures registered
  (same as `CausalProceduresTest`), loads an `EdgeList` through `UNWIND` batches with a
  uniqueness constraint on `:Variable(id)`, exposes a Bolt URI (for Step 6) and a
  `GraphDatabaseService` (for Steps 4 and 5), and calls `causal.compile`.

## Step 2: the DFS baseline switch and the layer-pruned branch (plugin change, small)

Two things the paper states that the code does not have yet:

1. `-Dcausal.index=off`: `ReachabilityIndex` reads the system property once in its
   constructor and routes `ancestors`/`descendants`/`hasDirectedPath` to the `*Dfs`
   methods when it is `off`. WP2 will do the same for its `*Reference` paths; here only
   reachability is needed.
2. The layer-pruned branch of Section V ("When closures stop paying"): a compile option
   `-Dcausal.closures.budget=<bytes>` (default unlimited). When $2\,n\,\lceil n/64\rceil
   \cdot 8$ exceeds the budget, `CompiledCausalGraph` skips the two closure sweeps
   (`ancestorBits`/`descendantBits` stay `null`, `hasClosures()` returns false) and
   `ReachabilityIndex` answers `ancestors`/`descendants` with a DFS that **prunes by
   layer**: when searching ancestors of `v`, never expand a node whose `layer` is not
   smaller than the current node's; when testing `reaches(u, v)`, abandon any branch
   whose layer is not below `layer(v)`. Keep the change in `CompiledCausalGraph` to the
   guard around the two sweeps plus the accessor; WP2 touches the same class, so keep the
   diff minimal and rebase early.

Tests: `IndexParityTest` runs once more with `causal.closures.budget=1` and must pass
through the pruned branch; a unit test shows the pruned DFS visits fewer nodes than the
plain DFS on `Graphs.randomDag(1000, 0.003, 0)`.

## Step 3: tier (a) operators, index vs DFS (RQ1, RQ4)

`jmh/TierABenchmarks`: `@State` holds the snapshot for one dataset and the workload;
`@Param` over dataset ids (all bnlearn, covid, synthetic with $\bar d = 4$, seed 0, every
$n$) and over `index ∈ {on, off}`. Benchmarks: `ancestors`, `descendants`, `reaches`,
`confounders`, `mediators`, each iterating one workload pair per invocation
(`@OperationsPerInvocation(1)`). Also `compile` (build from edge list, both budget
settings) for RQ2.

Script `bench/scripts/rq1_tier_a.sh` runs the JMH class and `bench/scripts/rq4.sh` runs
it over the synthetic grid (all three degrees, seeds 0–4) with `index=on` only.

## Step 4: Cypher baseline

`db/CypherBaseline` with one Cypher string per operator, executed through
`GraphDatabaseService.executeTransactionally` with the 60 s timeout:

```
ancestors    MATCH (x:Variable)-[:CAUSES*]->(v:Variable {id:$id}) RETURN DISTINCT x.id
descendants  MATCH (v:Variable {id:$id})-[:CAUSES*]->(x:Variable) RETURN DISTINCT x.id
reaches      RETURN EXISTS { MATCH (:Variable {id:$u})-[:CAUSES*]->(:Variable {id:$v}) } AS r
confounders  MATCH (x:Variable)-[:CAUSES*]->(:Variable {id:$t}) WITH collect(DISTINCT x) AS a
             MATCH (x:Variable)-[:CAUSES*]->(:Variable {id:$y}) WHERE x IN a RETURN DISTINCT x.id
mediators    MATCH (:Variable {id:$t})-[:CAUSES*]->(m:Variable)-[:CAUSES*]->(:Variable {id:$y}) RETURN DISTINCT m.id
```

Run through JMH (`jmh/CypherBenchmarks`, 1 fork) over the same datasets as Step 3; the
variable-length patterns will time out on dense synthetic graphs, which is a result.
Record `timeout` per query in a side CSV.

## Step 5: GDS baseline

Add `org.neo4j.gds` dependencies (`proc`, `algo`, `core`, version compatible with the
pinned Neo4j; check the GDS compatibility table) and register the GDS procedures in
`EmbeddedNeo4j`. Project the graph once (`gds.graph.project('g', 'Variable', 'CAUSES')`),
then: `ancestors` = `gds.dfs.stream` on the reverse projection (`orientation: 'REVERSE'`),
`descendants` = `gds.dfs.stream` forward, `reaches` = `gds.bfs.stream` with
`targetNodes`. GDS has no path-role or d-separation operator; the coverage matrix of the
paper (Table IV) already says so. If the GDS artifacts cannot be resolved from Maven
Central for the pinned Neo4j version, implement the GDS baseline against an external
Neo4j server with GDS installed, driven over Bolt from the same harness, and record
`gds.mode = external` in `environment.json`; do not silently skip it.

## Step 6: external round-trip baseline

`bench/py/causal4j_bench/roundtrip.py` (extends the WP5a package): given a Bolt URI,
export the DAG (`MATCH (a:Variable)-[r:CAUSES]->(b:Variable) WHERE coalesce(r.active,true)
RETURN a.id, b.id` plus the node list), build the library object, run every operator of
the workload, and record `export_ms`, `build_ms`, `query_ms` per operator per library:
pgmpy (`BayesianNetwork`: ancestors via `networkx`, d-separation via `is_dconnected`,
adjustment via its backdoor API), dagitty through rpy2 (`ancestors`, `dseparated`,
`adjustmentSets(type="canonical")`), causal-learn (reachability only, through its graph
class), DoWhy (`CausalModel.identify_effect` for the adjustment decision). The Java
harness starts the embedded database, prints the Bolt URI, and invokes the script;
`bench/scripts/rq1_external.sh` runs it on bnlearn and covid (the external libraries are
not competitive on $10^4$-node graphs and the paper does not claim they are; run one
synthetic size per degree to show the trend).

## Step 7: RQ2, index cost and crossover

`bench/scripts/rq2.sh`: for every dataset and both budget settings, measure build time
(JMH `compile` from Step 3) and resident memory: after `System.gc()`, the difference of
`MemoryMXBean.getHeapMemoryUsage().getUsed()` before and after holding the snapshot,
plus the analytic closure size; then latency of `ancestors` and `reaches` on both
branches. Output `rq2.csv` with columns `dataset,n,m,branch,build_ms_median,heap_bytes,
closure_bytes,ancestors_us_median,reaches_us_median`. The crossover is reported as the
$n$ at which the closure branch's heap exceeds the budget for budgets
$\{64\,\text{MB}, 256\,\text{MB}, 1\,\text{GB}\}$, together with the latency ratio of
the two branches at that $n$.

## Step 8: results to LaTeX

`bench/py/causal4j_bench/results_to_tex.py <run-id>`: reads the run directory, computes
medians and $p_{99}$, and writes to `bench/out/<run-id>/`:

- `tables/datasets.tex` (already produced by WP5a `stats.py`; copy it here so one
  directory holds everything the paper inputs);
- `tables/rq1_tier_a.tex` (operator × system, median µs, one row per bnlearn network
  plus one per synthetic degree at $n = 10^3$), `tables/rq2.tex`;
- `figures/rq4_latency.pdf` and `figures/rq2_crossover.pdf` through matplotlib, one
  line per system, log-log, censored timeouts as open markers; style: grayscale-safe,
  8 pt labels, `\columnwidth` size (3.4 in);
- `numbers.tex` with `\providecommand` lines for every macro the paper uses. Phase 1
  writes the RQ1/RQ2/RQ4 headline macros (`\speedupTierAvsDfs`, `\speedupTierAvsCypher`,
  `\buildMsMunin`, `\closureKbMunin`, `\crossoverNodes`) and leaves the eight RQ5
  macros and the RQ3 macros as `\TODO{...}` until 5b-2 fills them. The paper's own
  `numbers.tex` is replaced wholesale by this file, so every macro it defines must be
  defined here.

Check the macro list against `grep -o '\\num[A-Za-z]*\|\\speedup[A-Za-z]*\|\\build[A-Za-z]*\|\\closure[A-Za-z]*\|\\crossover[A-Za-z]*' ../Causal4j-paper/sections/*.tex | sort -u`
and fail if any used macro is missing.

## Step 9: export to the paper repository

`bench/export_paper.sh <run-id> [paper-dir=../Causal4j-paper]`: `git -C <paper-dir> pull
--rebase`, copy `tables/*.tex`, `figures/*.pdf`, `numbers.tex` into the paper directory,
then print `git -C <paper-dir> status --short` and stop. The commit and push are done by
a human, who then pulls in Overleaf. The script never commits.

## Step 10: CI and docs

`run_quick.sh` in a third CI job (`bench-quick`), 1 fork, asia + alarm + one synthetic
graph, index on/off and Cypher only; must finish under 5 minutes. `bench/README.md`: a
table mapping every paper table, figure and macro to the script that produces it, and
the full `run_all.sh` wall time measured once on your machine.

## Phase 5b-1 done when

- `bench/run_all.sh` completes on a clean checkout and `export_paper.sh` leaves the paper
  repository compiling (12 pages, Table III unchanged, RQ1/RQ2/RQ4 figures present).
- `IndexParityTest` passes with and without closures.
- `environment.json` is present in the run directory and `bench-quick` is green in CI.
- The PR description contains the RQ1 tier (a) table and the RQ4 figure as images.

---

# Phase 5b-2 (after WP2 and WP3 merge)

## Step 11: tiers (b) and (c)

`jmh/TierBCBenchmarks` over the balanced triples and the pairs: `dsep` with
`causal.dsep.mask ∈ {true,false}` and `index ∈ {on,off}` (the `*Reference` path from
WP2), `adjustmentDecide`, `adjustmentListMinimal(limit=100)`; external round-trip for
`dsep` and `decide` through Step 6's script. `tables/rq1_tier_bc.tex`; macros
`\speedupDsepMasked`, `\speedupDecideVsEnumeration` (the latter measured against the
July level-wise enumeration kept as `AdjustmentReference` by WP2, with a 60 s cap and
censoring).

## Step 12: RQ5

`bench/scripts/rq5.sh`: run Causal4j's `dsep`, canonical `decide`, and
`listMinimal(limit=100)` on every workload item of every dataset with a reference file,
compare with `bench/reference/`: verdict equality; canonical-set equality; family equality
where the reference family is non-null; for null families, write the streamed sets to
`rq5_to_validate.json` and call `causal4j-bench reference --validate` (WP5a) which checks
each set with dagitty's `isAdjustmentSet` and removal-minimality. Fill the eight RQ5
macros: `\numDsepQueries`, `\numDsepAgree`, `\numPairs`, `\numCanonicalAgree`,
`\numPairsNotIdentifiable`, `\numFamiliesCompared`, `\numFamiliesEqual`,
`\numFamiliesChecked`. Any disagreement is written to `rq5_disagreements.csv` with both
answers; the run does not fail, the paper paragraph explains it.

## Step 13: RQ3

`jmh/MaintenanceBenchmarks` and `bench/scripts/rq3.sh`: random edit streams per
dataset (insert, delete, `active` flip, `latent` flip, add/remove variable) at batch
sizes $\{1, 10, 100, 1000\}$, measuring `SnapshotPatcher.apply` against full recompile
(`GraphBuilder.build` from the updated edge list), the cone sizes (`|an(u)|`, `|de(v)|`)
per edit, and the fallback counter from `causal.inspectSnapshot`. Mixed workload: a
stream interleaving one edit every $k \in \{1, 10, 100\}$ queries, query latency observed
under (i) external re-export (Step 6 script re-exporting after each edit), (ii)
invalidate-and-recompile, (iii) patch. `figures/rq3_incremental.pdf` (two panels),
macros `\patchVsRecompileInsert`, `\patchVsRecompileDelete`, `\fallbackThreshold`,
`\mixedWorkloadSpeedup`.

## Phase 5b-2 done when

- Every `\TODO{...}` default in the exported `numbers.tex` is replaced by a value, and
  the paper's `grep -c TODO` on the sections drops to the abstract/introduction headline
  placeholders only, which are filled by hand from the same macros.
- `rq5_disagreements.csv` is empty or every row is explained in the PR.
- `run_all.sh` wall time is recorded in `bench/README.md`.

## Out of scope

Statistical tests beyond medians and percentiles; distributed or multi-database setups;
re-running the dagitty reference (WP5a owns it); any text edit in the paper repository
other than the files `export_paper.sh` copies.
