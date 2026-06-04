# Causal4j

A Neo4j-backed toolkit for causal inference. Two Maven modules:

| Module | What it is |
|---|---|
| [`plugin/`](plugin/) | Neo4j procedures/functions. Compiles a causal DAG stored in Neo4j into an in-memory representation and exposes structural queries (`causal.ancestors`, `causal.adjustmentCandidate`, …) as Cypher-callable procedures. |
| [`dsl/`](dsl/) | A causal-inference DSL (`DEFINE CAUSAL DAG`, `IDENTIFY EFFECT OF`, `INTERVENE ON …`, `COUNTERFACTUAL …`) that either runs in-process against an in-memory model or translates to Cypher calls against the plugin. Full language reference: **[`dsl/README.md`](dsl/README.md)**. |

The DSL depends only on the plugin's `com.example.causal.api.*` package. Everything under `runtime.*` / `procedures.*` is a plugin implementation detail.

## What the DSL does

- **Schema** — declare variables and a DAG (`DEFINE CAUSAL VARIABLE / DAG / MODEL`), optionally with linear-Gaussian parameters (`WEIGHT` on edges, `MEAN` / `NOISE` on variables).
- **Structural queries** — parents, children, ancestors, descendants, mediators, colliders, confounders, backdoor variables.
- **Identification** — `IDENTIFY EFFECT OF … ON …` (backdoor criterion, adjustment sets).
- **Intervention** — `INTERVENE ON x = v` is the do-operator: graph surgery producing a **named derived world** that becomes the active graph; `USE MODEL` / `RESET` / `SHOW MODELS` navigate worlds. `RETURN DISTRIBUTION / EFFECT / EXPECTATION` is structural by default and **numeric** on a parameterized (linear-Gaussian) model.
- **Counterfactual** — `COUNTERFACTUAL … WHAT WOULD Y HAVE BEEN IF X = x*` (closed-form on a linear-Gaussian model).
- **Estimation** — `ESTIMATE EFFECT OF … ON …` returns the SEM-implied total effect.

> **Boundary — this is a database, not an ML system.** Anything ML (training/testing,
> *fitting* parameters from data) is out of scope; a future external ETL performs that
> and writes results back *through* this DSL. Model parameters are **given as input**
> (`WEIGHT`/`MEAN`/`NOISE`); the DSL/plugin only do arithmetic, graph surgery, and
> identification over what is supplied.

---

## Repository layout

```
plugin/
  src/main/java/com/example/causal/
    api/              ← stable API surface (both modules depend on this)
    runtime/          ← graph data structures, algorithms, compiled DAG
      model/          ← Variable record
      compile/        ← GraphLoader, CompiledCausalGraph
      algorithms/     ← TopologicalSorter, ReachabilityIndex, PathAnalyzer
    procedures/       ← thin @Procedure / @UserFunction wrappers
    dto/              ← Cypher row-mapping classes
    lifecycle/        ← transaction listener + extension factory
                        (auto-invalidates/updates the snapshot on writes)
  src/test/java/...   ← unit + embedded-Neo4j integration tests

dsl/
  src/main/antlr4/... ← CausalDSL.g4 grammar
  src/main/java/com/example/causal/dsl/
    ast/              ← sealed Statement hierarchy
    parser/           ← AstBuilder (ANTLR visitor) + ParseException
    model/            ← in-memory CausalModel + ModelRegistry
    exec/             ← QueryExecutor interface
                        InMemoryQueryExecutor (self-contained)
                        Neo4jQueryExecutor    (Bolt driver)
                        CypherTranslator      (AST → Cypher+params)
    CausalDSL.java    ← programmatic entry point
    CausalDSLRepl     ← interactive REPL (executes statements)
    CypherDryRunRepl  ← prints the Cypher a statement WOULD emit (no execution)

dev/
  seed-dag.cypher     ← COVID-19 DAG (25 vars, 78 edges)
  seed.sh             ← loads seed-dag.cypher into local Neo4j
  example.causal      ← end-to-end DSL walkthrough
```

---

## Requirements

- **Java 17** for the plugin (Neo4j 2025.x requirement).
- **Java 21** for the DSL module (uses pattern matching in `switch`).
  The Maven build handles both automatically — install a JDK that has 21
  and it will compile both.
- **Maven 3.9+**.
- **Neo4j 2025.05.0** if you want to run the plugin against a real server
  (optional for DSL in-memory use).

