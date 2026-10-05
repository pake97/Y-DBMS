# Causal4j: code plan for the ICDE 2027 submission

Target: ICDE 2027 Round 2, **November 11, 2026, 17:00 PT**. Plan revised on
**September 28, 2026** after WP0 and WP1 landed. This file replaces the September 26
version; the executable per-WP briefs for Claude Code live in `plans/`.

The rule from the first version still holds: **no work package starts before the
parity test of the previous one is green**, because each one builds on the arrays the
previous one produces.

Two people: A = Amedeo, K = Kirollos. Effort is in working days; WP1 took one day
with Claude Code instead of the six budgeted, so the budgets below are what the
remaining packages should cost with the same method.

## Status

| WP | What | Owner | Status | Days left |
|---|---|---|---|---|
| 0 | Repository hygiene, branch, CI | A | **done** Sep 28 (`00871a3`, `17bbdf2`, tag `v0-dfs-baseline`) | 0 |
| 1 | Dense layout + reachability closures | K + Claude Code | **done** Sep 28 on `feat/index-wp1-closures` (6 commits, 165 tests green). **Not yet merged into `feat/index`.** | 0 |
| 1.5 | Reconcile with the August branch (see below) | A | new | 2 |
| 2 | Query processing over the index | K + Claude Code | next | 4 |
| 3 | Incremental maintenance | K | reduced: port + complete | 4 |
| 4 | Server-side model registry and derived worlds | A | unchanged | 3 |
| 5 | Benchmark harness, datasets, baselines, correctness | A | seeded by August ITs | 7 |
| 6 | Artifact packaging | A | unchanged | 2 |

Immediate actions (today): merge `feat/index-wp1-closures` into `feat/index` (squash
or merge commit, either is fine), `git add plans/` and commit, then branch
`feat/index-wp2-queries` from `feat/index`.

## What changed: the August branch

The review of September 26 and the first plan were written against `master`, which
stops at PR #24 (July 21). Two remote branches contain work that never reached
`master`: `origin/bitset-reachability-index` (K, July 21) and
`origin/incremental-index-maintenance` (K, August 14, includes the former; local
`fix/listener-delete-guard` adds one test on top). Together they change 49 files
(+4.5k lines) and contain:

- a bitset index inside `CompiledCausalGraph` (`int[][]` adjacency, `getAncestorBits`,
  `getDescendantBits`, `reaches`, `idForIndex`), i.e., a second implementation of WP1
  with a different layout and API;
- `CompiledCausalGraph.patch(ChangeBatch)`: an insertion-only, copy-on-write patch
  (rows shared with the previous snapshot, widened at word boundaries) that returns
  `null` to request a full recompile on any removal, on any added edge whose endpoints
  violate the existing topological index order, and on accounting errors
  (`PatchNotApplicable`);
- `ChangeBatch` moved to `runtime/` with `Edge(from, to, weight)`, `updatedVariables`,
  `updatedEdges`;
- a `CausalGraphChangeListener` (309 lines) that handles `active` and `weight` flips on
  relationships and `latent`/`observed`/SEM flips on nodes, rejects parallel `CAUSES`
  edges, rejects edges to unknown variables, and detects cycles in `beforeCommit`
  against the snapshot plus the batch; `afterCommit` calls
  `DefaultCausalGraphRuntime.applyChange(batch)` and invalidates on `false`;
- tests: `IncrementalPatchTest` (11 cases incl. word-boundary widening and row
  aliasing), `IncrementalListenerCypherTest` (13 embedded-Neo4j cases incl. cycle
  rejection and delete-all), `ReachabilityIndexParityTest`, and three performance ITs
  (`ReachabilityPerformanceIT`, `IncrementalMaintenancePerformanceIT`,
  `ListenerPerformanceIT`);
- `CLAUDE.md` schema constraints (one `CAUSES` per pair; `MERGE ... SET`, not inline
  properties) and a `dev/seed-dag.cypher` that creates the uniqueness constraint.

