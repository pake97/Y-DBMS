# Plugin exercises (Student A)

This is your assignment brief. You own the **`plugin/`** module — the Neo4j
procedures and the algorithms over the compiled causal graph. The **`dsl/`**
module is built on top of your work and is owned separately; it talks to the
plugin only through `com.example.causal.api.*`.

Every exercise is a stub that **already compiles and is wired in** (or, for the
quantitative layer, a stub class with a clear contract to design). When you
implement one, a feature the DSL already exercises "lights up" — that is your
**definition of done**, made concrete per exercise below.

> The DSL ships **reference implementations** of the hard algorithms in its
> in-memory engine (`dsl/.../model/CausalModel.java`,
> `dsl/.../exec/LinearGaussianSEM.java`, `…/CounterfactualEvaluator.java`). They
> are your **correctness oracle** — your plugin output must match their numbers.

## Ground rules

- **Keep procedures thin.** No graph logic in `CausalProcedures` / `CausalFunctions`;
  algorithms live in `runtime/`. A procedure validates args, calls the runtime,
  maps the result to a DTO.
- **The DSL sees only `com.example.causal.api.*`.** When an exercise needs a *new*
  contract, design it on that boundary: a `CausalGraphRuntime` method + a DTO +
  a `ProcedureNames` / `ResultColumns` constant + a `@Procedure`. `ProcedureContractTest`
  enforces that procedure names and YIELD columns stay consistent — keep it green.
- **DTOs** (`com.example.causal.dto`) are plain classes with `public final` fields
  (Neo4j maps rows by reflection — **not** records).
- **`@Procedure(name = …)`** must reference a `ProcedureNames` constant (not a literal).
- Java 17. Consume `tx.execute(...)` results in tests before commit (lazy streams).

## Read this first: the worked example

**`runtime/algorithms/PathAnalyzer.java`** is fully implemented and tested
(`runtime/algorithms/PathAnalyzerTest`). It is the pattern every exercise follows:
a class constructed from a `CompiledCausalGraph`, operating on variable **id
strings**, reusing `ReachabilityIndex` for ancestor/descendant sets. Study it first.

## What is already wired vs. what you build

