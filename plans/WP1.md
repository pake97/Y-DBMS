# WP1: the compiled index (dense layout + reachability closures)

Instructions for Claude Code. Run from the repository root on branch `feat/index`
(WP0 must be done: clean tree, CI green, `git tag v0-dfs-baseline` exists).
Read `CLAUDE.md` and `CODE-PLAN.md` first; this file is the executable version of
CODE-PLAN.md §WP1. Work in the `plugin/` module only. Open one PR named
`feat/index-wp1-closures` against `feat/index`.

## Goal

Replace the map-of-sets representation inside `CompiledCausalGraph` with a dense,
integer-indexed layout plus precomputed ancestor/descendant bitsets, and make
`ReachabilityIndex` read the bitsets instead of running a DFS per query. Nothing
observable changes: same public API, same DTOs, same procedure signatures, same
answers. The DFS stays in the code as the reference the parity test checks against.

## House rules (do not violate)

- Java 17 for `plugin/` (no `switch` pattern matching, no records with features
  beyond 17). Procedures stay thin; all algorithm work lives in `runtime/`.
- The snapshot is immutable: every new array is filled in the constructor and never
  written again. No lazy caches in this WP.
- Keep the package-private constructor
  `CompiledCausalGraph(Map<String,Variable>, Map<String,Set<String>>, Map<String,Set<String>>, List<String>, Map<String,Map<String,Double>>, Instant)`
  with the same signature: `GraphLoader` calls it and `PathAnalyzerTest` builds
  graphs through it by reflection. Add the index build *inside* it.
- Keep every existing public method of `CompiledCausalGraph` (`findVariable`,
  `parentsOf`, `childrenOf`, `topoOrder`, `topoIndex`, `weight`, `intercept`,
  `noiseVariance`, `isParameterized`, `logStructure`, and the `GraphSnapshot`
  methods) with the same return types. `parentsOf`/`childrenOf` may now return
  sets materialized from the CSR arrays; iteration order must remain the
  insertion order the loader produced (use `LinkedHashSet`).
- Do not touch `DefaultCausalGraphRuntime.isDSeparated`, `candidateAdjustmentSet`,
  `PathAnalyzer`, the listener, the DSL module, or the procedures beyond what
  §4 below says. Those are WP2/WP3/WP4.
- Run `mvn -pl plugin test` after every step; the embedded-Neo4j tests take ~2 min.
  Do not commit with a red build.

## Step 1: `BitSet64` helper

Create `plugin/src/main/java/com/example/causal/runtime/index/BitSet64.java`, a
final class with only static methods over `long[]` (no object per bitset):

```java
static int words(int n)                       // (n + 63) >>> 6
static long[] empty(int n)
static void set(long[] b, int i)
static boolean get(long[] b, int i)
static void or(long[] dst, long[] src)        // dst |= src, same length
static void and(long[] dst, long[] src)
static void andNot(long[] dst, long[] src)    // dst &= ~src
static boolean intersects(long[] a, long[] b)
static boolean isEmpty(long[] b)
static int cardinality(long[] b)
static long[] copy(long[] b)
static IntStream stream(long[] b)             // set bits ascending (Long.numberOfTrailingZeros loop)
static String toBinaryString(long[] b, int n) // for logStructure, MSB-left per word is fine, document it
```

Unit test `runtime/index/BitSet64Test.java`: set/get across word boundaries
(bits 0, 63, 64, 65, 127, 128), `or`/`and`/`andNot` on 3-word arrays, `stream`
returns ascending indices, `cardinality` matches `stream().count()`.

## Step 2: dense layout and closures in `CompiledCausalGraph`

Add these fields (all `private final`), filled in the constructor after the
existing map copies and before `computeParameterized()`:

```java
private final int n;
private final Map<String,Integer> nameToIndex;   // LinkedHashMap, insertion = loader order
private final Variable[] indexToVar;
private final int[] fwdOffset, fwdTarget;        // children of i: fwdTarget[fwdOffset[i] .. fwdOffset[i+1])
private final int[] revOffset, revTarget;        // parents of i
private final int[] topoOrderIdx;                // dense indices, sources first (same order as topoOrder list)
private final int[] topoPos;                     // topoPos[topoOrderIdx[k]] = k
private final int[] topoLayer;                   // 0 for sources; 1 + max(layer of parents) otherwise
private final int words;                         // BitSet64.words(n)
private final long[][] ancestorBits;             // ancestorBits[i][w]; bit j set iff j ∈ an(i); i itself never set
private final long[][] descendantBits;           // bit j set iff j ∈ de(i)
```