The paper's `\NOTE` blocks of July were accurate for `master` and wrong for the
repository as a whole. WP1 (September 28) is therefore a second bitset index. We keep
WP1's version as the base, because its CSR layout, accessor surface (`indexOf`,
`childrenOf(int)`, `layer`, `topoPosition`, `toIds`), parity test at word boundaries,
and `Graphs` builder are what WP2 and WP3 were specified against, and we port the
rest of the August work onto it. Nothing from August is discarded: its patch logic
becomes the first version of `SnapshotPatcher`, its listener replaces the `master`
listener, and its tests become the regression suite of WP3.

---

## WP1.5. Reconcile with the August branch (Sep 29–30, A)

Branch `feat/index-wp15-reconcile` from `feat/index` after WP1 is merged.

1. **Listener and batch.** Cherry-pick, then adapt, from `fix/listener-delete-guard`:
   `runtime/ChangeBatch.java` (delete `lifecycle/ChangeBatch.java`),
   `lifecycle/CausalGraphChangeListener.java`, `lifecycle/CausalGraphExtensionFactory.java`,
   `runtime/RuntimeRegistry.java` (+5 lines), the `Variable.DEFAULT_LATENT`/`DEFAULT_OBSERVED`
   constants, and the `applyChange` method of `DefaultCausalGraphRuntime`. The cycle
   check in `beforeCommit` must call WP1's `reaches(int,int)` through `indexOf`; the
   August code used `getAncestorBits`/`idForIndex`, rename at the call sites.
2. **Patch.** Do not cherry-pick the private patch constructor of the August
   `CompiledCausalGraph`. Create `runtime/compile/SnapshotPatcher.java` with
   `static CompiledCausalGraph apply(CompiledCausalGraph g, ChangeBatch b)` returning
   `null` for "fall back", and move the August logic into it against the CSR layout
   (offsets/targets are rebuilt for touched rows; closures widened at word boundaries
   as the August code does; rows outside the cones shared, never copied). Keep
   `PatchNotApplicable` as the internal signal. `CompiledCausalGraph` gains one
   package-private constructor that takes the already-built arrays so the patcher can
   assemble a snapshot without recomputing anything.
3. **Tests.** Cherry-pick `IncrementalPatchTest`, `IncrementalListenerCypherTest`,
   `ListenerPerformanceIT`, `IncrementalMaintenancePerformanceIT`,
   `ReachabilityPerformanceIT`; port `GraphTestSupport` calls to WP1's `Graphs`;
   delete the August `ReachabilityIndexParityTest` (WP1's `IndexParityTest` supersedes
   it, verify every assertion of the old one exists in the new one first). Tag the ITs
   `slow`.
4. **Docs and seed.** Take the `CLAUDE.md` schema-constraint paragraph and the
   uniqueness-constraint statement in `dev/seed-dag.cypher`; keep 78 as the edge
   count (the August `CLAUDE.md` says 76, which is wrong).
5. **Done when** `mvn -pl plugin test` is green with the ported tests, the WP1 parity
   test still passes, and `git diff --stat feat/index` shows no change to
   `ReachabilityIndex`, `PathAnalyzer`, or the closure build.

WP3 below assumes this is in place.

---

## WP2. Query processing over the index (Sep 29 – Oct 3, K + Claude Code)

Unchanged in substance from the first plan; brief in `plans/WP2.md`. Branch
`feat/index-wp2-queries` from `feat/index`. In parallel with WP1.5; the two touch
disjoint files (WP2: `algorithms/`, `DefaultCausalGraphRuntime` query methods,
procedures; WP1.5: `lifecycle/`, `SnapshotPatcher`, `applyChange`). Merge WP1.5 first.

The three cost tiers the paper states: (a) bitset operators $O(w)$; (b) Bayes-Ball
$O(|V|+|E|)$ with bitset membership and ancestral masking; (c) identification as one
canonical-set construction plus one d-separation test, and polynomial-delay
enumeration of minimal sets.

- **2a** `PathAnalyzer` over bitsets: `confounders`, `mediators`, `causalNodes` (cn),
  `forbidden` (forb), `canonical` (adj), `colliders`, `backdoorVariables` (BFS on the
  skeleton with `long[]` visited and `forb` as the barrier). Uses WP1's
  `ancestorBits(int)`/`descendantBits(int)`/`childrenOf(int)`/`parentsOf(int)`.
