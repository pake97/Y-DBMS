# WP4: model registry and derived worlds

Instructions for Claude Code (owner: A). Read `CLAUDE.md`, `CODE-PLAN.md` (WP4 section),
`plans/WP1.md`, `plans/WP2.md` and `plans/WP3.md` first. Branch `feat/index-wp4-worlds`
from `feat/index` **after the WP3 PR is merged** (Step 2 of WP3, deletion by cone
recompute, is what builds a derived world). Work in `plugin/` and `dsl/`. One PR against
`feat/index`, one commit per step.

## Goal

The papers state that a database holds several named causal models, that `do(X)` defines
a derived world (the same DAG without the edges into `X`) that is named, queried and
compared with its base, that switching worlds is a name lookup on the server, and that a
derived world costs the cone recompute in time and the changed rows in memory because it
shares every untouched row with its base. Today `RuntimeRegistry` holds one
`CausalGraphRuntime`, and the DSL builds intervention worlds client-side
(`Neo4jQueryExecutor.executeIntervene`, `hydrateCurrentGraph`, `StructuralEvaluator`).
WP4 moves worlds into the server and makes those sentences true.

## Design (keep it this simple)

- A world is a **definition plus a cached snapshot**: `World(name, baseName,
  Set<String> intervened, volatile CompiledCausalGraph snapshot)`. The base model is the
  world named `base`, compiled from the stored graph exactly as today.
- A derived world's snapshot is computed by applying, to the **base snapshot**, one
  `ChangeBatch` that removes every incoming edge of every intervened variable, through
  `SnapshotPatcher.apply` (WP3 Step 2). Copy-on-write already guarantees that untouched
  rows are shared by reference with the base. If the patch returns `null` (threshold),
  build the derived snapshot with `GraphBuilder.build` from the base edge list minus the
  removed edges, and record the fallback in the world's counters.
- When the base snapshot changes (listener patch, recompile, invalidate), every derived
  world's cached snapshot is dropped, and recomputed lazily from its definition on the
  next read. A derived world never receives edits of its own; writes go to the stored
  graph, which is the base.
- The **active world** is process-wide (one `volatile String`), as the registry itself
  is process-wide today; document this next to the existing single-database note.
  Default `base`.
- Derived worlds of derived worlds: allowed, `baseName` can be any world; the definition
  is resolved recursively (union of intervened sets down to `base`).

## Step 1: registry

`RuntimeRegistry` keeps its static API for the base (`getInstance()` returns the base
runtime, so every existing call site and test keeps working) and gains a `WorldRegistry`
with `define(name, from, Set<VariableRef>)`, `drop(name)`, `use(name)`, `active()`,
`list()`, and `snapshot(name)` (lazy compute as above). Names are unique; `base` cannot be
dropped or redefined; defining an existing name replaces it. `DefaultCausalGraphRuntime`
notifies the registry whenever it installs a new snapshot (the single `volatile` store
is the hook).

## Step 2: queries against a world

Every read operation of `CausalGraphRuntime` runs against a snapshot. Refactor so the
query methods take the snapshot to read (or a small `WorldView` wrapping it) instead of
always reading the base field; the base methods delegate with the base snapshot so the
interface and its callers are unchanged. No algorithm changes: `PathAnalyzer`,
`DSeparation`, `Adjustment`, the quantitative layer all already work on a
`CompiledCausalGraph`.

## Step 3: procedures

- `causal.intervene(name :: STRING, variables :: LIST<NODE>, from = '' :: STRING)`:
  defines the world (from = active world when empty), computes its snapshot eagerly,
  yields `name, base, intervened, removedEdges, coneSize, sharedRows, totalRows,
  millis`.
- `causal.use(name)`, `causal.models()` (yields `name, base, intervened, active,
  materialized, nodes, edges`), `causal.drop(name)`.
- Every read procedure (structural, `*Ids`, `dsep`, `adjustmentSets`,
  `adjustmentCandidate`, quantitative, `inspectSnapshot`) gains a last optional
  parameter `model = '' :: STRING`; empty means the active world. Unknown names fail with
  a clear message. Keep parameter order otherwise unchanged.
- Add the names to `ProcedureNames`/`ResultColumns` and to `ProcedureContractTest`.

## Step 4: DSL on the server-side worlds

In `Neo4jQueryExecutor` (Neo4j backend only):

- `INTERVENE ON x = v [FROM MODEL m] RETURN ...` calls `causal.intervene` with a generated
  or declared world name, then answers the RETURN part with procedures that pass that
  name; `USE MODEL` calls `causal.use`; `RESET` calls `causal.use('base')`; `SHOW MODELS`
  calls `causal.models()`.
- Structural statements on a derived world call the procedures with the active world
  name instead of `StructuralEvaluator`; `hydrateCurrentGraph()` is removed for
  structural statements.
- `IDENTIFY` calls `causal.adjustmentSets(t, y, 'all-minimal', limit)` and reports
  "identifiable by adjustment (generalized criterion)" or "not identifiable by
  adjustment", with the minimal sets; `adjustmentCandidate` is no longer used by the
  DSL.
- `ESTIMATE` and `COUNTERFACTUAL` keep their current DSL-side evaluation (out of scope).
- `ModelRegistry`, `StructuralEvaluator` stay for the in-memory backend only.

## Step 5: tests

1. `WorldRegistryTest` (unit): define/use/drop/list semantics, `base` protected,
   recursive definitions, cache dropped on base change and recomputed.
2. `DerivedWorldParityTest`: on every `Graphs` fixture, the seed DAG, the policy DAG
   (`dev/seed-policy-dag.cypher`) and `randomDag(1000, 0.004)`, for 200 random intervened
   sets of size 1 to 3: the derived snapshot equals `GraphBuilder.build` of the base edges
   minus the in-edges of the intervened set (closures, layers, adjacency, `obs`); every
   closure row of a variable outside the recomputed cones is the **same array** as the
   base row (`assertSame`); report median `sharedRows / totalRows`.
3. `WorldsCypherTest`: `causal.intervene` then `causal.ancestorIds(..., 'w')` differs
   from the base answer as expected; the base `causal.inspectSnapshot()` `compiledAt` is
   unchanged by `intervene`; a Cypher edit to the stored graph changes the next answer on
   the derived world without a new `intervene`; `causal.models()` lists both.
4. `DslPluginE2ETest`: `DEFINE MODEL`, `INTERVENE`, `USE MODEL`, structural queries on the
   derived world, `RESET`, `SHOW MODELS` end to end, with no client-side graph and an
   unchanged base `compiledAt`.

## Done when

All of the above green, plugin and DSL suites green, `-Dgroups=slow` green; the PR
description reports, per fixture, the median and maximum `sharedRows / totalRows` and
`intervene` time, and the number of derived worlds that hit the threshold fallback.

## Out of scope

Per-session active worlds; multi-database keying of the registry; `ESTIMATE` and
`COUNTERFACTUAL` over server-side worlds; persistence of world definitions across restarts.