Version pins live in the root [`pom.xml`](pom.xml) (`neo4j.version`,
`neo4j.home`, `neo4j.password`) — change them in one place.

---

## Build

```bash
# from repo root — builds both modules
mvn clean package            # compile + test + produce fat JAR
mvn test                     # all tests

# single module
mvn test -pl plugin
mvn test -pl dsl

# single test class
mvn test -pl plugin -Dtest=TopologicalSorterTest     # pure Java, fast
mvn test -pl plugin -Dtest=CausalProceduresTest      # embedded Neo4j, ~2 min
mvn test -pl dsl    -Dtest=CausalDSLTest
```

---

## Using the DSL

For the full language specification see **[`dsl/README.md`](dsl/README.md)**.
Quick start:

### Programmatic (in-memory — no Neo4j needed)

```java
import com.example.causal.dsl.CausalDSL;

var dsl = new CausalDSL();
dsl.executeScript("""
    DEFINE CAUSAL DAG Retention {
        Age        -> Treatment
        Age        -> Retention30
        Treatment  -> Retention30
    }
    IDENTIFY EFFECT OF Treatment ON Retention30 RETURN ADJUSTMENT SETS
""").forEach(System.out::println);
```

With linear-Gaussian parameters, interventions and counterfactuals return numbers:

```java
dsl.executeScript("""
    DEFINE MODEL Chain {
        VARIABLE X MEAN 0 NOISE 1
        VARIABLE M MEAN 0 NOISE 1
        VARIABLE Y MEAN 0 NOISE 1
        DAG { X -> M WEIGHT 2   M -> Y WEIGHT 3 }
    }
    INTERVENE ON X = 4 RETURN EXPECTATION OF Y          // E[Y | do(X=4)] = 24
    COUNTERFACTUAL GIVEN X = 1, Y = 10 WHAT WOULD Y HAVE BEEN IF X = 3   // Y_cf = 22
""").forEach(System.out::println);
```

### Programmatic (against a running Neo4j server)

```java
try (Driver driver = GraphDatabase.driver("bolt://localhost:7687",
         AuthTokens.basic("neo4j", "neo4j"))) {
    var registry = new ModelRegistry();
    var dsl = new CausalDSL(new Neo4jQueryExecutor(driver, registry), registry);
    dsl.executeScript(Files.readString(Path.of("dev/example.causal")));
}
```

### Interactive REPL

```bash
# executes each statement (blank line submits, Ctrl-D exits)
mvn exec:java -pl dsl -Dexec.mainClass=com.example.causal.dsl.CausalDSLRepl

# or run a script
mvn exec:java -pl dsl -Dexec.mainClass=com.example.causal.dsl.CausalDSLRepl \
    -Dexec.args="dev/example.causal"
```

### Dry-run REPL (shows generated Cypher, does not execute)

Useful when debugging the translation layer or previewing what a script will
send to Neo4j.

```bash
mvn exec:java -pl dsl -Dexec.mainClass=com.example.causal.dsl.CypherDryRunRepl \
    -Dexec.args="dev/example.causal"
```

Sample output:

```
────────────────────────────────────────────────────────────────────────
 AST: CausesOfStmt
      CausesOfStmt[var=Retention30]
────────────────────────────────────────────────────────────────────────

 [1/1] READ (yield: variableId)
   Cypher:
     MATCH (anchor:Variable {id: $var})
     CALL causal.parents([anchor]) YIELD variable
     RETURN variable.id AS variableId ORDER BY variableId
   Params: var = Retention30
```

---

## Plugin: local dev workflow

Day-to-day loop when working on the Neo4j procedures.

### One-time setup

Allow the plugin procedures in `neo4j.conf`:

```
dbms.security.procedures.unrestricted=causal.*
```

If your Neo4j password is not the default, set it once:
using bash

```bash
export NEO4J_PASSWORD=yourpassword
```

