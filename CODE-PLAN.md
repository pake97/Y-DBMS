# Causal4j: code plan for the ICDE 2027 submission

Target: ICDE 2027 Round 2, **November 11, 2026, 17:00 PT**. Today is September 26, 2026, which leaves six and a half weeks. This plan replaces `IMPLEMENTATION-GUIDE.md` and Section 13 of `paper/ICDE2027-outline.md`; it is derived from the review in `paper/REVIEW-icde2027.md` and from the rewritten paper (`Downloads/Causal4j`), so every work package below corresponds to a claim the new draft makes. The rule from the implementation guide still holds: **no work package starts before the parity test of the previous one is green**, because each one builds on the arrays the previous one produces.

Two people: A = Amedeo, K = Kirollos. Effort is in working days.

| WP | What | Owner | Days | Blocks |
|---|---|---|---|---|
| 0 | Repository hygiene, branch, CI | A | 1 | everything |
| 1 | Dense layout + reachability closures (the index) | K | 6 | 2, 3, 4, 5 |
| 2 | Query processing over the index (path roles, masked Bayes-Ball, generalized adjustment, polynomial-delay enumeration) | K + A | 8 | 5 |
| 3 | Incremental maintenance (insert patch, cone recompute, flips, pre-commit acyclicity) | K | 6 | 5 |
| 4 | Server-side model registry and derived worlds | A | 4 | 5 |
| 5 | Benchmark harness, datasets, baselines, correctness validation | A | 8 | paper §8 |
| 6 | Artifact packaging, reproducibility scripts | A | 2 | submission |

Critical path: WP1 → WP2 → WP5. WP3 and WP4 run in parallel with WP2 once WP1 is green. If WP3 slips past October 30, the paper keeps insertion and property flips as measured and reports deletion as cone recompute only; if WP4 slips, §3.3 of the paper is cut to the client-side registry paragraph (already written as the fallback).

---

## WP0. Repository hygiene (Sep 29)

1. Commit the two dirty files (`CausalGraphChangeListener.java`, `CounterfactualInference.java`) after reading the diff; commit `paper/`, `pipeline/`, `PIPELINE-DESIGN.md`; delete `IMPLEMENTATION-GUIDE.md` in favor of this file.
2. Branch `feat/index` from `main`; every WP is a PR against it; `main` receives the merge on November 1 at the latest.
3. Add a GitHub Actions workflow running `mvn -B test -Dgroups="!synthetic"` on Java 17/21 so parity tests run on every PR.
4. Fix the four inconsistent counts of the running example now: `dev/seed-dag.cypher` seeds **25 variables and 78 active edges** (one `id` appears twice in the file; `grep -o "id: *'[a-z_0-9]*'" | sort -u` gives 25). Update `README.md`, `CLAUDE.md`, and the paper to 25/78 and verify the edge count against Piccininni et al. (2020), Fig. 3.
5. Make `hydrateCurrentGraph()` and `showDag()` in `Neo4jQueryExecutor` filter with `coalesce(r.active, true)` like `GraphLoader`, so client and server see the same edge set.

---

## WP1. The index: dense layout and reachability closures (Sep 30 – Oct 7)

**Files.** `runtime/compile/CompiledCausalGraph.java` (rewrite), `runtime/compile/GraphLoader.java` (build call), `runtime/algorithms/ReachabilityIndex.java` (read the closures; keep DFS as oracle), new `runtime/index/BitSet64.java` (static helpers over `long[]`), new test `runtime/index/IndexParityTest.java`.

**Layout to add to `CompiledCausalGraph`** (all `final`, filled in the constructor, never mutated; the incremental path in WP3 produces a *new* instance by copy-on-write of the touched rows):

```java
final int n;                          // number of variables
final Map<String,Integer> nameToIndex;// id -> dense index
final Variable[] indexToVar;
final int[] fwdOffset, fwdTarget;     // CSR: children of i are fwdTarget[fwdOffset[i] .. fwdOffset[i+1])
final int[] revOffset, revTarget;     // CSR: parents of i
final int[] topoOrder;                // sources first
final int[] topoPos;                  // inverse of topoOrder, O(1) topoIndex(id)
final int[] topoLayer;                // 0 for sources; layer[v] = 1 + max layer of parents
final int words;                      // ceil(n / 64)
final long[][] ancestorBits;          // ancestorBits[i][w]; bit j set iff j in an(i)
final long[][] descendantBits;        // bit j set iff j in de(i)
```

