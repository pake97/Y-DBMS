# Causal4j

A Neo4j-backed toolkit for causal inference. Two Maven modules:

| Module | What it is |
|---|---|
| [`plugin/`](plugin/) | Neo4j procedures/functions. Compiles a causal DAG stored in Neo4j into an in-memory representation and exposes structural queries (`causal.ancestors`, `causal.adjustmentCandidate`, …) as Cypher-callable procedures. |
| [`dsl/`](dsl/) | A small causal-inference DSL (`DEFINE CAUSAL DAG`, `IDENTIFY EFFECT OF`, …) that either runs in-process against an in-memory model or translates to Cypher calls against the plugin. Full language reference: **[`dsl/README.md`](dsl/README.md)**. |

The DSL depends only on the plugin's `com.example.causal.api.*` package. Everything under `runtime.*` / `procedures.*` is a plugin implementation detail.

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
  seed-dag.cypher     ← COVID-19 DAG (25 vars, 76 edges)
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

Waits until Neo4j is ready, then loads 25 variables and 76 causal edges from
`dev/seed-dag.cypher`. Safe to re-run — it clears existing data first.

### Query from Neo4j Browser or cypher-shell

```cypher
// Compile the in-memory snapshot
CALL causal.compile() YIELD size RETURN size;

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
- **DSL-only metadata** (unit, role, `OBSERVED AS` binding) is held in the
  DSL's `ModelRegistry` — the plugin's schema does not carry it.
- **`CypherDryRunRepl`** exists specifically so you can inspect what the
  translator emits without running it.

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

## Open work

Structured as tiers: **Tier 1 is required** for the plugin to answer every
DSL query end-to-end; Tier 2 is performance; Tier 3 is ergonomics / optional
caches.

### Tier 1 — required for feature completeness

| Location | Description |
|---|---|
| `plugin/runtime/algorithms/PathAnalyzer` | Implement `mediators`, `colliders`, `confounders`, `backdoorVariables`. Unblocks `MEDIATORS / COLLIDERS / CONFOUNDERS / BACKDOOR` in the DSL. Can be written today against the existing DFS-based `ReachabilityIndex` — no bitset work required. |
| `plugin/runtime/DefaultCausalGraphRuntime.isDSeparated` | Bayes Ball on the DAG directly. Unblocks `causal.dsep`. |
| `plugin/runtime/DefaultCausalGraphRuntime.candidateAdjustmentSet` | Backdoor criterion (depends on d-separation). Unblocks `causal.adjustmentCandidate` and the DSL's `IDENTIFY EFFECT OF`. |
| `plugin/lifecycle/CausalGraphChangeListener` | Extend `beforeCommit` + `afterCommit` so creating/deleting `:Variable` nodes or `:CAUSES` edges incrementally updates the compiled snapshot (dense adjacency, topo layer, ancestor/descendant bitsets) instead of the current fallback — a full `invalidate()`. Also: reject topology-breaking writes (e.g. cycle-creating edges) in `beforeCommit`. See TODOs in that file for the concrete hook points. |
| `dsl/exec/Neo4jQueryExecutor` | `ESTIMATE` needs `OBSERVED AS` execution + estimator dispatch (currently returns a placeholder). |

### Tier 2 — performance refactor

| Location | Description |
|---|---|
| `plugin/runtime/compile/CompiledCausalGraph` | Replace the `Map<String, Set<String>>` adjacency with a dense `int[][]` layout + `int[] topoOrder` + `int[] topoLayer`. Everything downstream (reachability, path queries) benefits. |
| `plugin/runtime/compile/CompiledCausalGraph` | Pre-compute `long[][] ancestorBits` / `descendantBits` in reverse topological order. Fill from the dense layout. |
| `plugin/runtime/algorithms/ReachabilityIndex` | Switch `ancestors` / `descendants` to O(n/64) bitset lookups reading from the snapshot. |
| `plugin/runtime/DefaultCausalGraphRuntime` | Cache a single `ReachabilityIndex` built at compile time, instead of allocating per query. |
| `plugin/runtime/compile/CompiledCausalGraph.topoIndex` | Replace the `List.indexOf` scan with an O(1) map lookup. |

### Tier 3 — optional ergonomics / lazy caches

| Location | Description |
|---|---|
| `plugin/runtime/compile/CompiledCausalGraph` | Lazy `markovBlanket(v)` = `parents ∪ children ∪ parents_of_children`. One-liner on top of Tier 2 bitsets. |
| `plugin/runtime/compile/CompiledCausalGraph` | Lazy `forbiddenAdjustmentSet(t, y)` = descendants of `t` on any causal path to `y`. Used by the DSL's `ESTIMATE` validator. |
| `plugin/runtime/DefaultCausalGraphRuntime` | Lazy pair-index cache: `Map<(treatment, outcome), PairFacts>` with `hasDirectedPath / hasBackdoorPath / mediatorsBitset / minimalAdjustmentSets`. Only add if profiling shows repeat queries on the same pairs. |
| `plugin/procedures/CausalFunctions.topoOrder` | Expose `CompiledCausalGraph.topoIndex` through `CausalGraphRuntime`. |
| `dsl/exec/Neo4jQueryExecutor` | `INTERVENE` / `COUNTERFACTUAL` — v1 parse-only stubs; implementations are a separate workstream. |

### Not in scope for v1

- Materialising derived relationships as Neo4j edges (`:ANCESTOR_OF`,
  `:DESCENDANT_OF`, `:IN_TOPO_LAYER`, `:PairQuery` nodes). The compiled
  snapshot is the single source of truth; duplicating it in the graph
  doubles the invalidation burden. Expose derived facts through `causal.*`
  procedures instead.
- Districts / c-components (ADMG / latent projection), spouse sets,
  path-pattern signature caches. Only needed if d-separation is implemented
  via the moral graph or the DSL grows an ADMG layer.
