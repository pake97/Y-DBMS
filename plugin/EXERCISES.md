# Plugin exercises (Student A)

This is your assignment brief. You own the **`plugin/`** module — the Neo4j
procedures and the algorithms over the compiled causal graph. The **`dsl/`**
module is built on top of your work and is owned separately; it talks to the
plugin only through `com.example.causal.api.*`.

Each exercise below is a stub that compiles and is wired into the system but is
not yet implemented. When you implement one, a feature the DSL already exercises
"lights up" — that is your **definition of done** (see each section).

## Ground rules

- **Keep procedures thin.** No graph logic in `CausalProcedures` / `CausalFunctions`;
  algorithms live in `runtime/`.
- **The DSL sees only `com.example.causal.api.*`.** When an exercise needs a new
  contract, design it on that boundary (new `CausalGraphRuntime` method + DTO +
  `ProcedureNames`/`ResultColumns` entry). The `ProcedureContractTest` enforces
  procedure-name / YIELD-column consistency — keep it green.
- **DTOs** (`com.example.causal.dto`) are plain classes with `public final` fields
  (Neo4j maps rows by reflection — not records).
- Java 17.

## Read this first: the worked example

**`runtime/algorithms/PathAnalyzer.java`** is fully implemented and tested
(`runtime/algorithms/PathAnalyzerTest`). It is the pattern every exercise below
follows: a class constructed from a `CompiledCausalGraph`, operating on variable
**id strings**, reusing `ReachabilityIndex` for ancestor/descendant sets. Study
it before starting.

---

## Tier 1 — core identification algorithms

These run on the structural DAG and unblock the headline `causal.*` queries.

### 1. d-separation — `runtime/DefaultCausalGraphRuntime.isDSeparated`
Currently returns `DSeparationResult.unimplemented()`.

Test whether X ⟂ Y | Z in the DAG. Recommended approach: **Bayes Ball** on the
DAG directly (send balls from X, track which reach Y under conditioning set Z;
no moral graph needed — works with today's forward/reverse adjacency). Return
`DSeparationResult.separated(explanation)` or `.connected(explanation)`.

Model it on `PathAnalyzer`: you may add a `DSeparation` class under
`runtime/algorithms`. Building blocks: `graph.parentsOf(id)`, `graph.childrenOf(id)`,
`graph.topoOrder()`, and `new ReachabilityIndex(graph).ancestors/descendants(id)`.

**Done when:** `causal.dsep` returns correct answers (write a unit test on a small
DAG, e.g. a v-structure X→Z←Y where X and Y are d-separated given ∅ but connected
given Z).

### 2. adjustment sets (backdoor criterion) — `runtime/DefaultCausalGraphRuntime.candidateAdjustmentSet`
Currently returns `AdjustmentCandidateResult.unimplemented()`. **Do d-separation first** — this builds on it.

A set Z satisfies the backdoor criterion for (treatment, outcome) when:
1. No node in Z is a descendant of treatment, and
2. Z blocks every backdoor path — i.e. `treatment ⟂ outcome | Z` in the graph
   with treatment's *outgoing* edges removed (reuse `isDSeparated` on that
   mutilated graph).

Collect every valid Z into `candidateSets`; `adjustmentSetExists` is true iff at
least one was found.

**Done when:** re-enable the `@Disabled` test
`identify_returnsAdjustmentSetViaPlugin` in `dsl/.../DslPluginE2ETest.java` — it is
disabled *only* because this method is stubbed — and it passes (Treatment→Retention30
identifiable, adjustment set contains the confounders {Age, PriorUsage}).

### 3. graph-change handling — `lifecycle/CausalGraphChangeListener`
Today every relevant graph mutation triggers a full recompile. Implement (a)
incremental snapshot updates for added/removed `:CAUSES` edges and property
changes, and (b) validation that a new edge does not create a cycle.

---

## Tier 2 — the quantitative layer (linear-Gaussian SEM)

This is the new, highest-value exercise. The DSL already produces numeric
interventional answers **in-memory**; your job is the same engine on the
plugin's persisted graph so the DSL can get numbers against a live Neo4j.

### 4. linear-Gaussian inference — `runtime/algorithms/LinearGaussianInference.java`
All three methods throw `UnsupportedOperationException`.

Model: each variable `V = intercept_V + Σ_{P∈parents(V)} β_{P→V}·P + ε_V`,
`ε_V ~ N(0, σ²_V)`. Implement:
- `interventionalMeans` — propagate in `graph.topoOrder()`: μ(V)=intercept+Σ β·μ(P).
- `interventionalVariances` — incremental covariance recursion
  `Cov(V,U)=Σ β_{P→V}·Cov(P,U)`, `Var(V)=ΣΣ β·β·Cov(P,Q)+σ²_V`.