| Exercise | api method | DTO | procedure | You implement |
|---|---|---|---|---|
| 1 d-separation | ✅ exists | ✅ `DSeparationRecord` | ✅ `causal.dsep` | **runtime method body only** |
| 2 adjustment sets | ✅ exists | ✅ `AdjustmentRecord` | ✅ `causal.adjustmentCandidate` | **runtime method body only** |
| 3 change listener | — | — | — | the listener logic |
| 4 linear-Gaussian SEM | ❌ design it | ❌ design it | ❌ add it | engine **+ schema/loader + full contract** |
| 4b counterfactual | ❌ design it | ❌ design it | ❌ add it | engine **+ contract** (reuses #4) |
| 5–7 performance | — | — | — | internal data structures |

So exercises 1–2 are pure algorithm work — the contract is done. Exercise 4 is the
big one: you also design the api/DTO/procedure and load parameters from Neo4j.

---

## Tier 1 — core identification algorithms

### 1. d-separation — `runtime/DefaultCausalGraphRuntime.isDSeparated`
Returns `DSeparationResult.unimplemented()` today. Procedure `causal.dsep`, DTO
`DSeparationRecord`, and the api method already exist and call this — **you write
only the method body.**

Test whether X ⟂ Y | Z in the DAG. Recommended: **Bayes Ball** on the DAG directly
(send balls from X, track which reach Y under conditioning set Z; no moral graph
needed). Return `DSeparationResult.separated(explanation)` / `.connected(explanation)`.
Building blocks: `graph.parentsOf(id)`, `graph.childrenOf(id)`, `graph.topoOrder()`,
`new ReachabilityIndex(graph).ancestors/descendants(id)`. You may add a
`DSeparation` class under `runtime/algorithms` (model it on `PathAnalyzer`).

**Deliverables:** the method body (+ optional helper class) + a pure-Java unit test.
**Done when:** a v-structure `X→Z←Y` is reported d-separated given ∅ and connected
given Z; chains/forks behave correctly. Oracle: `dsl/.../model/CausalModel` semantics.

### 2. adjustment sets (backdoor criterion) — `runtime/DefaultCausalGraphRuntime.candidateAdjustmentSet`
Returns `AdjustmentCandidateResult.unimplemented()`. Procedure/DTO/api already exist.
**Do #1 first.** A set Z satisfies the criterion for (treatment, outcome) when:
1. no node in Z is a descendant of treatment, and
2. `treatment ⟂ outcome | Z` in the graph with treatment's **outgoing** edges
   removed (reuse `isDSeparated` on that mutilated graph).

Collect every valid Z into `candidateSets`; `adjustmentSetExists` = at least one found.

**Deliverables:** the method body + unit test.
**Done when:** **re-enable** the `@Disabled` test `identify_returnsAdjustmentSetViaPlugin`
in `dsl/src/test/java/com/example/causal/dsl/DslPluginE2ETest.java` (disabled *only*
because this is stubbed) and it passes — Treatment→Retention30 identifiable, adjustment
set contains {Age, PriorUsage}.

### 3. graph-change handling — `lifecycle/CausalGraphChangeListener`
Today every relevant mutation triggers a full recompile (see its TODOs).
**Deliverables:** (a) incremental snapshot updates for added/removed `:CAUSES`
edges and `active` property changes; (b) validation rejecting an edge that would
create a cycle. **Done when:** a unit/integration test mutates the graph and the
snapshot reflects it without a full recompile, and a cycle-creating edge is rejected.

---

## Tier 2 — the quantitative layer (linear-Gaussian SEM)

The DSL already produces numeric interventional answers **in-memory**; your job is
the same engine on the plugin's persisted graph, exposed through a contract you
design. Model: `V = intercept_V + Σ_{P∈parents(V)} β_{P→V}·P + ε_V`, `ε_V ~ N(0, σ²_V)`.

> **Boundary:** this is a database, not an ML system. Parameters (β, intercept,
> noise) are **given** — read them from the graph; do **not** fit them from data.

### 4. linear-Gaussian inference — `runtime/algorithms/LinearGaussianInference.java`
The three methods throw `UnsupportedOperationException`. Implement:
- `interventionalMeans` — topo order: `μ(V)=intercept+Σ β·μ(P)`.
- `interventionalVariances` — covariance recursion `Cov(V,U)=Σ β·Cov(P,U)`,
  `Var(V)=ΣΣ β·β·Cov(P,Q)+σ²_V`. Intervened var ⇒ Var 0.
- `totalEffect` — unit-impulse: `∂treatment=1, ∂root=0, ∂V=Σ β·∂parent`.

**Full deliverables (this is the big one):**
1. **Schema + loader.** The compiled graph has no parameters today. Choose a schema
   — e.g. `:Variable {intercept, noise}` and `:CAUSES {weight}` — and load it in
   `runtime/compile/GraphLoader` into `CompiledCausalGraph` (add accessors:
   `weight(from,to)`, `intercept(id)`, `noise(id)` with defaults 1.0 / 0.0 / 1.0).
2. **Engine.** The three methods above. do(X=x) fixes μ(X)=x, Var(X)=0, removes X's
   incoming edges — a fixed var then has empty parents + zero noise, so the general
   recursion handles it with no special case.
3. **Contract.** Add a `CausalGraphRuntime` method (e.g. `interventionalEffect`),
   a DTO (`public final` fields), `ProcedureNames`/`ResultColumns` constants, and a
   thin `@Procedure` (e.g. `causal.interventionalEffect`). Keep `ProcedureContractTest` green.

**Reference oracle:** `dsl/src/main/java/com/example/causal/dsl/exec/LinearGaussianSEM.java`.
Numbers your engine must reproduce (from `CausalDSLTest`):
- chain `X→M→Y` (β = 2, 3): total effect `6`; `E[Y|do(X=4)] = 24`; `do(X=1)` ⇒ `E[Y]=6`, `Var[Y]=10`.
- confounded fork `Z→X (5)`, `Z→Y (7)`, `X→Y (2)`: total effect `2` (do() cuts Z→X);
  `do(X=3)` ⇒ `E[Y]=6`, `Var[Y]=50`.

**Done when:** a pure-Java unit test reproduces those numbers, **and** an
embedded-Neo4j integration test (style of `CausalProceduresTest`) calls your new
`causal.*` procedure on a parameterized seed graph and gets them back.

> Surfacing this through the DSL's `INTERVENE … RETURN` over Neo4j needs DSL-side
> wiring (persisting parameters + calling your procedure) — that is a separate task
> on the DSL side, **not** part of this exercise. Your contract is what unblocks it.

### 4b. counterfactual inference — `runtime/algorithms/CounterfactualInference.java`
Throws `UnsupportedOperationException`. Builds on #4 and its schema. Linear-Gaussian
unit counterfactual (`WHAT WOULD Y HAVE BEEN IF X = x*`, given the factual world):
holding noise fixed, `Y` is affine in `X`, so

```
Y_cf = Y_factual + totalEffect(X→Y) · (x* − X_factual)
```

— reuse `LinearGaussianInference.totalEffect`. (Pearl's abduction→action→prediction,
collapsed for a linear system.)

**Deliverables:** the engine method + (if exposing it) a contract like #4.3.
**Reference oracle:** `dsl/.../exec/CounterfactualEvaluator.java`. Worked check:
chain (β = 2, 3), `GIVEN X=1, Y=10`, `IF X=3` ⇒ `Y_cf = 10 + 6·(3−1) = 22`.
**Done when:** a unit test reproduces that number against the oracle.

---

## Tier 2 — performance (optional, after correctness)

Behaviour-preserving — every existing test must stay green; correctness first.

### 5. bitset reachability — `runtime/algorithms/ReachabilityIndex`
Replace per-query DFS ancestor/descendant computation with precomputed O(n/64)
bitset lookups. **Done when:** `ReachabilityIndex` / `PathAnalyzer` tests still pass.

### 6. dense graph layout — `runtime/compile/CompiledCausalGraph`
Replace `Map<String,Set<String>>` adjacency with dense `int[]`/`int[][]` keyed by a
topo index; cache the `ReachabilityIndex` in `DefaultCausalGraphRuntime` (commented
field + TODO there). Extend `logStructure()` with the new structures. **Done when:**
the full plugin suite passes unchanged.

### 7. expose topo index — `procedures/CausalFunctions.topoOrder`
Surface the topo index from `CompiledCausalGraph` through `CausalGraphRuntime`
(see the TODO in `CausalFunctions`).

---

## Build & test

```bash
mvn test -pl plugin                                  # all plugin tests
mvn test -pl plugin -Dtest=PathAnalyzerTest          # fast pure-Java unit tests
mvn test -pl plugin -Dtest=CausalProceduresTest      # embedded-Neo4j integration (~2 min)
mvn test                                              # full reactor (plugin + dsl)
```

Write unit tests next to each algorithm, in the style of `PathAnalyzerTest` /
`TopologicalSorterTest` (pure Java, no Neo4j). Add procedure-level checks in the
style of `CausalProceduresTest` (embedded Neo4j).

## Deliverables checklist

- [x] #1 `isDSeparated` implemented + unit test
- [x] #2 `candidateAdjustmentSet` implemented; `identify_returnsAdjustmentSetViaPlugin` re-enabled & green
- [ ] #3 `CausalGraphChangeListener` incremental update + cycle validation
- [x] #4 SEM: schema+loader, engine (means/variances/totalEffect), api+DTO+procedure, unit + integration tests matching the oracle
- [ ] #4b counterfactual engine + unit test matching the oracle
- [ ] #5–7 performance (optional): all existing tests stay green
- [ ] full reactor `mvn test` green

## Suggested order

1. **#1 d-separation → #2 adjustment sets** — runtime-only; re-enables the disabled DSL E2E test.
2. **#4 linear-Gaussian SEM** (then **#4b** counterfactual) — the quantitative layer; oracle-checked; design the contract.
3. **#3 change listener**, then **#5–#7 performance** once everything is correct.
