# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A Neo4j **procedures/functions plugin** (JAR dropped into `plugins/`) that compiles
a causal DAG stored in Neo4j into an in-memory representation and exposes structural
queries (ancestors, descendants, d-separation, adjustment sets) as Cypher-callable
procedures. It is the backend runtime for a future causal-analysis DSL.

## Commands

```bash
mvn clean package                          # compile + test + fat JAR
mvn test                                   # all tests (unit + integration)
mvn test -Dtest=TopologicalSorterTest      # pure Java unit tests (fast, no Neo4j)
mvn test -Dtest=CausalProceduresTest       # integration tests (~2 min, embedded Neo4j)
```

Java 17 required (same as Neo4j 2025.x).

## Project structure

```
src/main/java/com/example/causal/
  api/           ← stable interface layer (both DSL and procedures depend on this)
  runtime/       ← Student A's domain: data structures, algorithms, compiled graph
    model/       ← Variable record (loaded from Neo4j)
    compile/     ← GraphLoader, CompiledCausalGraph
    algorithms/  ← TopologicalSorter, ReachabilityIndex
  procedures/    ← thin Neo4j @Procedure / @UserFunction wrappers
  dto/           ← public-field classes for Cypher output rows
src/test/java/com/example/causal/
  runtime/       ← pure unit tests (TopologicalSorterTest)
  procedures/    ← integration tests using neo4j-harness (CausalProceduresTest)
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

- `neo4j.version=2025.05.0` in `pom.xml` — bump this single property to upgrade.
- Procedure annotations (`@Procedure`, `@UserFunction`, `@Context`, `@Name`) come
  from the `org.neo4j:neo4j` provided dependency — no separate annotation JAR needed.
- `@Context Transaction tx` is injected by Neo4j per-call; pass it to
  `GraphCompileOptions` for `causal.compile()` only. Other procedures use the cached snapshot.

## Testing note

When calling procedures in test code via `tx.execute()`, always **consume the result**
before closing — lazy result streams can silently skip procedure execution:
```java
Result r = tx.execute("CALL causal.compile()");
while (r.hasNext()) r.next();  // force execution
tx.commit();
```