Build order inside the constructor:

1. `nameToIndex` from `variableById.keySet()` in iteration order; `indexToVar`.
2. CSR arrays from `forwardAdj`/`reverseAdj`. Children of each node are written
   in the order the set iterates; reverse likewise. Assert
   `fwdOffset[n] == revOffset[n] == number of edges`.
3. `topoOrderIdx` by mapping the `topoOrder` list through `nameToIndex`; `topoPos`
   as its inverse. Do not re-sort; `TopologicalSorter` already validated acyclicity.
4. `topoLayer`: one forward pass over `topoOrderIdx`; `layer[v] = 0` if no parents,
   else `1 + max(layer[p])`.
5. Ancestor closure, one forward-topological pass:
   ```
   for i in topoOrderIdx:
       anc = new long[words]
       for p in parents(i): or(anc, ancestorBits[p]); set(anc, p)
       ancestorBits[i] = anc
   ```
6. Descendant closure, one reverse-topological pass, symmetric with children.

Add public accessors (index level, used by WP2/WP3 and by `ReachabilityIndex`):

```java
public int size()                       // already exists; must equal n
public int indexOf(String id)           // -1 if unknown
public String idOf(int i)
public Variable variableAt(int i)
public int wordCount()
public long[] ancestorBits(int i)       // the internal array; callers must not mutate (document it)
public long[] descendantBits(int i)
public boolean reaches(int from, int to)  // BitSet64.get(descendantBits[from], to)
public int layer(int i)
public int topoPosition(int i)
public int[] childrenOf(int i)          // copy of the CSR slice (small; fine for now)
public int[] parentsOf(int i)
public Set<String> toIds(long[] bits)   // LinkedHashSet in ascending index order
```

Change `topoIndex(String id)` to `topoPos[indexOf(id)]` (O(1)), returning -1 for
unknown ids as before.

Extend `logStructure()` with three sections after the reverse adjacency: the
layer per node, and the two closure matrices (one row per node, using
`BitSet64.toBinaryString` when `n <= 64`, otherwise `cardinality` per row).

Memory note for the Javadoc: closures cost `2 * n * words` longs; state it.

## Step 3: `ReachabilityIndex` reads the closures, keeps the DFS

In `runtime/algorithms/ReachabilityIndex.java`:

- `ancestors(String id)` → `graph.toIds(graph.ancestorBits(graph.indexOf(id)))`;
  unknown id returns an empty set (current behavior).
- `descendants(String id)` → symmetric.
- `hasDirectedPath(from, to)` → `graph.reaches(...)`, false if either id is unknown.
- Rename the current DFS bodies to `ancestorsDfs(String id)` and
  `descendantsDfs(String id)`, keep them `public`, annotate their Javadoc
  "reference implementation; used by the parity test and by the DFS baseline of
  the evaluation (CODE-PLAN.md WP5). Do not delete."
- Add `public Set<String> ancestors(String id, boolean useIndex)` delegating to one
  or the other; the no-flag overload uses the index. This is the hook WP5's
  `-Dcausal.index=off` switch will use; do not implement the switch itself now.
- Rewrite the class Javadoc: remove the "TODO(student, tier 2)" plan, describe
  what it now is.

In `DefaultCausalGraphRuntime`: hold one `ReachabilityIndex` per snapshot. Add
`private volatile ReachabilityIndex reachability;` set in `compile()` right after
`snapshot = GraphLoader.load(options)` and nulled in `invalidate()`. Replace the
four `new ReachabilityIndex(g)` allocations in `ancestors`, `descendants`,
`hasDirectedPath`, `eligibleAdjustmentIds` with a `requireIndex()` accessor that
reads the volatile once. Do not change the algorithms in that class.

`PathAnalyzer` keeps constructing its own `ReachabilityIndex(graph)`; that is
now cheap (no precomputation in the constructor) and WP2 rewrites the class anyway.

## Step 4: `TopologicalSorter` stays; delete the stale comment

Remove the "should be updated to operate on int[] arrays" paragraph from its
Javadoc; the sorter runs once per compile on the maps and that is fine. Do not
rewrite it.

