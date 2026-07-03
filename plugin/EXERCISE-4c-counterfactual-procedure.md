# Exercise 4c (procedure) — expose `causal.counterfactual`

**Owner:** Student A (plugin). **Status:** engine done, procedure missing.
**Prereq:** #4 (linear-Gaussian SEM) — done. This is thin wiring on top of it.

## Why

`CounterfactualInference` already exists and is unit-tested, but there is **no
`@Procedure`** for it — so the DSL cannot reach counterfactuals over Neo4j the way
it now reaches interventions via `causal.linearGaussianInference`. This exercise
adds that missing contract. It is the same shape as the SEM procedure you already
shipped; no new algorithm.

The DSL side (`CypherTranslator` / `Neo4jQueryExecutor`) will call this to answer
`COUNTERFACTUAL … WHAT WOULD Y HAVE BEEN IF X = x*`. Until it exists, the DSL has
to compute counterfactuals itself from `totalEffect`, which duplicates logic that
belongs in the plugin.

## The contract to add (mirror `causal.linearGaussianInference`)

1. **`ProcedureNames`** — add:
   ```java
   public static final String COUNTERFACTUAL = "causal.counterfactual";
   ```

2. **DTO** `com.example.causal.dto.CounterfactualRecord` — plain class, `public final`
   fields (not a record — Neo4j maps by reflection), fields = YIELD columns:
   ```java
   public final Double factual;         // Y_factual (echoed back)
   public final Double counterfactual;  // Y_cf
   public final Double difference;      // Y_cf − Y_factual
   ```

3. **`ResultColumns`** — add the three column-name constants
   (`FACTUAL`, `COUNTERFACTUAL`, `DIFFERENCE`) matching those field names.
   `ProcedureContractTest` reflects over the DTO and will fail if a field has no
   matching constant — add `CounterfactualRecord.class` to the two DTO arrays in
   that test.

4. **`CausalGraphRuntime`** — add the api method:
   ```java
   CounterfactualRecord counterfactual(VariableRef interventionVar,
                                       double counterfactualValue,
                                       VariableRef outcome,
                                       Map<VariableRef, Double> factualValues);
   ```

5. **`DefaultCausalGraphRuntime`** — implement it: `requireSnapshot()`, guard
   `isParameterized()` (same as the SEM method), translate the `VariableRef` map to
   `Map<String,Double>` by `.id()`, then delegate to the existing engine:
   ```java
   double yCf = new CounterfactualInference(g)
           .counterfactual(interventionVar.id(), counterfactualValue,
                           outcome.id(), factualById);
   double yFactual = factualById.get(outcome.id());
   return new CounterfactualRecord(yFactual, yCf, yCf - yFactual);
   ```

6. **`CausalProcedures`** — thin `@Procedure`, exactly like `linearGaussianInference`
   (note the same Cypher-map-key constraint — `factualValues` keys are String ids,
   resolved to `VariableRef` in the procedure body):
   ```java
   @Procedure(name = ProcedureNames.COUNTERFACTUAL, mode = Mode.READ)
   @Description("Linear-Gaussian unit counterfactual: what would `outcome` have been "
       + "under do(interventionVar = counterfactualValue), given the factual values. "
       + "Use Variable node IDs (without quotes) as keys in the factualValues map.")
   public Stream<CounterfactualRecord> counterfactual(
           @Name("interventionVar") Node interventionVar,
           @Name("counterfactualValue") double counterfactualValue,
           @Name("outcome") Node outcome,
           @Name(value = "factualValues", defaultValue = "{}") Map<String, Double> factualValues) { … }
   ```

## Semantics (already in `CounterfactualInference`, do not re-derive)

Linear-Gaussian unit counterfactual (Pearl abduction→action→prediction, collapsed):
```
Y_cf = Y_factual + totalEffect(X→Y) · (x* − X_factual)
```
`factualValues` **must** contain both `interventionVar` and `outcome`; the engine
throws `IllegalArgumentException` otherwise — surface that as-is.

## Definition of done

- Unit test reproducing the oracle number: chain (β = 2, 3),
  `GIVEN X=1, Y=10, IF X=3` ⇒ `Y_cf = 10 + 6·(3−1) = 22`
  (oracle: `dsl/.../exec/CounterfactualEvaluator.java`).
- An embedded-Neo4j test that calls `CALL causal.counterfactual(...)` on a
  parameterized graph and gets `22` / `difference = 12` back.
- `ProcedureContractTest` stays green (constants ↔ DTO fields ↔ registered names).

## What the DSL will do once this lands

`Neo4jQueryExecutor` will translate `COUNTERFACTUAL … IF X = x*` to
`CALL causal.counterfactual(x, x*, y, $given)` for the persisted base graph, and
cross-check the returned `counterfactual` against the in-memory oracle
(`CounterfactualEvaluator`) — same pattern as `QuantitativeLayerE2ETest` does for
`INTERVENE`. Ping me (DSL side) when the procedure name + columns are final so I
can wire it without a second round-trip.