**Build algorithm** (in the constructor, after `TopologicalSorter` has produced `topoOrder`):

```
for i in topoOrder:                       // forward order: parents are done before i
    layer[i] = 0; anc = new long[words]
    for p in parents(i):
        layer[i] = max(layer[i], layer[p] + 1)
        or(anc, ancestorBits[p]); set(anc, p)
    ancestorBits[i] = anc
for i in reverse(topoOrder):              // children are done before i
    des = new long[words]
    for c in children(i): or(des, descendantBits[c]); set(des, c)
    descendantBits[i] = des
```

Cost is $O(|V|\cdot|V|/64 + |E|\cdot|V|/64)$ time and $2\,|V|\lceil|V|/64\rceil$ words of space. State both in the paper (they are).

**Accessor surface.** Keep every existing public method of `CompiledCausalGraph` (`parentsOf`, `childrenOf`, `topoOrder`, `findVariable`, weights) so nothing else breaks, and add index-level accessors used by WP2/WP3: `int indexOf(String id)`, `String idOf(int i)`, `long[] ancestorBits(int i)`, `long[] descendantBits(int i)`, `boolean reaches(int from, int to)` (one bit test), `IntStream parents(int i)`, `IntStream children(int i)`, `int layer(int i)`. `Set<String>`-returning helpers materialize from a bitset with `BitSet64.toIds`.

**`ReachabilityIndex`.** `ancestors(id)` / `descendants(id)` read the closures; rename the current bodies to `ancestorsDfs` / `descendantsDfs` and keep them package-visible for the parity test and for the DFS baseline in WP5. `hasDirectedPath` becomes `graph.reaches`. `DefaultCausalGraphRuntime` stops allocating a `ReachabilityIndex` per call: hold one per snapshot, built in `compile`.

**`logStructure()`.** Add the layer array and the two closure matrices (as bit strings for $n \le 64$, as popcounts otherwise).

**Tests / done when.**
- `IndexParityTest`: for the COVID seed DAG, synthetic configs 1–3, and 50 random DAGs with $n \in \{1, 63, 64, 65, 130, 1000\}$: indexed ancestors == DFS ancestors for every node; `ancestorBits[i]` has bit `j` iff `descendantBits[j]` has bit `i` (duality); `reaches(u,v)` == `descendantsDfs(u).contains(v)`; `layer[v] > layer[u]` for every edge.
- `mvn test -pl plugin` unchanged and green.
- Micro-check: `causal.ancestors` on `munin`-sized random DAG returns in under 50 µs after warmup (sanity, not the paper number).

---

## WP2. Query processing over the index (Oct 6 – Oct 17)

This is where the paper's algorithmic claims live, so the paper and the code must say the same thing. The three cost tiers the new draft states are: (a) bitset operators, $O(|V|/64)$; (b) Bayes-Ball, $O(|V|+|E|)$ with a constant-factor gain and ancestral masking; (c) identification, one canonical-set construction plus one d-separation test, and polynomial-delay enumeration of minimal sets.

**Files.** `runtime/algorithms/PathAnalyzer.java` (rewrite over bitsets), `runtime/algorithms/DSeparation.java` (new; move Bayes-Ball out of the runtime class), `runtime/algorithms/Adjustment.java` (new), `runtime/DefaultCausalGraphRuntime.java` (delegation only), `api/AdjustmentCandidateResult.java` (extend), `procedures/CausalProcedures.java` (new optional arguments), tests `DSeparationBayesBallTest`, `AdjustmentCandidateTest` (extend), new `AdjustmentCriterionTest`, `MinimalSeparatorTest`.

### 2a. Path roles as bitset algebra (`PathAnalyzer`)