or using `mise set` command, or by creating `mise.local.toml` file directly (mise-en-place CLI tool)[https://mise.jdx.dev/]
```bash
mise set --env local NEO4J_PASSWORD=yourpassword
```


### Build, deploy, restart

```bash
mvn package -pl plugin -P deploy -DskipTests
```

This single command compiles the plugin, stops any running Neo4j, copies the
JAR into the `plugins/` directory, and restarts Neo4j in daemon mode.

### Seed the COVID-19 DAG

```bash
./dev/seed.sh
```

Waits until Neo4j is ready, then loads 25 variables and 78 causal edges from
`dev/seed-dag.cypher`. Safe to re-run — it clears existing data first.

### Query from Neo4j Browser or cypher-shell

```cypher
// Compile the in-memory snapshot
CALL causal.compile() YIELD variableCount RETURN variableCount;

// Structural queries (pass :Variable nodes; procedures yield nodes)
MATCH (cases:Variable {id: 'reported_cases'})
CALL causal.ancestors([cases]) YIELD variable RETURN variable;

MATCH (exposure:Variable {id: 'exposure_sarscov2'})
CALL causal.parents([exposure]) YIELD variable RETURN variable;

RETURN causal.hasDirectedPath('interventions', 'reported_cases') AS isCause;

// Adjustment set candidate (backdoor)
MATCH (t:Variable {id: 'interventions'}), (y:Variable {id: 'reported_cases'})
CALL causal.adjustmentCandidate(t, y)
    YIELD adjustmentSetExists, candidateSets, explanation
RETURN adjustmentSetExists, candidateSets;

// Path queries (used by the DSL)
MATCH (t:Variable {id: 'interventions'}), (y:Variable {id: 'reported_cases'})
CALL causal.mediators(t, y) YIELD variable RETURN variable;
CALL causal.confounders(t, y) YIELD variable RETURN variable;
CALL causal.backdoorVariables(t, y) YIELD variable RETURN variable;
```

### Deploy to a different Neo4j server

```bash
cp plugin/target/causal4j-plugin-1.0-SNAPSHOT.jar $NEO4J_HOME/plugins/
# restart Neo4j, then verify:
```

```cypher
SHOW PROCEDURES YIELD name WHERE name STARTS WITH 'causal' RETURN name;
```

---

## Neo4j graph schema

```
(:Variable {id, name, observed, latent}) -[:CAUSES {active}]-> (:Variable)
```

Only `:CAUSES` relationships with `active = true` are loaded into the compiled
snapshot. Property defaults when absent: `name` → `id`, `observed` → `true`,
`latent` → `false`, `active` → `true`. The `id` property is the canonical
key used by `VariableRef`.

---

## Architecture

### Plugin side

- **Procedures are thin.** `CausalProcedures` / `CausalFunctions` do no graph
  logic — they resolve variable ids, call the runtime, and map to DTOs.
- **`RuntimeRegistry`** holds the process-scoped `CausalGraphRuntime`
  singleton. Neo4j instantiates procedure classes per-call, so the runtime has
  to live outside them.
- **DTOs** (`com.example.causal.dto`) must be plain classes with
  `public final` fields, not records — Neo4j's reflection-based row mapping
  does not support records.
- **`@Context Transaction tx`** is injected by Neo4j per-call. Pass it to
  `GraphCompileOptions` when implementing `causal.compile()`; other procedures
  read the cached snapshot.
- **Transaction listener** (`com.example.causal.lifecycle`) — `CausalGraphExtensionFactory`
  is loaded via `META-INF/services/org.neo4j.kernel.extension.ExtensionFactory`
  on database startup and registers a `CausalGraphChangeListener` that observes
  every transaction. Its `beforeCommit` builds a `ChangeBatch`; its `afterCommit`
  applies that batch to `RuntimeRegistry`. Today it falls back to a full
  `invalidate()`; Student A's Tier 1 work turns it into incremental maintenance
  of the snapshot's adjacency, topo layer, and reachability bitsets.

### DSL side

- **Parser → AST → Executor.** ANTLR4 builds a parse tree; `AstBuilder`
  (a visitor) converts it to a sealed `Statement` hierarchy. An
  `InMemoryQueryExecutor` evaluates against a pure-Java `CausalModel`; a
  `Neo4jQueryExecutor` translates via `CypherTranslator` and runs through the
  Bolt driver. Same AST, pluggable backend.
- **In-memory engines = reference oracle.** `StructuralEvaluator`,
  `LinearGaussianSEM`, `CounterfactualEvaluator`, and `EstimateEvaluator` compute
  the answers in pure Java. They are self-contained *and* serve as the correctness
  oracle for the equivalent plugin algorithms (see `plugin/EXERCISES.md`).
- **Intervention worlds.** `INTERVENE` derives a graph via surgery
  (`CausalModel.intervene`), registers it in `ModelRegistry` under an ID-safe name,
  and makes it the active graph; `USE MODEL` / `RESET` switch the active world.
  Numeric answers come from the linear-Gaussian engine when parameters are present;
  otherwise the answer is structural.
- **DSL-only metadata** (unit, role, `OBSERVED AS` binding) is held in the
  DSL's `ModelRegistry` — the plugin's schema does not carry it.
- **`CypherDryRunRepl`** exists specifically so you can inspect what the
  translator emits without running it.
- **Cross-module contract.** Procedure names and `YIELD` column names are shared
  constants in `com.example.causal.api` (`ProcedureNames`, `ResultColumns`), so the
  plugin's `@Procedure` annotations and the DSL's `CypherTranslator` cannot drift;
  `ProcedureContractTest` enforces it.

---

## Structural logging

Every `CALL causal.compile()` dumps the full adjacency lists + topological
order via `CompiledCausalGraph.logStructure()`. In the embedded test harness
this goes to stdout; on a real server it goes to `logs/neo4j.log`. Add a
section to `logStructure()` whenever you introduce a new cached structure.

---

## Testing notes

When calling procedures in test code via `tx.execute()`, **consume the result
before closing** — lazy result streams can silently skip execution:

```java
Result r = tx.execute("CALL causal.compile()");
while (r.hasNext()) r.next();   // force execution
tx.commit();
```

---

## Status & remaining work

**DSL — feature-complete** (within the database boundary). Every statement is
implemented and tested: schema (`DEFINE …`, incl. linear-Gaussian parameters),
structural queries, `IDENTIFY`, `INTERVENE` (+ `COMPARE TO`, numeric on a
parameterized model), `USE MODEL` / `RESET` / `SHOW MODELS`, `COUNTERFACTUAL`, and
`ESTIMATE` (SEM-implied). The in-memory engines are the reference oracle.

**Plugin — the algorithm work is a student exercise.** The structural traversal
(`parents/children/ancestors/descendants/hasDirectedPath`) and `PathAnalyzer`
(`mediators/colliders/confounders/backdoorVariables`) are **implemented**. The
identification and quantitative algorithms are stubbed placeholders for the
student to fill in — the authoritative brief is **[`plugin/EXERCISES.md`](plugin/EXERCISES.md)**:

| Tier | Plugin work | Unblocks |
|---|---|---|
| 1 | `isDSeparated` (Bayes Ball); `candidateAdjustmentSet` (backdoor criterion) — *runtime method bodies only; procedures/DTOs already wired* | `causal.dsep`, `causal.adjustmentCandidate`, the disabled `IDENTIFY` E2E test |
| 1 | `CausalGraphChangeListener` — incremental snapshot updates + cycle validation (today: full `invalidate()`) | live-graph maintenance |
| 2 | `LinearGaussianInference` + parameter schema/loader + a new `api`/DTO/procedure contract; `CounterfactualInference` | numeric `INTERVENE` / `COUNTERFACTUAL` / `ESTIMATE` against a live Neo4j |
| 2 | Performance: dense `int[][]` layout + bitset `ReachabilityIndex` + cached index; `CausalFunctions.topoOrder` | speed (behaviour-preserving) |

Each plugin algorithm has a reference oracle in the DSL (`CausalModel`,
`LinearGaussianSEM`, `CounterfactualEvaluator`) — implement to match its numbers.

### Explicitly out of scope (external ML)

Estimation *from data* — executing `OBSERVED AS` bindings and fitting an estimator
(regression / IPW / AIPW) — is **not** built here. This is a DB extension, not an
ML system; a future external ETL performs data-driven estimation and inserts
results back through this DSL. Parameters are supplied via `WEIGHT` / `MEAN` / `NOISE`.

### Not in scope for v1

- Materialising derived relationships as Neo4j edges (`:ANCESTOR_OF`,
  `:DESCENDANT_OF`, `:IN_TOPO_LAYER`, `:PairQuery` nodes). The compiled
  snapshot is the single source of truth; duplicating it in the graph
  doubles the invalidation burden. Expose derived facts through `causal.*`
  procedures instead.
- Districts / c-components (ADMG / latent projection), spouse sets,
  path-pattern signature caches. Only needed if d-separation is implemented
  via the moral graph or the DSL grows an ADMG layer.
