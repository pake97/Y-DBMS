# Causal4j — What's Left to Build

Two things stand between the current code and the paper's core contribution: a real
index and real incremental maintenance. Both are described in the draft but not yet
implemented, so this is the substance of the work, not clean-up.

Do them **in this order** — the second depends on the first. The rule that keeps you
honest: don't start incremental maintenance until the index's parity check passes.
Building maintenance on an unverified index means patching something that's quietly
wrong.

This document gives you the task, why it matters, where the code is today, and what
"done" looks like. The *how* is yours to figure out — that's the point.

## House rules (from CLAUDE.md)

- Java 17. Procedures stay thin — algorithm work lives in `runtime/`, never in the
  procedure classes. The DSL layer only sees `com.example.causal.api.*`.
- `RuntimeRegistry` is the process-scoped singleton holding the live snapshot: reads
  lock-free, compile/invalidate synchronized. Keep it that way.
- In tests, exhaust a procedure result (`while (r.hasNext()) r.next();`) before commit
  — lazy streams skip execution otherwise.
- Module-scoped `dsl` tests need `-am`; a full `mvn test` from the root is always safe.

---

## 1. The compiled index

**The task.** Give the compiled graph a compact, index-based representation of
reachability, and make the ancestor/descendant/reachability queries read from it
instead of walking the graph.

**Why it matters.** This is the paper's central claim: structural queries drop from
`O(V+E)` per-call traversal to `O(V/64)` word operations. Right now that claim can't
even be measured, because the "index" and the "baseline" are the same DFS.

**Where the code is today.**
- `runtime/compile/CompiledCausalGraph.java` holds the graph as
  `Map<String,Set<String>>` adjacency + a `List<String>` topo order. The dense,
  integer-indexed layout with precomputed ancestor/descendant bitsets is described in
  the `TODO(tier 2)` Javadoc but not implemented.
- `runtime/algorithms/ReachabilityIndex.java` answers `ancestors`/`descendants` by
  iterative DFS — correct, but `O(V+E)` every call.

**Goal.** At compile time, precompute for every variable the full set of its ancestors
and descendants, stored compactly (bitsets over dense integer indices are the intended
design). Queries then become lookups/intersections rather than traversals.

**Desiderata.**
- The compiled snapshot stays **immutable** — build the index once, in construction.
- **No behaviour change:** the public API, DTOs, procedure signatures, and — crucially
  — the *answers* returned by `PathAnalyzer`, d-separation, and adjustment-set
  enumeration must be identical. You're changing how they compute, not what.
- **Keep the DFS implementation** available as a separate method — it's the reference
  the parity test checks the indexed answers against, so don't delete it.
- Extend `logStructure()` so the new structures print alongside the existing dump;
  that's your correctness eyeball.

**Done when.**
- A test shows the indexed `ancestors`/`descendants` equal the DFS answers exactly, on
  the COVID seed DAG (`dev/seed-dag.cypher`) and synthetic configs 1–3, including the
  ancestor/descendant duality.
- `mvn test -pl plugin` passes unchanged.

**Watch out for.** Word-count off-by-ones at n = 63/64/65; getting the topological
direction backwards when filling the two closures; forgetting a node's own bit when
propagating from a parent.

---

## 2. Incremental index maintenance

**Start only once stage 1's parity test is green.**

**The task.** When the graph is edited, update the compiled index in place — bounded to
the affected region — instead of throwing it away and recompiling.

**Why it matters.** A curated causal graph changes constantly. "Recompile on every
edit" is exactly the cost the paper claims to avoid, and RQ3 (incremental vs.
recompile) is meaningless until the two are actually different things.

**Where the code is today.**
- `lifecycle/CausalGraphChangeListener.java` already hooks Neo4j transactions and diffs
  each commit into a `ChangeBatch` (added/removed variables and `CAUSES` edges). But
  `afterCommit` just calls `invalidate()` — a full recompile next query. The
  property-change cases, a cycle validator, and the actual in-place patch are all TODO.

**Goal.** Turn a `ChangeBatch` into an in-place update of the index. Handle the edit
types the schema allows: added/removed edges and variables, the `active` flag flipping
(which adds/removes an edge), and the `latent` flag flipping (a property update).

**Desiderata.**
- **Correctness over cleverness.** Edge/variable *insertion* and `active` true-on admit
  a local patch. *Deletion* and reachability-shrinking changes are non-local and hard —
  falling back to recompile for those is the expected, defensible choice (the paper
  already frames deletion that way). A correct fallback beats a subtly wrong patch.
- **Reject illegal edits early.** An edge that would create a cycle, or that references
  unknown variables, should be refused before commit rather than corrupting the index.
- **Preserve snapshot isolation.** A concurrent reader must never see a half-updated
  index — favour building the new state and swapping it in atomically over mutating in
  place under readers.
- Keep the maintenance hook internal; don't widen the public `CausalGraphRuntime` API.

**Done when.**
- The key test: apply a sequence of edits (add edge; add variable + edge; `active` flip
  both ways; `latent` flip; remove edge) and assert the incrementally-maintained index
  is **structurally identical** to a fresh `causal.compile()` on the same final graph —
  same reachability sets, same topo semantics — on the COVID DAG and synthetic configs.
- An edge that would close a cycle is rejected and leaves the index untouched.
- `mvn test -pl plugin` still green.

**Watch out for.** You can't read a deleted node/edge's properties in `afterCommit` —
capture what you need earlier. And an insertion's effect cascades: everything reachable
from the changed node inherits the change, so a patch that stops at the immediate
neighbour will fail on any graph deeper than two layers.

---

## Order

1. Index — the keystone; incremental maintenance has nothing to maintain without it.
2. Incremental maintenance — makes the update story a real contribution instead of a
   recompile.

Small commits, one branch per stage, and let the parity/correctness tests — not
intuition — decide when a stage is done. When both land, tell Amedeo: that unblocks
the measurement pass the paper is ultimately waiting on.