```
confounders(t,y)  = anc[t] & anc[y]                       // minus t, y
mediators(t,y)    = des[t] & anc[y]
causalNodes(t,y)  = mediators(t,y) | {y if reaches(t,y)}   // cn(t,y), Perković et al.
forbidden(t,y)    = {t} | OR over c in cn(t,y) of des[c] | cn(t,y)   // de(cn) ∪ {t}
colliders(x,y)    : for each z not in anc[x]|anc[y]|{x,y}: exists parent in des[x]|{x} and parent in des[y]|{y}
backdoorVariables : unchanged BFS on the skeleton, but `forbidden` from bitsets and the visited set as long[]
```

`forbidden` needs one OR per causal node; that is $O(|cn|\cdot|V|/64)$ and is the only place where a loop over a set of nodes remains. Define collider formally in the paper as this set.

### 2b. Masked Bayes-Ball (`DSeparation`)

Signature: `static boolean separated(CompiledCausalGraph g, long[] x, long[] y, long[] z, long[] cutOut)` where `cutOut` marks nodes whose outgoing edges are ignored, and `cutOutTo` (optional, `long[][]` or a predicate) restricts the cut to specific children for the proper backdoor graph (2c).

Two uses of the index: (i) all sets (`x`, `y`, `z`, visited-from-parent, visited-from-child, cutOut) are `long[]`, so membership is one word test and no `State` records are allocated; (ii) **ancestral masking**: d-separation of $X$ and $Y$ given $Z$ depends only on the subgraph induced by $\mathrm{An}(X \cup Y \cup Z)$, so compute `mask = x | y | z | OR anc[v] for v in x|y|z` (one OR per endpoint, $O((|X|+|Y|+|Z|)\,|V|/64)$) and never enqueue a node outside `mask`. On sparse DAGs with small $Z$ this prunes most of the graph; this is the measured constant-factor claim in RQ1, not an asymptotic one. Work queue is an `int[]` ring buffer of size $2n$ (node, direction).

Keep the existing `Set<String>`-based routine as `bayesBallReference` for the parity test.

### 2c. Generalized adjustment criterion and the canonical set (`Adjustment`)

Replace Pearl's backdoor criterion (all out-edges of $T$ cut, all descendants of $T$ excluded) with the generalized adjustment criterion of Shpitser et al. (2010) / Perković et al. (2018), which dagitty implements, so RQ5 can compare exactly:

```
isValidAdjustment(t, y, z):
    if (z & forbidden(t,y)) != 0: return false
    return separated(g, {t}, {y}, z, cutOut = {t}, cutOutOnlyTo = cn(t,y))   // proper backdoor graph
identifiableByAdjustment(t, y):                                                // van der Zander et al. 2019, constructive criterion
    adj = (anc[t] | anc[y]) & ~forbidden(t,y) & ~{t,y}
    return isValidAdjustment(t, y, adj) ? Optional.of(adj) : Optional.empty()
```

`identifiableByAdjustment` is the new default of `causal.adjustmentCandidate`: one canonical-set construction (bitset ops) plus one masked Bayes-Ball run, replacing the level-by-level enumeration of `combinationsOfSize`, which is exponential and must be deleted.

### 2d. Minimal adjustment sets with polynomial delay

Implement the separator framework of van der Zander, Liśkiewicz, Textor (AIJ 2019) on the proper backdoor graph with $I = \emptyset$ and $R = V \setminus (\mathrm{Forb} \cup \{t,y\})$:

```
findSep(x, y, I, R):        z = (anc[x]|anc[y]|OR anc[I]) & R & ~{x,y};  return separated(x,y,z) ? z : none
findMinSep(x, y, I, R):     z' = findSep(...); if none return none
                            build moral ancestral graph on mask = An(x ∪ y ∪ z') (from bitsets)
                            z1 = I | (z' & reach(x, moral graph minus (z' \ I)))
                            z  = I | (z1 & reach(y, moral graph minus (z1 \ I)))
                            return z
listMinSep(x, y, I, R, emit):                                          // polynomial delay
    if findMinSep(x,y,I,R) is none: return
    pick v in R \ I with v not forced; if none: emit(findMinSep(x,y,I,R)); return
    listMinSep(x, y, I ∪ {v}, R, emit)
    listMinSep(x, y, I, R \ {v}, emit)
```

