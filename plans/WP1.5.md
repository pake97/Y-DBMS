# WP1.5: bring `feat/index` up to date with `origin/master` and reconcile the two indexes

Instructions for Claude Code. Read `CLAUDE.md`, `CODE-PLAN.md` and `plans/WP1.md`
first. Work in `plugin/` only. Open one PR named `feat/index-wp15-reconcile` against
`feat/index`.

## Why this exists

`feat/index` (WP0 + WP1) was branched from a local `master` at `ec47df7` (July 21).
`origin/master` is 45 commits ahead at `d879117` (PR #29, August 14), which merged the
`incremental-index-maintenance` and `fix/listener-delete-guard` branches. Those commits
contain a second bitset index inside `CompiledCausalGraph` (different layout and
accessor names), an insertion-only copy-on-write patch, a transaction listener that
handles property flips and rejects cycles and parallel edges, `applyChange` on the
runtime, and about a dozen test classes. WP1 reimplemented the index with a CSR layout
and a richer accessor surface. This WP merges `origin/master` into `feat/index`, keeps
WP1's index, keeps August's maintenance code, and moves August's patch logic into a
`SnapshotPatcher` that works on WP1's arrays.

## Step 0: preconditions (human, on the Mac, before starting Claude Code)

```bash
git fetch origin
git log --oneline -1 origin/master        # must be d879117 "Merge pull request #29 ..."
git checkout master && git merge --ff-only origin/master
git checkout feat/index && git log --oneline -1   # must contain the WP1 merge
git checkout -b feat/index-wp15-reconcile
```

(The sandbox shell used to write this brief cannot authenticate to GitHub; the fetch has
to happen on the Mac.)

## Step 1: merge and resolve

```bash
git merge origin/master
```

Expected conflicts and the rule for each:

| File | Rule |
|---|---|
| `plugin/.../runtime/compile/CompiledCausalGraph.java` | **ours** (WP1) wholesale. Do not keep August's `nameToIndex`/`denseForwardAdj`/`getAncestorBits`/`idForIndex`/`patch(...)`/private patch constructor. Their logic moves to `SnapshotPatcher` (Step 2). |
| `plugin/.../runtime/algorithms/ReachabilityIndex.java` | **ours** wholesale. |
| `plugin/.../runtime/DefaultCausalGraphRuntime.java` | **ours**, then re-add August's `public synchronized boolean applyChange(ChangeBatch batch)` with the body changed to call `SnapshotPatcher.apply(snapshot, batch)`; on a non-null result set `snapshot = next; reachability = new ReachabilityIndex(next);` and return true. |
| `plugin/.../runtime/ChangeBatch.java` (theirs, new) and `plugin/.../lifecycle/ChangeBatch.java` (ours, deleted by theirs) | accept the move: keep `runtime/ChangeBatch.java` with `Edge(from, to, weight)`, `updatedVariables`, `updatedEdges`. Fix imports in `lifecycle/`. |
| `plugin/.../lifecycle/CausalGraphChangeListener.java`, `CausalGraphExtensionFactory.java`, `runtime/RuntimeRegistry.java`, `runtime/model/Variable.java` | **theirs** (August). Then rename call sites per Step 3. |
| `plugin/src/test/.../AdjustmentCandidateTest.java`, `DSeparationBayesBallTest.java`, `PathAnalyzerTest.java` | **ours** (they use `Graphs`). If theirs added test methods that ours lacks, port those methods onto `Graphs.fromEdges`. Diff the method lists before resolving. |
| `plugin/src/test/.../testsupport/GraphTestSupport.java` (theirs, new) | delete after porting its callers to `Graphs` (`graphFromEdges(List<String>)` → `Graphs.fromEdges`; `parseEdges`/`buildFromInput` callers → `Graphs.fromEdges`). |
| `plugin/src/test/.../algorithms/ReachabilityIndexParityTest.java` (theirs, new) | read it, confirm each assertion has an equivalent in `runtime/index/IndexParityTest`, add any that is missing there, then delete the file. |
| `plugin/src/test/.../compile/IncrementalPatchTest.java`, `lifecycle/IncrementalListenerCypherTest.java`, `lifecycle/ListenerPerformanceIT.java`, `runtime/algorithms/ReachabilityPerformanceIT.java`, `runtime/compile/IncrementalMaintenancePerformanceIT.java` (theirs, new) | keep; rename call sites per Step 3; tag the three `*IT` classes `@Tag("slow")`. |
| `CLAUDE.md` | theirs' "Constraints" paragraph and `{active, weight}` schema line **plus** ours' `78 edges` (theirs says 76, which is wrong). Add one line pointing to `CODE-PLAN.md`. |
| `dev/seed-dag.cypher` | theirs (adds the uniqueness constraint). Confirm 25 ids / 78 edges afterwards. |
| `pipeline/**` | identical on both sides; if git still flags them, take either. |
| `README.md` | ours; append nothing. |

Resolve, `git add`, commit the merge. Do not run tests yet; Steps 2–3 make it compile.

## Step 2: `SnapshotPatcher`

Create `plugin/src/main/java/com/example/causal/runtime/compile/SnapshotPatcher.java`:

```java
public final class SnapshotPatcher {
    /** Returns the patched snapshot, or null when the batch must be applied by a full recompile. */
    public static CompiledCausalGraph apply(CompiledCausalGraph old, ChangeBatch batch)
}
```

Port the August logic (the former `patch(ChangeBatch)` method and the private
`CompiledCausalGraph(CompiledCausalGraph old, ChangeBatch batch)` constructor; read them
from `git show origin/master:plugin/src/main/java/com/example/causal/runtime/compile/CompiledCausalGraph.java`,
lines ~213–455) with these changes and no others:

1. **Same contract.** Return `null` when: the batch has `removedVariables` or
   `removedEdges`; an added edge has an unknown endpoint; an added edge `u→v` has
   `old.topoPosition(indexOf(u)) >= old.topoPosition(indexOf(v))` while both are old
   nodes; `PatchNotApplicable` is thrown. (WP3 removes these fallbacks; this WP only
   relocates the code.) Keep `PatchNotApplicable` as a private static exception in
   the patcher.
2. **Variables.** Added variables get dense indices `oldN..newN-1` in batch order;
   `updatedVariables` replace entries in a copied `indexToVar` and `variableById`.
   Weights and intercept/noise handling exactly as August.
3. **Adjacency.** WP1 stores CSR (`fwdOffset/fwdTarget`, `revOffset/revTarget`), not
   `int[][]`. Rebuild both CSR pairs in full from `old` plus the added edges
   ($O(|V|+|E|)$, far below the closure cost; WP3 may optimize). Children/parents of
   existing nodes keep their order; new edges are appended.
4. **Closures.** Keep August's flagged-row recompute: seed the ancestor frontier with
   `old.descendantBits(v) ∪ {v}` for every added edge head `v` and the descendant
   frontier with `old.ancestorBits(u) ∪ {u}` for every tail `u`; recompute only
   flagged rows in topological order from parents (ancestors) and in reverse order from
   children (descendants); share every unflagged row by reference; allocate fresh rows
   for flagged and new nodes; widen to `BitSet64.words(newN)` when the word count
   grows, copying old rows on widening. The comment block headed "ALIASING INVARIANT"
   must be carried over verbatim.
5. **Order arrays.** `topoOrderIdx`: old order followed by new nodes in batch order
   (valid because of the fallback rule in 1); `topoPos` as its inverse; `topoLayer`
   recomputed for flagged and new rows only.
6. **Assembly.** Add to `CompiledCausalGraph` one package-private constructor that
   takes every final field already built (`variableById`, `edgeWeights`, `n`,
   `nameToIndex`, `indexToVar`, the four CSR arrays, `topoOrderIdx`, `topoPos`,
   `topoLayer`, `words`, `ancestorBits`, `descendantBits`, `compiledAt`) and stores
   them without copying or recomputing; it must assert the array lengths. The public
   build path (maps → constructor → closures) is untouched.

Add `SnapshotPatcher` to the `logStructure()` section header so a patched snapshot
prints "patched from <compiledAt of old>".

## Step 3: rename August call sites to WP1's accessors

Grep the whole module and replace:

| August (origin/master) | WP1 |
|---|---|
| `getAncestorBits(i)` | `ancestorBits(i)` |
| `getDescendantBits(i)` | `descendantBits(i)` |
| `idForIndex(i)` | `idOf(i)` |
| `topoIndex(id)` used as a **dense index** (August conflated the two) | `indexOf(id)` |
| `topoIndex(id)` used as a **topological position** | `topoIndex(id)` (unchanged) |
| `graph.patch(batch)` | `SnapshotPatcher.apply(graph, batch)` |
| `GraphTestSupport.graphFromEdges(...)` | `Graphs.fromEdges(...)` |

For each `topoIndex` occurrence in the listener and the ported tests, decide which of the
two meanings the code needs by reading the surrounding lines; the cycle check in
`beforeCommit` needs dense indices (`reaches(indexOf(to), indexOf(from))`).

`touchesCausalSchema` in the August listener returns true for any created or deleted
node. Narrow it: created/deleted nodes count only if they carry the `Variable` label
(for deleted nodes use `removedLabels` and `removedNodeProperties`), so a transaction on
unrelated nodes costs nothing, as the paper states.

## Step 4: tests

- `mvn -pl plugin test` green (unit + embedded Neo4j).
- `mvn -pl plugin test -Dgroups=slow` green (the three ITs).
- `IndexParityTest` unchanged and green.
- `IncrementalPatchTest`: every case passes with the same expected outcome as on
  `origin/master` (including the two fallback cases, which still fall back).
- `IncrementalListenerCypherTest`: all 13 cases pass; the cycle-rejection and
  delete-all cases are the ones most likely to break on the accessor rename.
- Add one test to `IncrementalPatchTest`: after a successful patch, every unflagged row
  of `ancestorBits`/`descendantBits` is the **same array instance** as in the old
  snapshot (`assertSame`), and every flagged row is a different instance. This pins the
  copy-on-write property the paper claims.
- Add one test to `IndexParityTest`: a snapshot produced by `SnapshotPatcher.apply`
  equals a snapshot built from scratch from the same edge list (closures, layers,
  `topoPos` monotone along edges, CSR contents).

## Step 5: finish

- Update the `.github/workflows/test.yml` groups to `'!synthetic,!slow'` if the merge
  reverted it.
- `CODE-PLAN.md`: mark WP1.5 done with the merge commit hash; nothing else.
- PR description: the conflict table above with what was taken per file, and the
  output of `git diff --stat feat/index`.

## Done when

- `git log feat/index-wp15-reconcile` contains `d879117` as an ancestor.
- `grep -rn "getAncestorBits\|idForIndex\|denseForwardAdj\|GraphTestSupport" plugin/src` returns nothing.
- `CompiledCausalGraph` has no `patch` method and no batch constructor; `SnapshotPatcher` has `apply`.
- All test goals of Step 4 hold.
- `git diff feat/index -- plugin/src/main/java/com/example/causal/runtime/algorithms/ReachabilityIndex.java plugin/src/main/java/com/example/causal/runtime/index/` is empty.

## Out of scope (WP3)

Removing the fallbacks (deletion cone recompute, topological relabel on backward edges,
variable removal), the fallback counter, `applyChange` batching rules, any change to
`PathAnalyzer`, d-separation, or adjustment (WP2).