- **2b** `DSeparation.separated(g, x, y, z, cutOut, cutOutOnlyTo)` over `long[]`, with
  the ancestral mask `x|y|z|⋃anc`, an `int[]` ring-buffer queue, and no allocation per
  state. Keep the `master` Bayes-Ball as `bayesBallReference` for parity.
- **2c** `Adjustment.identifiableByAdjustment(t, y)`: build `adj`, test it in the
  proper backdoor graph (cut only `t→c` for `c ∈ cn(t,y)`). This replaces the
  level-wise `combinationsOfSize` enumeration, which must be deleted.
- **2d** `Adjustment.listMinimal(t, y, limit, emit)`: `findSep`, `findMinSep`
  (two reachability passes in the moralized ancestral graph of the mask), `listMinSep`
  (branch on include/exclude, prune with `findMinSep`). Procedure
  `causal.adjustmentSets(t, y, mode ∈ {canonical, minimal, all-minimal}, limit)`;
  `causal.adjustmentCandidate` kept as an alias of `canonical` for one release.
- **Tests**: parity of every operator against the `master` implementations on all
  `Graphs` fixtures; `AdjustmentCriterionTest` with the Shpitser et al. 2010 and
  Perković et al. 2018 DAGs where backdoor and generalized criteria differ;
  `MinimalSeparatorTest` (valid, inclusion-minimal, family equals the dagitty
  reference); the dagitty JSON fixture is produced by the WP5 Python script, so WP2
  ships with a hand-checked fixture of 20 pairs on `alarm` and `hepar2` until WP5
  replaces it.

---

## WP3. Incremental maintenance: complete the August patch (Oct 1–8, K)

Branch `feat/index-wp3-maintenance` from `feat/index` after WP1.5 and WP2 merge.
The August patch covers insertions, variable additions, property updates, and
weights, and falls back on everything else. WP3 removes the fallbacks that the paper
claims are handled, in this order:

1. **Topological relabel on insertion.** The August patch falls back whenever an
   inserted edge $u \to v$ has `topoPos[u] >= topoPos[v]`, even when the edge is
   acyclic. Replace with: if `reaches(v, u)` reject (cycle, already caught in
   `beforeCommit`); else if `topoPos[u] > topoPos[v]`, move $v$ and $\de(v)$ (the
   affected cone) after $u$ in `topoOrderIdx`/`topoPos` by a local re-sort of the cone
   in the existing relative order, and recompute `layer` over the cone. Measure how
   often this case occurs on random edit streams (it is the difference between
   "incremental" and "incremental except when the curator adds an upstream edge").
2. **Deletion by cone recompute.** For $u \to v$ removed (or deactivated), recompute
   `anc` over $C = \de(v) \cup \{v\}$ in topological order from the updated parents
   and `des` over $C' = \an(u) \cup \{u\}$ in reverse order; rows outside $C \cup C'$
   are shared. Fall back to full recompile only when $|C| + |C'| > \theta$ (default
   $|V|/2$, a runtime setting). Batches: apply all deletions as one union of cones,
   then the insertions.
3. **Variable removal.** Delete incident edges through step 2, then mark the dense
   index free (no compaction until the next full compile; `size()` excludes freed
   indices and `Graphs`/parity tests must cover a snapshot with holes).
4. **Tests.** Extend `IncrementalPatchTest` with the cases the August version marks
   as fallback (`testTopologicalViolationFallback` and `testDeactivationFallback`
   become positive tests); add `IncrementalParityTest`: 1,000 random edit sequences
   per fixture, after each edit the patched snapshot equals `GraphLoader.load`-style
   reconstruction from the maps (closures, layers, `topoPos` monotone along edges,
   adjacency). `IncrementalListenerCypherTest` gains: `active` flip changes the next
   `causal.ancestors` answer without `causal.compile`; deletion with a large cone
   triggers the fallback and the answer is still right.
5. **Done when** the fallback counter (add one to the patcher, exposed through
   `causal.inspectSnapshot`) is zero on the random edit streams except for cones
   above $\theta$, and the three August ITs run green under `-Dgroups=slow`.