The moralization step is a traversal over the masked ancestral subgraph; `reach` there is BFS with `long[]` visited. Expose as `causal.adjustmentSets(t, y, {mode: 'canonical' | 'minimal' | 'all-minimal', limit: k})`; the DSL's `RETURN ADJUSTMENT SETS` maps to `all-minimal` with a default limit, `EXPLAIN` returns the canonical set and the forbidden set as the witness. `AdjustmentCandidateResult` gains `canonical`, `forbidden`, `mode`.

Also expose `causal.dsep(x, y, z)` unchanged and add `causal.minimalSeparator(x, y, I, R)` since it falls out of `findMinSep`.

**Tests / done when.**
- Parity: bitset `PathAnalyzer` == old `PathAnalyzer` on all fixtures; masked Bayes-Ball == `bayesBallReference` on 2,000 random $(X,Y,Z)$ triples per fixture, including $Z$ containing descendants of colliders.
- `AdjustmentCriterionTest`: hand-built DAGs from Shpitser et al. 2010 and Perković et al. 2018 where the backdoor and generalized criteria differ (a descendant of $T$ off the causal path is a valid adjustment variable); `identifiableByAdjustment` agrees with dagitty's `adjustmentSets(type="canonical")` on every bnlearn network for 200 random $(t,y)$ pairs (the Python round-trip harness of WP5 produces the reference file `test/resources/dagitty-adjustment.json`; the Java test only reads it).
- `MinimalSeparatorTest`: every emitted set is valid, inclusion-minimal (removing any element breaks validity), and the emitted family equals dagitty's `adjustmentSets(type="minimal")` on the fixtures.
- No `combinationsOfSize` left in the code base.

---

## WP3. Incremental maintenance (Oct 13 – Oct 21)

**Files.** `lifecycle/CausalGraphChangeListener.java`, `lifecycle/ChangeBatch.java` (add `activatedEdges`, `deactivatedEdges`, `latentFlips`), `runtime/DefaultCausalGraphRuntime.java` (`applyChange`), new `runtime/compile/SnapshotPatcher.java`, tests `IncrementalParityTest`, `ChangeListenerE2ETest` (embedded Neo4j).

**Change detection (`beforeCommit`).** Add the two property paths that the paper promises and that are `TODO` today: `assignedRelationshipProperties()` with key `active` (false→true = added edge, true→false = removed edge), `assignedNodeProperties()` with key `latent`. Extend `touchesCausalSchema` so a transaction touching only non-`Variable` nodes returns false (today any created node triggers the full scan); that makes the "cheap guard" sentence in §6 true. Pre-commit validation: for every added edge $u \to v$ in the batch, reject the transaction if `reaches(v, u)` in the current snapshot **or** in the batch's own added edges (a batch may create a cycle through two new edges; check with a small DFS over the batch plus closures). Reject edges whose endpoints are not `Variable` nodes. Throwing from `beforeCommit` aborts the transaction, which is the documented Neo4j behavior.

**Patch (`SnapshotPatcher.apply(CompiledCausalGraph g, ChangeBatch b) -> CompiledCausalGraph`).** Copy-on-write: the new snapshot shares every untouched `long[]` row and CSR array with the old one, so readers on the old snapshot are unaffected (snapshot isolation is preserved without locks).

- *Edge insertion* $u \to v$: rebuild the two CSR arrays (linear; or keep an overflow adjacency per node and merge lazily, decide by measurement), then
  ```
  gainDes = des[v] | {v};  for a in an(u) ∪ {u}: des[a] = des[a] | gainDes        // rows: |an(u)|+1
  gainAnc = anc[u] | {u};  for d in de(v) ∪ {v}: anc[d] = anc[d] | gainAnc        // rows: |de(v)|+1
  layer: relabel v and de(v) in topological order if layer[v] <= layer[u]
  ```
  Work is $(|\mathrm{an}(u)| + |\mathrm{de}(v)| + 2)\cdot\lceil|V|/64\rceil$ word ORs; state this bound in the paper.
