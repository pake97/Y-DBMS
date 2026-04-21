# Causal4j — Neo4j Causal Graph Plugin

A Neo4j procedure/function plugin that compiles a causal DAG stored in Neo4j
into an optimised in-memory representation and exposes structural graph queries
(parents, ancestors, d-separation, adjustment sets) as Cypher-callable procedures.

This project is the **backend runtime** for a future causal-analysis DSL.
The DSL lives in a separate project and depends only on the `CausalGraphRuntime`
interface defined here.

---

## What this project is

| Layer | Package | Owner |
|---|---|---|
| API contract | `com.example.causal.api` | Frozen — both sides depend on it |
| Runtime / data structures | `com.example.causal.runtime` | **Student A** |
| Neo4j procedures/functions | `com.example.causal.procedures` | Mostly done — thin wrappers |
| DTOs | `com.example.causal.dto` | Done |
| Tests | `src/test/...` | Both |

---

## Neo4j graph schema

```
(:Variable {id: String, name: String, observed: Boolean, latent: Boolean})
  -[:CAUSES {active: Boolean}]->
(:Variable {...})
```

Only `CAUSES` relationships with `active = true` are included in the compiled graph.
Property defaults: `name` → `id`, `observed` → `true`, `latent` → `false`, `active` → `true`.

---

## Build

```bash
mvn clean package          # compile + test + produce fat JAR
mvn test                   # run all tests (unit + integration)
mvn test -Dtest=TopologicalSorterTest   # single unit test class
mvn test -Dtest=CausalProceduresTest    # single integration test class
```

> **Java 17** is required (same requirement as Neo4j 2025.x).

---

## Deploy to Neo4j

```bash
cp target/causal4j-plugin-1.0-SNAPSHOT.jar $NEO4J_HOME/plugins/

# neo4j.conf — allow procedures (adjust for your security policy):
dbms.security.procedures.unrestricted=causal.*
dbms.security.procedures.allowlist=causal.*
```

Restart Neo4j. Verify the plugin loaded:

```cypher
SHOW PROCEDURES YIELD name WHERE name STARTS WITH 'causal' RETURN name;
```

---

## Sample Cypher session

```cypher
// 1. Compile the in-memory snapshot from Neo4j data
CALL causal.compile() YIELD variableCount, compiledAt
RETURN variableCount, compiledAt;

// 2. Structural queries
CALL causal.parents(['Y']) YIELD variableId RETURN variableId;
CALL causal.ancestors(['Y']) YIELD variableId RETURN variableId;
CALL causal.descendants(['X']) YIELD variableId RETURN variableId;
CALL causal.children(['X']) YIELD variableId RETURN variableId;

// 3. Reachability (scalar function — usable in expressions)
RETURN causal.hasDirectedPath('X', 'Y') AS isCause;

// 4. D-separation (stub — implement in DefaultCausalGraphRuntime)
CALL causal.dsep(['X'], ['Y'], ['Z']) YIELD separated, explanation RETURN separated;

// 5. Adjustment sets (stub — implement in DefaultCausalGraphRuntime)
CALL causal.adjustmentCandidate('treatment', 'outcome')
  YIELD adjustmentSetExists, candidateSets, explanation
RETURN adjustmentSetExists, candidateSets;

// 6. Inspect snapshot without recompiling
CALL causal.inspectSnapshot() YIELD variableCount, compiledAt RETURN variableCount;

// 7. Invalidate (call after mutating the graph)
CALL causal.invalidate();
```

---

## IntelliJ IDEA setup

1. **File → Open** → select the `Causal4j` directory (it is already a Maven project).
2. IntelliJ will auto-import the `pom.xml`. Accept "Load Maven Project" if prompted.
3. Set **Project SDK** to Java 17 (`File → Project Structure → SDK`).
4. Run tests: right-click `src/test/java` → **Run All Tests**, or use the gutter icons.

**Run/Debug a procedure locally:**
The `CausalProceduresTest` integration test uses an embedded Neo4j harness,
so you can set breakpoints inside `CausalProcedures`, `DefaultCausalGraphRuntime`,
or any runtime class and run the test in debug mode — no external Neo4j needed.

---

## Student A: where to start

You own everything under `com.example.causal.runtime`. Start here:

### Phase 1 — Get the tests green (already done for you)
The current implementation uses `HashMap`/`LinkedHashSet` and BFS. It is correct
but not optimised. All 15 tests pass out of the box.

### Phase 2 — Dense integer layout (`CompiledCausalGraph`)
Replace the `Map<String, ...>` adjacency structures with:
```java
Map<String, Integer>  nameToIndex;   // O(1) variable lookup
Variable[]            indexToVar;
int[][]               forwardAdj;    // children of node i
int[][]               reverseAdj;    // parents of node i
int[]                 topoOrder;     // variable indices, sources first
```
See the TODO comments in `CompiledCausalGraph.java`.

### Phase 3 — Bitset ancestor/descendant cache (`ReachabilityIndex`)
Pre-compute `long[][] ancestorBits` in reverse topological order:
```
for each node i in reverse-topo order:
    ancestorBits[i] = union of (ancestorBits[p] | setBit(p)) for each parent p of i
```
Then `ancestors(i)` is O(n/64) instead of O(V+E). See TODO in `ReachabilityIndex.java`.

### Phase 4 — D-separation (`DefaultCausalGraphRuntime.isDSeparated`)
Implement the Bayes Ball algorithm or the moral-graph criterion.
The method signature and stub are in `DefaultCausalGraphRuntime`.

### Phase 5 — Adjustment sets (`DefaultCausalGraphRuntime.candidateAdjustmentSet`)
Implement the backdoor criterion. Requires d-separation (Phase 4).

---

## DSL integration contract

The external DSL/planner project should:

1. **Depend only on `com.example.causal.api.*`** — never on `runtime.*` or `procedures.*`.
2. **Obtain a `CausalGraphRuntime`** instance via whatever injection mechanism
   your planner uses (e.g. a factory, a constructor parameter, or a service loader).
   Do not reference `RuntimeRegistry` from the DSL.
3. **Call `compile(options)` once** per session (or after graph mutations).
4. **Issue structural queries** using the interface methods:

```java
CausalGraphRuntime rt = ...; // injected

Optional<VariableRef> x  = rt.resolveVariable("X");
Set<VariableRef>   anc   = rt.ancestors(Set.of(x.get()));
DSeparationResult  dsep  = rt.isDSeparated(setX, setY, setZ);
boolean            path  = rt.hasDirectedPath(x.get(), y.get());
AdjustmentCandidateResult adj = rt.candidateAdjustmentSet(treatment, outcome);
```

The types `VariableRef`, `GraphSnapshot`, `DSeparationResult`, and
`AdjustmentCandidateResult` are all in `com.example.causal.api` and are stable.

---

## Known TODOs

| Location | Description |
|---|---|
| `CompiledCausalGraph` | Replace Map-based adjacency with dense `int[][]` layout |
| `ReachabilityIndex` | Implement bitset ancestor/descendant pre-computation |
| `DefaultCausalGraphRuntime.isDSeparated` | Implement Bayes Ball algorithm |
| `DefaultCausalGraphRuntime.candidateAdjustmentSet` | Implement backdoor criterion |
| `CausalFunctions.topoOrder` | Wire up `CompiledCausalGraph.topoIndex()` through the API |
| `CausalGraphRuntime` | Consider adding `topoIndex(VariableRef)` to the interface |
| `pom.xml` | Pin `maven-compiler-plugin` version to silence the Maven warning |
