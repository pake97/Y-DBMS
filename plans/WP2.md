# WP2: query processing over the index

Instructions for Claude Code (owner: K). Read `CLAUDE.md`, `CODE-PLAN.md`, `plans/WP1.md`
and `plans/WP1.5.md` first. Branch `feat/index-wp2-queries` from `feat/index` **after
WP1.5 is merged** (`git merge-base --is-ancestor d879117 HEAD` must print nothing and exit
0). Work in `plugin/` only; the DSL module is WP4. One PR against `feat/index`, one commit
per step below.

## Goal

Make every structural operator read the closures of WP1, replace the exponential
adjustment-set enumeration with the constructive criterion and the polynomial-delay
separator framework of van der Zander, Liśkiewicz and Textor (AIJ 2019), and switch
identification from Pearl's backdoor criterion to the generalized adjustment criterion
(Shpitser et al. 2010; Perković et al. 2018), which is what dagitty implements and what
the paper's RQ5 validates against. The paper (`paper/sections/05_indexing.tex`) states
three cost tiers; this WP is what makes them true:

- (a) bitset operators, $O(w)$ with $w = \lceil n/64 \rceil$: reachability, confounders,
  mediators, causal nodes, forbidden set, canonical set; colliders in $O(|E|)$ bit tests;
- (b) Bayes-Ball d-separation, $O(|V|+|E|)$ with bitset membership and ancestral masking;
- (c) identification: one canonical-set construction plus one masked Bayes-Ball run;
  enumeration of minimal sets with polynomial delay.

## House rules

- Java 17. Algorithms in `runtime/algorithms/`, procedures thin, DTOs public-field.
- Everything below works on dense `int` indices and `long[]` bitsets from
  `CompiledCausalGraph` (`indexOf`, `idOf`, `ancestorBits(i)`, `descendantBits(i)`,
  `reaches`, `childrenOf(int)`, `parentsOf(int)`, `wordCount`, `toIds`) and `BitSet64`.
  Convert from/to `String` ids only at the API boundary in `DefaultCausalGraphRuntime`.
- Never mutate a `long[]` obtained from the snapshot; `BitSet64.copy` first.
- Keep the old implementations as reference methods for the parity tests (names end in
  `Reference`); they are also the DFS baseline of the evaluation (CODE-PLAN.md WP5).
- `mvn -pl plugin test` after every step. The embedded-Neo4j tests take ~2 min.

## Definitions (use these names in code and Javadoc)

For treatment $t$ and outcome $y$ (dense indices), with `anc`/`des` the closures:

```
mediators(t,y)  = des[t] & anc[y]
cn(t,y)         = mediators(t,y) | {y}                 if reaches(t,y), else empty   // causal nodes
forb(t,y)       = {t} | cn(t,y) | OR_{c in cn(t,y)} des[c]                             // forbidden set
adj(t,y)        = (anc[t] | anc[y]) & ~forb(t,y) & ~{t,y}                             // canonical set
confounders(t,y)= anc[t] & anc[y]
colliders(x,y)  = { z not in anc[x]|anc[y]|{x,y} : exists p in parents(z) with p in des[x]|{x}
                                                    and exists q in parents(z) with q in des[y]|{y} }
proper backdoor graph G_pbd(t,y) = G minus the edges t->c for c in cn(t,y)
Z satisfies the adjustment criterion iff  (Z & forb) == 0  and  t ⊥ y | Z  in G_pbd
Effect identifiable by adjustment  iff  adj(t,y) satisfies the criterion              (constructive criterion)
```

## Step 1: `PathAnalyzer` over bitsets

Rewrite `runtime/algorithms/PathAnalyzer.java`. Keep the four public `Set<String>`
methods with identical signatures and semantics (`mediators`, `colliders`, `confounders`,
`backdoorVariables`); add index-level methods returning `long[]`:

```java
public long[] mediatorsBits(int t, int y)
public long[] confoundersBits(int x, int y)
public long[] causalNodesBits(int t, int y)        // cn
public long[] forbiddenBits(int t, int y)          // forb
public long[] canonicalBits(int t, int y)          // adj
public long[] collidersBits(int x, int y)
public long[] backdoorVariablesBits(int t, int y)  // BFS on the skeleton from parents(t), barrier = forb(t,y) | {t}; visited as long[]
```

