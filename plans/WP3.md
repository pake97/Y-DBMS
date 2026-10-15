# WP3: incremental maintenance, complete the patch

Instructions for Claude Code (owner: A, then K). Read `CLAUDE.md`, `CODE-PLAN.md` (WP3
section), `plans/WP1.md`, `plans/WP1.5.md` and `plans/WP2.md` first. Branch
`feat/index-wp3-maintenance` from `feat/index` after the WP2 PR is merged
(`git merge-base --is-ancestor <WP2 merge commit> HEAD` exits 0). Work in `plugin/` only.
One PR against `feat/index`, one commit per step.

## Goal

`SnapshotPatcher.apply` returns `null` today for every batch with a removed edge or
variable, for an inserted edge `u -> v` with `topoPos[u] >= topoPos[v]`, for an edge out
of a new variable, and for two new variables connected by a batch edge; the listener then
calls `invalidate()` and the next query recompiles. The paper (full version Section VI,
landscape version Section IV-C) claims that insertion is a cone-bounded patch, that
deletion is a cone recompute with a threshold fallback, that variable removal is handled,
that `active` flips are insertions or deletions and `latent` flips are property-only, and
that readers are never blocked. WP3 makes every one of those sentences true and exposes a
fallback counter so that WP5b-2 can measure how often the patch declines.

## House rules

As in WP1 and WP2: copy-on-write, every untouched closure row shared by reference with
the old snapshot; no allocation proportional to `|V|` for a patch whose cones are small,
except the row-pointer arrays themselves; `BitSet64` for every set; parity against a full
`GraphBuilder.build` of the updated edge list after every edit in tests. Never change the
layout of `CompiledCausalGraph` (WP1); add accessors if needed. Read `SnapshotPatcher`
before editing it: the insertion propagation (flagged rows, forward sweep for `anc` and
layers, reverse sweep for `des`) is kept and extended, not rewritten.

## Definitions

For an edge `u -> v`:

```
coneDown(v) = des[v] | {v}          // rows whose anc can change
coneUp(u)   = anc[u] | {u}          // rows whose des can change
```

Closure recurrences (WP1): `anc[i] = ∪_{p ∈ pa(i)} (anc[p] | {p})` in topological order,
`des[i] = ∪_{c ∈ ch(i)} (des[c] | {c})` in reverse topological order.

## Step 1: topological relabel on insertion

Replace the `backward edge` fallback. For an inserted `u -> v` with both endpoints old and
`topoPos[u] > topoPos[v]`:

1. if `old.reaches(v, u)` the edge closes a cycle: this cannot happen after `beforeCommit`
   (it already rejects cycles), so throw `PatchNotApplicable` with a message that names the
   invariant; do not silently recompile.
2. otherwise move `coneDown(v)` after `u`: take the nodes of `coneDown(v)` in their current
   relative order, remove them from `topoOrderIdx`, and reinsert them immediately after `u`
   (after the last node of the cone that precedes `u`, if `u` itself is in no cone). Rebuild
   `topoPos` for the moved range only, then recompute `layer` over `coneDown(v)` in the new
   order. `topoOrder` (the id list) follows `topoOrderIdx`.

Edges out of a new variable and edges between two new variables: topologically sort the
batch's new variables against the batch's own edges (Kahn on the sub-batch), assign dense
indices `oldN..newN-1` in that order, then treat each batch edge as above. Keep a counter
of how many insertions needed a relabel and how many nodes were moved; WP5b-2 reports it.

## Step 2: deletion by cone recompute

For each removed edge `u -> v` (and each `active` flip to false, which the listener already
turns into a removed edge):

1. remove it from the CSR adjacency (rebuild the forward row of `u` and the reverse row of
   `v`; the offset arrays shift, so copy them once per batch, not per edge);
2. recompute `anc` over `coneDown(v)` in topological order from the updated parents, and
   `des` over `coneUp(u)` in reverse topological order from the updated children, using the
   recurrences above restricted to those rows; rows outside `coneDown(v) ∪ coneUp(u)` keep
   their reference;
