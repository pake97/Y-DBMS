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

## Local dev workflow

This is the day-to-day loop when working on the plugin.

### One-time setup

Make sure `neo4j.conf` allows the plugin procedures:
```
dbms.security.procedures.unrestricted=causal.*
```

If your Neo4j password is not the default `neo4j`, set it once:
```bash
export NEO4J_PASSWORD=yourpassword
```

### Step 1 — Build, deploy, restart

```bash
mvn package -pl plugin -P deploy -DskipTests
```

This single command:
1. Compiles the code and produces the fat JAR
2. Stops any running Neo4j process
3. Copies the JAR into `neo4j-community-2025.05.0/plugins/`
4. Starts Neo4j in daemon mode

The Neo4j home path and password are configured in **one place**: the `<properties>` block of the root `pom.xml`.

### Step 2 — Seed the COVID-19 DAG

```bash
./dev/seed.sh
```

Waits until Neo4j is ready, then loads 25 variables and 76 causal edges from
`dev/seed-dag.cypher`. Safe to re-run — it clears existing data first.

### Step 3 — Compile the graph and query it

In Neo4j Browser (`http://localhost:7474`) or `cypher-shell`:

```cypher
// Compile the in-memory snapshot
CALL causal.compile() YIELD variableCount, compiledAt
RETURN variableCount, compiledAt;
// → 25 variables

// What are the ancestors of reported cases?
CALL causal.ancestors(['reported_cases']) YIELD variableId RETURN variableId;

// Is there a directed path from interventions to reported cases?
RETURN causal.hasDirectedPath('interventions', 'reported_cases') AS isCause;

// What directly causes exposure to SARS-CoV-2?
CALL causal.parents(['exposure_sarscov2']) YIELD variableId RETURN variableId;
```

### Variable ids in the seeded DAG

| id | Name |
|---|---|
| `interventions` | Interventions |
| `covid19_burden` | COVID-19 burden |
| `healthcare_utilization` | Health care utilization |
| `searches_corona` | Searches corona |
| `gender` | Gender |
| `age` | Age |
| `foreign_citizens` | Foreign citizens |
| `socioeconomic_status` | Socio-economic status |
| `turnout` | Turnout |
| `rightwing_votes` | Right-wing populist party votes |
| `trust_society` | Trust in society |
| `population_density` | Population density |
| `nursing_homes` | Nursing homes |
| `mobility` | Mobility |
| `temperature` | Temperature |
| `humidity` | Humidity |
| `wind` | Wind |
| `rainfall` | Rainfall |
| `tracing_capacity` | Tracing capacity |
| `exposure_sarscov2` | Exposure to SARS-COV-2 *(latent)* |
| `herd_immunity` | Herd immunity *(latent)* |
| `access_tests` | Access to tests |
| `susceptibility` | Susceptibility *(latent)* |
| `onset_covid19` | Onset of COVID-19 *(latent)* |
| `reported_cases` | Reported new cases COVID-19 |

### Deploy to a real Neo4j server

```bash
cp plugin/target/causal4j-plugin-1.0-SNAPSHOT.jar $NEO4J_HOME/plugins/
# restart Neo4j manually
```

Verify the plugin loaded:

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