If step 2 slips past October 30, the paper reports deletion as recompile-with-cone
measured against full recompile, which the August code already supports through
`invalidate()`; the text in `sections/06_incremental.tex` then loses the cone
recompute paragraph and Table II's maintenance row.

---

## WP4. Server-side model registry and derived worlds (Oct 6–9, A)

Unchanged. `RuntimeRegistry` becomes `Map<String, DefaultCausalGraphRuntime>` with
`"base"` as default; every read procedure gains an optional `model` argument;
`causal.models()`, `causal.use(name)`, `causal.intervene(name, [vars])`,
`causal.drop(name)`. `do(X)` is `SnapshotPatcher.apply(base, delete all in-edges of X)`
from WP3 step 2, so WP4 depends on WP3 step 2 for the sharing claim; until then it can
use a full copy. The DSL `Neo4jQueryExecutor` drops `hydrateCurrentGraph()` and the
`StructuralEvaluator` path for the Neo4j backend; `ModelRegistry` stays for the
in-memory backend only. Done when `DslPluginE2ETest` runs `DEFINE MODEL`, `INTERVENE`,
`USE MODEL`, structural queries on the derived world, `RESET`, `SHOW MODELS` end to end
with no client-side graph and an unchanged base `compiledAt`.

---

## WP5. Benchmark harness, datasets, baselines, correctness (Oct 6–24, A, in parallel)

Unchanged in scope; two adjustments. First, the three August ITs
(`ReachabilityPerformanceIT`, `IncrementalMaintenancePerformanceIT`,
`ListenerPerformanceIT`) are the seed of the `bench/` module: move their harness code
there, replace ad-hoc timing with JMH, and keep them as smoke tests in the plugin.
Second, WP1 already provides the DFS baseline as `ancestorsDfs`/`descendantsDfs` and
the `useIndex` flag; the `-Dcausal.index=off` switch is a one-line system-property
read in `ReachabilityIndex` plus the `bayesBallReference` path of WP2.

Deliverables as before: bnlearn loaders (`.bif`, eleven networks from `asia` to
`munin`), the seeded ER/BA DAG generator ($10^2$–$10^4$, $\bar d \in \{2,4,8\}$, five
seeds), Cypher and GDS baselines in the same embedded Neo4j, the Python round-trip
script for dagitty/pgmpy/causal-learn/DoWhy with export, build, and query time
separated, the workload files (200 $(T,Y)$ pairs and 200 $(X,Y,Z)$ triples per
dataset, fixed seeds), the coverage matrix, RQ1–RQ5 scripts including the mixed
edit/query stream under three policies, and `results_to_tex.py` emitting
`paper/numbers.tex` so no number is typed by hand. Done when `bench/run_all.sh`
regenerates every figure and table of §8 from a clean checkout.

---

## WP6. Artifact (Nov 1–4, A)

Unchanged: public repository or Zenodo snapshot, `run_all.sh`, environment file,
README mapping every figure and table to its command, AI-generated-content
acknowledgement with tools and sections, ORCIDs. Internal review round November 5–7,
buffer November 8–10.

---

## Revised timeline

| Week | K | A |
|---|---|---|
| Sep 29 – Oct 3 | WP2 | WP1.5, then WP5 datasets and generator |
| Oct 6 – 10 | WP3 steps 1–2 | WP4, WP5 baselines |
| Oct 13 – 17 | WP3 steps 3–5 | WP5 harness, RQ1/RQ2/RQ4 sweeps |
| Oct 20 – 24 | help on WP5 RQ3 (mixed workload) | WP5 RQ3/RQ5, `numbers.tex` |
| Oct 27 – 31 | paper figures | paper results sections, merge `feat/index` to `master` |
| Nov 3 – 10 | WP6, internal review, buffer | WP6, internal review, buffer |

The buffer grew by about two weeks compared with the first plan. Spend it on the RQ3
mixed workload and on the crossover measurement of RQ2, which are the two experiments
reviewers will not have seen before, not on new DSL surface.

## Things explicitly not done for this submission

ADMG / latent projection index; front-door and instrumental identification; batched
enumeration sharing work across queries; MVCC across multiple writers; dense-index
compaction after variable removal; new DSL surface beyond the `model` argument and the
`adjustmentSets` modes.
