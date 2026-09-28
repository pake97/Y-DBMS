# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A monorepo containing two Maven modules:

- **`plugin/`** — Neo4j procedures/functions plugin (JAR dropped into `plugins/`). Compiles a causal DAG stored in Neo4j into an in-memory representation and exposes structural queries (ancestors, descendants, d-separation, adjustment sets) as Cypher-callable procedures. Backend runtime for a future causal-analysis DSL.
- **`dsl/`** — Future DSL/parser/planner. Depends only on `com.example.causal.api.*` from the plugin module.

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

**Module-scoped tests and `-am`**: `dsl` depends on `causal4j-plugin`. Running
`mvn test -pl dsl` *without* `-am` resolves a possibly-stale `causal4j-plugin`
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
- **DSL layer sees only `com.example.causal.api.*`** — never runtime internals.
- **`RuntimeRegistry`** is the process-scoped singleton holding `CausalGraphRuntime`.
  Neo4j instantiates procedure classes per-call, so the runtime must live outside them.
- **DTOs** (`com.example.causal.dto`) must use plain classes with `public final` fields
  (not Java records) so Neo4j's reflection-based row mapping works.

## Neo4j graph schema

```
(:Variable {id, name, observed, latent}) -[:CAUSES {active}]-> (:Variable)
```

Only `active=true` CAUSES relationships are loaded. The `id` property is the
canonical key used by `VariableRef`.

## Version-sensitive notes

- `neo4j.version=2025.05.0` in the root `pom.xml` — bump this single property to upgrade.
- `neo4j.home` and `neo4j.password` are also in the root `pom.xml` — change them there if your local Neo4j moves.
- Procedure annotations (`@Procedure`, `@UserFunction`, `@Context`, `@Name`) come
  from the `org.neo4j:neo4j` provided dependency — no separate annotation JAR needed.
- `@Context Transaction tx` is injected by Neo4j per-call; pass it to
  `GraphCompileOptions` for `causal.compile()` only. Other procedures use the cached snapshot.

## Structural logging (Student A)

Every call to `CALL causal.compile()` automatically logs the full adjacency lists
and topological order via `CompiledCausalGraph.logStructure()`. Read this output
to verify your data structures are correct. In the embedded test harness it goes
to stdout; on a real Neo4j server it goes to `logs/neo4j.log`.

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