`backdoorVariables` keeps its current meaning (variables on undirected paths from a parent
of $t$ to $y$ that avoid $t$ and its descendants) but uses `forbiddenBits` minus `{y}` as
the barrier, which is the same set under the old definition (descendants of $t$) plus
$t$. Move the current bodies to `PathAnalyzerReference` (test scope is fine) for parity.

## Step 2: `DSeparation` with masking

Create `runtime/algorithms/DSeparation.java`:

```java
public final class DSeparation {
    /** Bayes-Ball on the DAG. cutOutgoing: nodes whose outgoing edges are ignored;
     *  cutOnlyTo (nullable): if non-null, an outgoing edge u->c of a cut node is ignored only when c is in cutOnlyTo. */
    public static boolean separated(CompiledCausalGraph g, long[] x, long[] y, long[] z, long[] cutOutgoing, long[] cutOnlyTo)
    public static boolean separated(CompiledCausalGraph g, long[] x, long[] y, long[] z)   // no cuts
    public static long[] ancestralMask(CompiledCausalGraph g, long[] x, long[] y, long[] z) // x|y|z|OR anc[v] for v in x|y|z
}
```

Implementation requirements:

1. Ball states are `(node, fromParent)`; two visited bitsets `visitedFromParent`,
   `visitedFromChild`; work queue an `int[]` ring buffer of capacity `2n` holding
   `node*2 + dir`. No object allocation per state.
2. Rules (same as the current `bayesBallDSeparated` in `DefaultCausalGraphRuntime`):
   at a node in `z`: entered from parent → pass to parents; entered from child → stop.
   At a node not in `z`: entered from parent → pass to children; entered from child →
   pass to parents and children. Start states: every $x$ with both directions.
   Return `false` (connected) as soon as a state's node is in `y`.
3. **Ancestral masking**: compute `mask = ancestralMask(...)` once and never enqueue a
   node with its bit clear. Cite Geiger, Verma, Pearl 1990 in the Javadoc: d-separation
   depends only on the ancestral subgraph of $x \cup y \cup z$.
4. Cuts: when expanding children of `u` with `cutOutgoing[u]` set, skip child `c` if
   `cutOnlyTo == null || cutOnlyTo[c]`.
5. Vacuous cases as today: empty `x` or `y` → separated; `x ∩ y ≠ ∅` → connected.
6. Move the current Bayes-Ball in `DefaultCausalGraphRuntime` to
   `DSeparationReference.separated(g, Set<String> x, y, z, Set<String> cutOutgoing)`
   (test scope) unchanged, for parity and as the WP5 baseline.
7. A system property `causal.dsep.mask=false` disables masking (keep it; RQ1 measures
   the masked/unmasked ablation).

`DefaultCausalGraphRuntime.isDSeparated` delegates to `DSeparation.separated`; the
`DSeparationResult.explanation` string stays as today ("found an active path reaching `<id>`" /
"no active path connects ...").

## Step 3: `Adjustment`: decision by the canonical set

Create `runtime/algorithms/Adjustment.java`:

```java
public final class Adjustment {
    public record Decision(boolean identifiable, long[] canonical, long[] forbidden) {}
    public static boolean isValid(CompiledCausalGraph g, int t, int y, long[] z)     // criterion test in G_pbd
    public static Decision decide(CompiledCausalGraph g, int t, int y)              // constructive criterion
}
```

`isValid`: `if (BitSet64.intersects(z, forb)) return false;` then
`DSeparation.separated(g, {t}, {y}, z, cutOutgoing = {t}, cutOnlyTo = cn(t,y))`.
`decide`: `adj = canonicalBits(t,y)`; `identifiable = isValid(g,t,y,adj)`.
Special cases: `t == y` → identifiable with empty set; `!reaches(t,y)` → `cn` empty, `forb = {t}`,
and the decision is still computed (the effect is zero but the criterion is well
defined; return identifiable with the canonical set, explanation says "no directed path").