3. recompute `layer` over `coneDown(v)` (a deletion can only lower layers);
4. the topological order is unchanged by a deletion.

Batches: take the union of all deletion cones first and recompute once, then apply the
insertion patch of Step 1 and the existing propagation on top. A batch that both removes
and inserts the same edge is a no-op for that edge.

Threshold: a system property `causal.patch.threshold`, a fraction of `|V|`, default `0.5`.
When `|coneDown(v) ∪ coneUp(u)|` summed over the batch's deletions exceeds
`threshold * |V|`, return `null` (recompile) and increment the fallback counter. This is
the only remaining legitimate fallback; every other `null` return is a bug.

## Step 3: variable removal and property flips

- Removed variable `x`: delete its incident edges through Step 2, then mark the dense index
  free: `indexToVar[x] = null`, remove it from `nameToIndex`, clear its closure rows, and
  skip it in `topoOrderIdx`. `size()` keeps counting the array length; add
  `liveCount()` and make `variables()`, `ids()` and the sweeps skip freed indices. No
  compaction until the next full compile; document this on the class.
- `latent` flip: the listener must emit it as an updated variable (it already builds
  `updatedVariables` for property changes; verify that `latent` and `observed` are
  included, add them if not). The patch replaces `indexToVar[i]` and recomputes the
  `obs` bitset that WP2's `PathAnalyzer` caches per snapshot; closures untouched.
- `active` flip to true: already an added edge; goes through Step 1.

## Step 4: observability

- `SnapshotPatcher` keeps per-snapshot counters: patches applied, insertions relabelled,
  nodes moved, deletions recomputed, cone size of the last patch, fallbacks (with reason:
  threshold, invariant). Expose them on `CompiledCausalGraph` as a small record and through
  `causal.inspectSnapshot` as extra columns; keep the existing columns.
- `causal.patch.enabled` (default true): when false, `applyChange` returns false and the
  listener invalidates, which is policy (ii) of WP5b-2's mixed workload.

## Step 5: tests

1. `IncrementalPatchTest`: `testTopologicalViolationFallback`,
   `testMultipleNewNodesFallback` and the removal test become positive tests (the patch
   applies and equals the rebuild).
2. `IncrementalParityTest` (new): for every `Graphs` fixture, the seed DAG and
   `randomDag(1000, 0.004)`: 1,000 random edits from a fixed seed, types {insert acyclic
   edge, delete edge, active flip, latent flip, add variable with one edge, remove variable},
   applied one at a time and in batches of 10; after each application the patched snapshot
   equals `GraphBuilder.build` of the maintained edge list on closures, layers, adjacency,
   `obs`, and `topoPos` monotone along every edge. Assert the fallback counter is zero
   except for deletions above the threshold, and report how many insertions relabelled.
3. `IncrementalListenerCypherTest` gains: an `active` flip changes the next
   `causal.ancestors` answer with no `causal.compile`; a deletion with a cone above the
   threshold triggers the fallback and the answer is still right; `causal.inspectSnapshot`
   shows the counters; `causal.patch.enabled=false` makes every edit a recompile.
4. The three August ITs (`IncrementalMaintenancePerformanceIT`, `ListenerPerformanceIT`,
   and the lifecycle IT) run green under `-Dgroups=slow`.

## Done when

- `SnapshotPatcher.apply` returns `null` only for the threshold case, and the invariant
  case throws.
- `IncrementalParityTest` passes on every fixture with zero fallbacks below the threshold.
- The PR description reports, per fixture: edits applied, insertions relabelled, nodes
  moved in total, deletions recomputed, median and maximum cone size, fallbacks.
- `plans/WP5b-2.md` Step 3 can run unchanged against the branch.

## Out of scope

Compaction of freed indices; concurrency beyond the single-writer `synchronized`
`applyChange`; derived worlds (WP4, which calls Step 2 on a copy of the base).