- `totalEffect` — unit-impulse propagation (∂treatment=1, ∂root=0, ∂V=Σ β·∂parent).

Sub-steps (see the class javadoc):
1. **Schema + loader.** The compiled graph carries no parameters today. Decide a
   schema (e.g. `:Variable {intercept, noise}`, `:CAUSES {weight}`) and load it in
   `runtime/compile/GraphLoader` into `CompiledCausalGraph` (add accessors).
2. **Engine.** Implement the three methods above.
3. **Contract + procedures.** Add the `api.*` surface + a `causal.*` procedure so
   the DSL's `Neo4jQueryExecutor` can call it. This contract is yours to design.
4. **do-operator.** `do(X=x)` fixes μ(X)=x, Var(X)=0, and removes X's incoming
   edges. A fixed variable then has empty parents and zero noise, so the general
   recursion yields the interventional answer with no special case.

**Reference oracle:** `dsl/src/main/java/com/example/causal/dsl/exec/LinearGaussianSEM.java`
is a complete, tested implementation of this exact math (over the DSL's in-memory
graph). Port it; your numbers must match. Concrete checks from `CausalDSLTest`:
- chain `X→M→Y` with β = 2, 3: total effect `6`; `E[Y|do(X=4)] = 24`;
  `do(X=1)` ⇒ `E[Y]=6`, `Var[Y]=10`.
- confounded fork `Z→X (5)`, `Z→Y (7)`, `X→Y (2)`: `do()` cuts Z→X so the total
  effect is `2` (the backdoor is excluded); `do(X=3)` ⇒ `E[Y]=6`, `Var[Y]=50`.

**Done when:** the engine reproduces those numbers and the DSL's Neo4j path returns
them for numeric `INTERVENE … RETURN DISTRIBUTION/EFFECT/EXPECTATION`.

### 4b. counterfactual inference — `runtime/algorithms/CounterfactualInference.java`
Throws `UnsupportedOperationException`. Builds on #4 (and its parameter schema).

Linear-Gaussian unit counterfactual (`WHAT WOULD Y HAVE BEEN IF X = x*`, given the
factual world): holding the noise fixed, `Y` is affine in `X`, so

```
Y_cf = Y_factual + totalEffect(X→Y) · (x* − X_factual)
```

— reuse `LinearGaussianInference.totalEffect`. (This is Pearl's
abduction→action→prediction collapsed for a linear system.)

**Reference oracle:** `dsl/src/main/java/com/example/causal/dsl/exec/CounterfactualEvaluator.java`.
Worked check: chain `X→M→Y` (β = 2, 3), `GIVEN X=1, Y=10`, `IF X=3` ⇒ `Y_cf = 10 + 6·(3−1) = 22`.

**Done when:** the DSL's Neo4j path answers `COUNTERFACTUAL` numerically against a
parameterized live graph, matching the oracle.

---

## Tier 2 — performance (optional, after correctness)

### 5. bitset reachability — `runtime/algorithms/ReachabilityIndex`
Replace the per-query DFS ancestor/descendant computation with precomputed
O(n/64) bitset lookups.

### 6. dense graph layout — `runtime/compile/CompiledCausalGraph`
Replace `Map<String,Set<String>>` adjacency with dense `int[]`/`int[][]` keyed by
a topo index; cache the `ReachabilityIndex` in `DefaultCausalGraphRuntime` (there
is a commented field + TODO there). Extend `logStructure()` with the new structures.

### 7. expose topo index — `procedures/CausalFunctions.topoOrder`
Surface the topo index from `CompiledCausalGraph` through `CausalGraphRuntime`.

---

## Build & test

```bash
mvn test -pl plugin                                  # all plugin tests
mvn test -pl plugin -Dtest=PathAnalyzerTest          # fast pure-Java unit tests
mvn test -pl plugin -Dtest=CausalProceduresTest      # embedded-Neo4j integration (~2 min)
mvn test                                              # full reactor (plugin + dsl)
```

Write unit tests next to the algorithm, in the style of `PathAnalyzerTest` /
`TopologicalSorterTest` — pure Java, no Neo4j harness.

## Suggested order

1. d-separation (#1) → adjustment sets (#2) — re-enables the disabled DSL E2E test.
2. linear-Gaussian SEM (#4) — the quantitative layer; oracle-checked.
3. performance (#5–#7) once everything is correct.