Delete `combinationsOfSize`, `buildCombinations`, `eligibleAdjustmentIds`,
`isBackdoorBlocked` from `DefaultCausalGraphRuntime`.

## Step 4: minimal adjustment sets with polynomial delay

Add to `Adjustment`:

```java
/** Minimal separators of t and y in G_pbd(t,y) within R = V \ (forb | {t,y}), i.e. all inclusion-minimal adjustment sets. */
public static void listMinimal(CompiledCausalGraph g, int t, int y, int limit, Consumer<long[]> emit)
public static Optional<long[]> findMinimal(CompiledCausalGraph g, int t, int y)     // one minimal set, or empty if none
```

Implement the separator framework of van der Zander et al. (2019) on `G_pbd`; the three
routines take constraint sets `I ⊆ Z ⊆ R`:

```
findSep(I, R):
    Z = (anc[t] | anc[y] | OR_{v in I} anc[v] | I) & R & ~{t,y}
    return separated_pbd(Z) ? Z : NONE
    // if any separator with I ⊆ Z ⊆ R exists, this Z is one (Theorem in vdZ 2019)

findMinSep(I, R):
    Z' = findSep(I, R); if NONE return NONE
    A  = ancestralMask({t},{y},Z')                   // nodes of the ancestral subgraph
    M  = moralize(G[A])                              // undirected: skeleton of G[A] plus edges between co-parents in A
    // the two passes below are undirected BFS in M that never enter the removed nodes
    Z1 = I | (Z' & reach_M(t, removed = Z' \ I))     // keep candidates reachable from t when the others are removed
    Z  = I | (Z1 & reach_M(y, removed = Z1 \ I))
    return Z                                         // inclusion-minimal, I ⊆ Z ⊆ R

listMinSep(I, R, emit):
    if findMinSep(I, R) is NONE: return
    pick v in R \ I  such that v is not forced: v is "free" if findMinSep(I, R \ {v}) is not NONE or findMinSep(I | {v}, R) is not NONE
       (simplest correct choice: any v in (findMinSep(I,R)) \ I; if none, that set is the unique answer in this branch)
    if no free v: emit(findMinSep(I, R)); return
    listMinSep(I | {v}, R, emit)        // branch: v forced in
    listMinSep(I, R \ {v}, emit)        // branch: v excluded
```

Where `separated_pbd(Z)` is `DSeparation.separated(g, {t}, {y}, Z, {t}, cn)`, and
`moralize(G[A])` is built on demand as adjacency bitsets over the masked nodes only
(`long[n][]` lazily allocated for nodes in `A`; this is the only per-query allocation
proportional to the mask). Stop when `limit` sets have been emitted (`limit <= 0` means
no limit). Every emitted set must pass `isValid`; assert it in a test, not in production
code.

Set `R = V & ~forb & ~{t,y}` and `I = ∅` for the public methods. `findMinimal` is
`findMinSep(∅, R)`.

Complexity note for the Javadoc: each `findMinSep` is $O(|V|+|E|)$ plus closure unions;
`listMinSep` performs at most $O(|V|)$ `findMinSep` calls between two emitted sets, so the
delay is polynomial regardless of the number of answers.

## Step 5: API and procedures

- New `api/AdjustmentSetsResult`:
  ```java
  public record AdjustmentSetsResult(boolean identifiable, Set<VariableRef> canonical, Set<VariableRef> forbidden,
                                     List<Set<VariableRef>> sets, String mode, String explanation) {}
  ```
- `CausalGraphRuntime` gains
  `AdjustmentSetsResult adjustmentSets(VariableRef t, VariableRef y, String mode, int limit)` with
  `mode ∈ {"canonical", "minimal", "all-minimal"}`: `canonical` returns `sets = [adj]` if
  identifiable else `[]`; `minimal` returns one minimal set; `all-minimal` streams
  `listMinimal` up to `limit` (default 100). Explanation strings: `"identifiable by
  adjustment; canonical set has k variables"`, `"not identifiable by adjustment: the
  canonical set does not block all non-causal paths"`, `"found m minimal adjustment
  set(s)"`.