- *Edge deletion* $u \to v$: the affected cone is $C = \mathrm{de}(v) \cup \{v\}$ for ancestor rows and $C' = \mathrm{an}(u) \cup \{u\}$ for descendant rows. Recompute `anc` for $C$ in topological order from the (updated) parents, and `des` for $C'$ in reverse topological order from children; rows outside the cones are unchanged because their closures do not pass through the deleted edge. Cost $O((|C| + |C'|)\cdot|V|/64 + |E_C|\cdot|V|/64)$, which is the "cone recompute" the paper describes; full recompile is the fallback only when $|C| + |C'|$ exceeds a threshold $\theta$ (default $|V|/2$, measured in RQ3).
- *Variable insertion*: append index $n$, grow every row by one word only when $n \bmod 64 = 0$ (otherwise the spare bits already exist); *variable deletion*: apply deletion of its incident edges, then mark the index free (keep it, do not compact; compaction happens at the next full compile).
- *`latent` flip*: copy `indexToVar[i]` with the new flag; closures untouched.
- *Batches*: apply deletions first (cone recompute once for the union of cones), then insertions.

**Wiring.** `afterCommit` calls `((DefaultCausalGraphRuntime) RuntimeRegistry.getInstance()).applyChange(batch)`; `applyChange` is `synchronized` with `compile`/`invalidate`, computes the new snapshot from the current one, and publishes it with one volatile store. If the current snapshot is `null` (never compiled), do nothing; the next query compiles.

**Tests / done when.**
- `IncrementalParityTest`: 1,000 random edit sequences (insert, delete, activate, deactivate, add/remove variable, latent flip) on each fixture; after every edit the patched snapshot's closures, layers, and `parentsOf/childrenOf` equal a fresh `GraphLoader.load` of the same graph.
- `ChangeListenerE2ETest`: in embedded Neo4j, a Cypher write that flips `active` changes the next `causal.ancestors` answer without a `causal.compile` call; a write closing a cycle fails with the documented error and leaves the graph unchanged.
- The existing `invalidate()` path stays as a procedure for manual use.

---

## WP4. Server-side model registry and derived worlds (Oct 13 – Oct 17)

The paper's §3.3 now says worlds live in the DBMS; make it so.

**Files.** `runtime/RuntimeRegistry.java` (map `String -> DefaultCausalGraphRuntime`, key `"base"` default), `procedures/CausalProcedures.java` (every read procedure gains an optional last parameter `model` defaulting to the active model; new `causal.models()`, `causal.use(name)`, `causal.intervene(name, [vars])`, `causal.drop(name)`), `dsl/.../CypherTranslator.java` (emit `CALL causal.use`/`causal.intervene` instead of client-side registry), `dsl/.../Neo4jQueryExecutor.java` (delete `hydrateCurrentGraph` and the `StructuralEvaluator` path; keep `ModelRegistry` only for the in-memory backend).

**Derived world for $do(X)$.** `SnapshotPatcher.apply(base, batch = delete all in-edges of every x in X)` produces the derived snapshot; it shares every untouched row with the base, so the cost is the deletion cone recompute of WP3 and memory is only the touched rows. Register it under the canonical name the DSL already uses. Quantitative statements (`causal.linearGaussianInference`) already accept `doValues`; they now also accept `model`.

**Done when.** `DslPluginE2ETest` runs `DEFINE MODEL`, `INTERVENE`, `USE MODEL`, structural queries on the derived world, `RESET`, and `SHOW MODELS` end to end with the Neo4j backend and no client-side graph; the base snapshot's `compiledAt` is unchanged after `INTERVENE`.

---

## WP5. Benchmark harness, datasets, baselines, correctness validation (Oct 6 – Oct 31, in parallel)

**Layout.** New Maven module `bench/` (JMH) and a Python package `bench/py/` (reference tools). Everything driven by `bench/run_all.sh` that writes `bench/results/*.csv` and `bench/figures/*.pdf`.

**Datasets** (`bench/datasets/`). (i) bnlearn networks in `.bif`: asia, sachs, child, alarm, hepar2, win95pts, pathfinder, andes, pigs, link, munin (download script with checksums; loader `BifLoader` keeps structure only). (ii) Synthetic DAGs: generator `SyntheticDag(n, m, seed, model ∈ {er, ba})` producing edges oriented by index (ER: each pair with probability $p = m/\binom{n}{2}$; BA: preferential attachment with $m$ edges per node, oriented from newer to older, then reversed so early nodes are sources); grid $n \in \{10^2, 10^{2.5}, 10^3, 10^{3.5}, 10^4\}$, average degree $\in \{2, 4, 8\}$, seeds 0–4. (iii) COVID DAG. A `datasets.csv` with $|V|$, $|E|$, density, longest path, memory of the closures for Table 3.

**Baselines** and what each can answer (this is Table 4 of the paper, the coverage matrix):

| Operator | Causal4j index | DFS baseline | Cypher | GDS | dagitty / pgmpy / causal-learn / DoWhy |
|---|---|---|---|---|---|
| ancestors / descendants / reaches | yes | yes | `MATCH (a)-[:CAUSES*]->(b)` | BFS/DFS procs | yes |
| confounders / mediators / colliders | yes | yes | path patterns | no | yes (dagitty, pgmpy) |
| d-separation | yes | yes | no | no | yes |
| adjustment (canonical, minimal) | yes | yes (same algorithm, DFS closures) | no | no | dagitty, pgmpy (backdoor) |

The DFS baseline is the current code path (`ancestorsDfs`, `bayesBallReference`, and the WP2 algorithms run with DFS-computed sets), kept as a switch `-Dcausal.index=off`. The Cypher baseline runs the variable-length pattern in the same embedded Neo4j. The GDS baseline uses `gds.bfs.stream`/`gds.dfs.stream` on a projected graph. The external baseline is a Python script that exports the DAG from Neo4j (`MATCH ... RETURN`), builds the library object, runs the query, and reports (export time, build time, query time) separately.

**Workloads.** Per dataset, 200 random $(t,y)$ pairs with $t$ an ancestor of $y$, and 200 random $(x,y,Z)$ triples with $|Z| \in \{0, 1, 3, 10\}$; the same seeds for every system. JMH: 5 forks, 5 warmup, 10 measurement iterations, report median and p99; memory via `MemoryMXBean` after `System.gc()` and via `-XX:+PrintGCDetails` for the closures' retained size. Hardware, JVM, Neo4j version recorded automatically into `results/environment.json`.

**Experiments, one script each.**
- RQ1 latency/throughput per operator per system (Fig. 4, log-log vs $|V|$).
- RQ2 build time and resident memory vs $|V|$ and density, both branches (closures vs. layer-pruned DFS), and the crossover as a function of $|V|$ (Table 5).
- RQ3 incremental vs recompile: batch sizes $\{1, 10, 100, 1000\}$, edit types {insert, delete, activate flip, latent flip, add/remove variable}, cone size distribution; plus the **mixed workload**: a stream interleaving one edit every $k$ queries ($k \in \{1, 10, 100\}$) and measuring query latency for (i) external round-trip with re-export, (ii) invalidate-and-recompile, (iii) patch (Fig. 5).
- RQ4 scalability: the synthetic grid.
- RQ5 correctness: d-separation verdicts vs dagitty and pgmpy; canonical and minimal adjustment sets vs dagitty on every dataset; the JSON produced here is also the fixture WP2's tests read.

**Done when.** `run_all.sh` regenerates every figure and table of §8 from scratch on a clean checkout; the paper's `\TODO` numbers are replaced by the CSV values through a small `results_to_tex.py` that emits `paper/numbers.tex` macros so numbers are never typed by hand.

---

## WP6. Artifact (Nov 1 – Nov 4)

Public GitHub repository (or Zenodo snapshot with DOI) containing plugin, dsl, bench, datasets script, `run_all.sh`, environment file, and a README that maps every figure and table to the command producing it. Add the AI-generated-content acknowledgement to the paper with the tools used and the sections concerned. Collect ORCIDs for the four authors. Internal review round with the co-authors on November 5–7; buffer November 8–10.

---

## Things explicitly not done for this submission

ADMG / latent projection index; front-door and instrumental identification; batched enumeration sharing work across queries; MVCC across multiple writers; new DSL surface beyond the `model` argument and `adjustmentSets` modes. All are listed as future work in the paper.
