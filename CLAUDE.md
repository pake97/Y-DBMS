# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

Y-DBMS (formerly Causal4j): a monorepo with three Maven modules (`plugin`, `dsl`, `bench`;
artifacts `ydbms-plugin`, `ydbms-dsl`, `ydbms-bench`) and `artifact/`, the paper's
reproduction instructions. The Java packages are still `com.example.causal.*` and the bench
Python package is still `causal4j_bench`.

- **`plugin/`** — Neo4j procedures/functions plugin (JAR dropped into `plugins/`). Compiles a causal DAG stored in Neo4j into an in-memory representation and exposes structural queries (ancestors, descendants, d-separation, adjustment sets) as Cypher-callable procedures. Backend runtime for a future causal-analysis DSL.
- **`dsl/`** — DSL/parser/planner with two backends: Neo4j (calls the plugin's procedures) and in-memory. See the DSL rules under *Key architectural rules*.

The current work plan (work packages, owners, deadlines) is in `CODE-PLAN.md`.

## Commands

```bash
# From repo root — builds both modules
mvn clean package                          # compile + test + fat JAR
mvn test                                   # all tests (unit + integration)

# Plugin module only
mvn test -pl plugin                        # all plugin tests
mvn test -pl plugin -Dtest=TopologicalSorterTest   # pure Java unit tests (fast, no Neo4j)
mvn test -pl plugin -Dtest=CausalProceduresTest    # integration tests (~2 min, embedded Neo4j)

# DSL module — note the -am
mvn test -pl dsl -am                       # ALWAYS pass -am for module-scoped dsl tests
mvn test -pl dsl -Dtest=DslPluginE2ETest -am -Dsurefire.failIfNoSpecifiedTests=false

# Local dev workflow — build, deploy to local Neo4j, restart
mvn package -pl plugin -P deploy -DskipTests
./dev/seed.sh                              # load the COVID-19 DAG after Neo4j starts
```

**Module-scoped tests and `-am`**: `dsl` depends on `ydbms-plugin`. Running
`mvn test -pl dsl` *without* `-am` resolves a possibly-stale `ydbms-plugin`
from your local `~/.m2` instead of rebuilding it from source — which produces
confusing failures (e.g. "no procedure `causal.mediators`" or "expected
List<String> but was List<Node>") when source has moved ahead of the installed
JAR. Always use `-am` (or `mvn install` the plugin first). A full `mvn test`
from the repo root builds the reactor in order and is unaffected.

Java 17 required (same as Neo4j 2025.x).

## Project structure

```
plugin/src/main/java/com/example/causal/
  api/           ← stable interface layer (both DSL and procedures depend on this)
  runtime/       ← Student A's domain: data structures, algorithms, compiled graph
    model/       ← Variable record (loaded from Neo4j)
    compile/     ← GraphLoader, CompiledCausalGraph
    algorithms/  ← TopologicalSorter, ReachabilityIndex
  procedures/    ← thin Neo4j @Procedure / @UserFunction wrappers
  dto/           ← public-field classes for Cypher output rows
plugin/src/test/java/com/example/causal/
  runtime/       ← pure unit tests (TopologicalSorterTest)
  procedures/    ← integration tests using neo4j-harness (CausalProceduresTest)
dev/
  seed-dag.cypher  ← COVID-19 causal DAG (25 variables, 78 edges)
  seed.sh          ← loads seed-dag.cypher into local Neo4j via cypher-shell
```

## Key architectural rules

- **Procedures are thin**: no graph logic in `CausalProcedures` or `CausalFunctions`.
- **DSL, Neo4j backend: sees only `com.example.causal.api.*`** — never runtime internals;
  it reaches the runtime through the procedures.
- **DSL, in-memory backend: uses the plugin's runtime algorithms** (`runtime.algorithms.Adjustment`,
  on a snapshot built with `GraphBuilder`) for IDENTIFY, so both backends give the same
  IDENTIFY answers (generalized adjustment criterion, latent variables excluded). Do not
  reimplement identification in the DSL.
- **`RuntimeRegistry`** is the process-scoped singleton holding `CausalGraphRuntime`.
  Neo4j instantiates procedure classes per-call, so the runtime must live outside them.
- **DTOs** (`com.example.causal.dto`) must use plain classes with `public final` fields
  (not Java records) so Neo4j's reflection-based row mapping works.

## Neo4j graph schema

```
(:Variable {id, name, observed, latent}) -[:CAUSES {active, weight}]-> (:Variable)
```

**Constraints:**
- The schema strictly enforces at most one `CAUSES` relationship between any pair of nodes (regardless of `active` status). The `CausalGraphChangeListener` will throw an error if a parallel edge is created.
- **Important:** To update an edge, use `MERGE (a)-[r:CAUSES]->(b) SET r.active = true, r.weight = 0.8`. Do NOT use inline properties with `MERGE (a)-[:CAUSES {active:true}]->(b)` as this will attempt to create a parallel edge and the transaction listener will reject it.
- A uniqueness constraint should be defined on the `:Variable(id)` property.

Only `active=true` CAUSES relationships are loaded. The `id` property is the
canonical key used by `VariableRef`.

## Version-sensitive notes

- `neo4j.version=2025.05.0` in the root `pom.xml` — bump this single property to upgrade.
- `neo4j.home` and `neo4j.password` are also in the root `pom.xml` — change them there if your local Neo4j moves.
- Procedure annotations (`@Procedure`, `@UserFunction`, `@Context`, `@Name`) come
  from the `org.neo4j:neo4j` provided dependency — no separate annotation JAR needed.
- `@Context Transaction tx` is injected by Neo4j per-call; pass it to
  `GraphCompileOptions` for `causal.compile()`. Other procedures and functions use the cached
  snapshot, and compile it with their own transaction when there is none (after an
  invalidation, including the listener's fallback when a patch declines).

## Structural logging (Student A)

`CALL causal.compile()` (and the on-demand compile of read procedures) dumps the
full adjacency lists, topological order, layers and closures via
`CompiledCausalGraph.logStructure()` only when FINE is enabled for the
`com.example.causal.runtime.compile.CompiledCausalGraph` logger; by default it
writes nothing (the dump is megabytes on large DAGs and used to be paid inside
every timed recompile). To read it, enable FINE for that logger, e.g. a
`logging.properties` with `com.example.causal.runtime.compile.CompiledCausalGraph.level = FINE`
and `java.util.logging.ConsoleHandler.level = FINE`. In the embedded test harness
it then goes to stderr; on a real Neo4j server to `logs/neo4j.log`.

When you add new structures (bitset cache, index maps, etc.), add a section
to `logStructure()` so you can inspect them in the same place.

## Testing note

When calling procedures in test code via `tx.execute()`, always **consume the result**
before closing — lazy result streams can silently skip procedure execution:
```java
Result r = tx.execute("CALL causal.compile()");
while (r.hasNext()) r.next();  // force execution
tx.commit();
```