- Keep `candidateAdjustmentSet(t, y)` on the interface **as an alias** of
  `adjustmentSets(t, y, "all-minimal", 100)` mapped into the old
  `AdjustmentCandidateResult`, so the DSL keeps working until WP4 switches it.
- New procedure `causal.adjustmentSets(treatment, outcome, mode = 'all-minimal', limit = 100)`
  returning a new `dto/AdjustmentSetsRecord` (public fields `identifiable`, `canonical`
  (`List<Node>`), `forbidden`, `sets` (`List<List<Node>>`), `mode`, `explanation`);
  `ProcedureNames.ADJUSTMENT_SETS = "causal.adjustmentSets"`. `causal.adjustmentCandidate`
  stays and calls the alias.
- New procedure `causal.minimalSeparator(x, y, include, restrict)` is **not** in this WP.

## Step 6: tests

1. **Parity** (`runtime/algorithms/QueryParityTest`): on every `Graphs` fixture used by
   `IndexParityTest` plus the three synthetic configs: the four `PathAnalyzer` methods
   equal `PathAnalyzerReference`; `DSeparation.separated` equals `DSeparationReference`
   on 2,000 random $(X, Y, Z)$ triples per fixture with $|X|,|Y| \in \{1,2\}$,
   $|Z| \in \{0,1,3,10\}$, including triples where $Z$ contains a descendant of a
   collider; masked equals unmasked (`causal.dsep.mask=false`) on the same triples.
2. **Criterion** (`runtime/algorithms/AdjustmentCriterionTest`): hand-built DAGs where the
   backdoor and generalized criteria differ. At minimum: (i) `T->Y, T->D` with `D` not on
   a causal path: `{D}` is valid under the generalized criterion (the old code rejected
   every descendant of `T`); (ii) `T->M->Y, C->T, C->Y`: `{C}` valid, `{M}`, `{C,M}` forbidden;
   (iii) the Shpitser et al. 2010 Fig. 1 example and the Perković et al. 2018 example
   where `adj` is valid but no backdoor set of smaller size exists; (iv) a graph where
   the effect is not identifiable by adjustment (latent confounder modeled as an
   observed node that is placed in `forb` by construction is not enough; use
   `U->T, U->Y, T->Y` with `U` excluded from `R` by passing it through `forb` manually,
   or simply assert `decide` on `T->Y, T->M->Y, M<-U->Y`... pick one from the papers and
   cite the figure in the test name).
3. **Minimal sets** (`runtime/algorithms/MinimalSeparatorTest`): for every fixture and
   200 random $(t,y)$ pairs with `reaches(t,y)`: every emitted set passes `isValid`; is
   inclusion-minimal (removing any element breaks `isValid`); the family has no
   duplicates; `findMinimal` returns an element of the family or empty iff the family is
   empty; `decide().identifiable` iff the family is non-empty. On `asia`, `sachs`,
   `alarm` (hand-written edge lists in test resources, copied from bnlearn), compare the
   full family with the dagitty reference file `plugin/src/test/resources/dagitty-adjustment.json`
   (format: `{"dag": "...", "pairs": [{"t": "...", "y": "...", "canonical": [...], "minimal": [[...], ...]}]}`).
   Until WP5 produces that file, generate it once by hand with dagitty's web interface
   or R package for 20 pairs on `alarm` and 10 on `sachs`, and commit it; name the
   commit "test fixture: dagitty reference (manual, to be regenerated by WP5)".
4. **Existing tests**: `AdjustmentCandidateTest`, `DsepAndAdjustmentE2ETest`,
   `CovidSeedDagE2ETest`, `CausalProceduresTest` will change where the old code returned
   minimum-cardinality backdoor sets and the new code returns all inclusion-minimal
   generalized adjustment sets. For each changed expectation, write in the PR
   description the pair, the old family, the new family, and why the new one is
   correct under the generalized criterion. Do not weaken an assertion to make it pass;
   change its expected value with the justification.
5. **Smoke timing** (tag `slow`): `Adjustment.decide` on `Graphs.randomDag(5000, 0.002, 0)`
   for 1,000 random pairs completes in under 5 s total; the old enumeration is not
   re-run.