Also remove the tier-1/tier-2 "Student A implementation plan" Javadoc from
`CompiledCausalGraph` and `DefaultCausalGraphRuntime` and replace it with a short
description of the layout (fields above) and a pointer to `CODE-PLAN.md`.

## Step 5: tests

### 5a. Test-only graph builder

`PathAnalyzerTest.graphFromEdges` builds a `CompiledCausalGraph` by reflection.
Move that helper into a new test-support class
`plugin/src/test/java/com/example/causal/testsupport/Graphs.java` with:

```java
public static CompiledCausalGraph fromEdges(List<String> edges)          // "A->B" strings, as today
public static CompiledCausalGraph fromEdges(int n, int[][] edges)        // dense ids "v0".."v{n-1}"
public static CompiledCausalGraph randomDag(int n, double density, long seed)  // edges i->j only for i<j in a random permutation
public static CompiledCausalGraph fromSeedDag()                          // parse dev/seed-dag.cypher's UNWIND lists without Neo4j (regex on {from:'..', to:'..'} and {id:'..'})
```

Make `PathAnalyzerTest` use it; keep every existing assertion.

### 5b. `runtime/index/IndexParityTest.java`

For each fixture in { `fromSeedDag()`, `randomDag(n, d, seed)` for
`n ∈ {1, 2, 63, 64, 65, 130, 1000}`, `d ∈ {0.0, 0.05, 0.3}`, seeds 0..2, plus
the three synthetic configs loaded through `SyntheticDataSupport` in an embedded
DB tagged `synthetic` }, assert:

1. `ancestors(id)` equals `ancestorsDfs(id)` and `descendants(id)` equals
   `descendantsDfs(id)` for every node (set equality).
2. Duality: bit `j` in `ancestorBits(i)` iff bit `i` in `descendantBits(j)`, all pairs.
3. `reaches(u, v)` iff `descendantsDfs(idOf(u)).contains(idOf(v))`, all pairs
   for `n ≤ 130`, 10 000 random pairs otherwise.
4. Every edge `u→v`: `layer(v) > layer(u)` and `topoPosition(v) > topoPosition(u)`.
5. No node has its own bit set in either closure.
6. `topoIndex(id) == topoOrder().indexOf(id)` for every id.
7. CSR: `Arrays.equals(childrenOf(i), indices of childrenOf(idOf(i)))` in order.

Word-boundary cases (`n = 63, 64, 65`) are the ones that catch off-by-ones; do not
drop them to save time.

### 5c. Existing tests

`mvn -pl plugin test` must pass unchanged. In particular `CausalProceduresTest`,
`DsepAndAdjustmentE2ETest`, `CovidSeedDagE2ETest`, `AdjustmentCandidateTest`,
`DSeparationBayesBallTest` must produce identical answers; they are the behavior
contract. If any of them fails, the index is wrong, not the test.

### 5d. Sanity micro-benchmark (not a paper number)

Add `runtime/index/IndexSmokeTimingTest.java`, tagged `slow` and excluded from CI
(`-Dgroups='!synthetic,!slow'` in the workflow), that builds `randomDag(5000, 0.002, 0)`,
times 10 000 `ancestors()` calls through the index and 100 through the DFS after
warm-up, and asserts the index median is at least 10× lower. This is only a guard
against accidentally leaving a traversal in the indexed path.

## Step 6: finish

- `logStructure()` output for the seed DAG pasted into the PR description (first
  30 lines), so a reviewer can eyeball the closures.
- Update `README.md` §"Repository layout": `runtime/index/` line, and one sentence
  saying `causal.compile` now builds the closures.
- Commit messages in the imperative, one commit per step above, ending with the
  attribution lines required by the session.

## Done when

- `mvn -pl plugin test` green locally and in CI.
- `IndexParityTest` green on all fixtures including `n = 63/64/65`.
- `grep -rn "TODO(student" plugin/src/main` returns nothing.
- `ReachabilityIndex.ancestors` contains no loop over the graph; `ancestorsDfs` does.
- `DefaultCausalGraphRuntime` allocates no `ReachabilityIndex` outside `compile()`.

## Out of scope for this PR (WP2/WP3 will do them)

Bitset versions of `PathAnalyzer`, masked Bayes-Ball, the adjustment criterion
rewrite, `applyChange`, named snapshots, the `-Dcausal.index=off` switch, any
change to the DSL module or the procedures' signatures.