## Done when

- `grep -rn "combinationsOfSize\|buildCombinations\|eligibleAdjustmentIds" plugin/src/main` returns nothing.
- `mvn -pl plugin test` green; `-Dgroups=slow` green.
- `QueryParityTest`, `AdjustmentCriterionTest`, `MinimalSeparatorTest` green, the dagitty
  fixture committed.
- `causal.adjustmentSets` callable from Cypher in `CausalProceduresTest` with all three modes.
- `paper/sections/05_indexing.tex` describes what the code does; if an implementation
  detail had to deviate (e.g., the free-variable choice in `listMinSep`), note it in the
  PR so the paper can be adjusted.

## Out of scope (later WPs)

The DSL module (`CypherTranslator` still calls `causal.adjustmentCandidate`; the in-memory
`StructuralEvaluator.identify` still uses the old criterion and will disagree with the
plugin until WP4 aligns it); `causal.minimalSeparator`; the `-Dcausal.index=off` switch
(WP5); any change to `lifecycle/` or `SnapshotPatcher` (WP3).


---

## Addendum, October 2 (after WP5a)

Read this before Step 3. Three corrections and one small extra task.

**A. Latent variables are not eligible for adjustment.** The definitions above take
$R = V \setminus (\forb \cup \{t,y\})$. That is wrong for a model with latent nodes: an
adjustment set may contain observed variables only, and dagitty (the RQ5 oracle, which
WP5a ran with the `latent` flags from `dev/seed-dag.cypher`) excludes latents, which is
why 6 covid pairs come out non-identifiable in `bench/reference/covid/adjustment.json`.
Change:

```
obs             = bitset of i with !variableAt(i).latent()        // precompute once per snapshot in PathAnalyzer or CompiledCausalGraph
R(t,y)          = obs & ~forb(t,y) & ~{t,y}
adj(t,y)        = (anc[t] | anc[y]) & R(t,y)                       // canonical set over observed variables only
```

`decide`, `findMinimal`, `listMinimal` all use this `R`. The forbidden set and the
d-separation test are unchanged (latent nodes still block/open paths; they are simply
not allowed into $Z$). Add to `AdjustmentCriterionTest`: `U->T, U->Y, T->Y` with `U`
latent is **not** identifiable; with `U` observed it is, with canonical `{U}`. The
paper's Section II and V definitions need the same restriction; A will edit the text.

**B. Fixture paths.** Replace the hand-made fixture in Step 6.3 with the WP5a output:
`bench/reference/alarm/adjustment.json`, `bench/reference/sachs/adjustment.json`, and
`bench/reference/covid/adjustment.json` (the covid file is the one with latents). Format
per pair: `{"t","y","canonical": [...] | null, "minimal": [[...],...] | null,
"minimal_count", "identifiable"}`; a `null` `minimal` means the family exceeded the
limits and only `minimal_count` is known. The test compares the full family where it is
non-null, and for null families checks that every set `listMinimal` emits under
`limit = 100` is valid and inclusion-minimal, and that `identifiable` matches.
D-separation parity against `bench/reference/<dataset>/dsep.json` on the 200 triples of
`asia`, `alarm`, `hepar2`, `covid` goes into `QueryParityTest` as well.

**C. Expect a skewed workload.** On sparse graphs 80–99% of the random triples are
separated. Parity must therefore also run on the connected cases explicitly: take the
triples whose reference verdict is `false` across all bnlearn datasets and include all of
them in the parity set, so the "ball reaches y" branch is exercised a few hundred times,
not a handful.

**D. `causal.topoOrder` returns -1.** `CausalFunctions.topoOrder(variableId)` is a stub
because `CausalGraphRuntime` has no topological accessor. Add
`int topoPosition(VariableRef v)` to the interface (returns `-1` for unknown ids),
implement it in `DefaultCausalGraphRuntime` through `snapshot.topoIndex(id)`, wire the
function, and add one assertion in `CausalProceduresTest` on the seed DAG
(`covid19_burden` precedes `interventions`). Ten lines; do it in Step 5.
