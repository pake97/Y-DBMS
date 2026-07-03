package com.example.causal.dsl;

import com.example.causal.dsl.exec.InMemoryQueryExecutor;
import com.example.causal.dsl.exec.InterventionResult;
import com.example.causal.dsl.exec.Neo4jQueryExecutor;
import com.example.causal.dsl.model.ModelRegistry;
import com.example.causal.procedures.CausalFunctions;
import com.example.causal.procedures.CausalProcedures;
import com.example.causal.runtime.RuntimeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the <em>quantitative layer</em> across the DSL → plugin seam,
 * and the cross-check that proves the two linear-Gaussian engines agree.
 *
 * <p>The same parameterized model is defined twice: once through the
 * {@link Neo4jQueryExecutor} (which persists {@code intercept}/{@code noise}/{@code weight}
 * to Neo4j and answers {@code INTERVENE} numerics via the plugin's
 * {@code causal.linearGaussianInference} procedure), and once through the
 * {@link InMemoryQueryExecutor} (the reference oracle, {@code LinearGaussianSEM}).
 * Every numeric answer must equal both the oracle and the hand-computed constants
 * from {@code plugin/EXERCISES.md}.
 */
class QuantitativeLayerE2ETest {

    private static final String CHAIN = """
        DEFINE MODEL Chain {
          VARIABLE X MEAN 0 NOISE 1
          VARIABLE M MEAN 0 NOISE 1
          VARIABLE Y MEAN 0 NOISE 1
          DAG { X -> M WEIGHT 2   M -> Y WEIGHT 3 }
        }
        """;

    private static final String FORK = """
        DEFINE MODEL Fork {
          VARIABLE Z MEAN 0 NOISE 1
          VARIABLE X MEAN 0 NOISE 1
          VARIABLE Y MEAN 0 NOISE 1
          DAG { Z -> X WEIGHT 5   Z -> Y WEIGHT 7   X -> Y WEIGHT 2 }
        }
        """;

    @TempDir
    Path tempDir;

    private Neo4j neo4j;
    private Driver driver;

    @BeforeEach
    void setUp() {
        neo4j = Neo4jBuilders.newInProcessBuilder(tempDir)
                .withProcedure(CausalProcedures.class)
                .withFunction(CausalFunctions.class)
                .build();
        driver = GraphDatabase.driver(neo4j.boltURI());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.close();
        if (neo4j != null) neo4j.close();
        RuntimeRegistry.setInstance(null);   // process-scoped singleton — reset per test
    }

    /** A fresh Neo4j-backed DSL with {@code model} already defined and compiled. */
    private CausalDSL neoDsl(String model) {
        ModelRegistry registry = new ModelRegistry();
        CausalDSL dsl = new CausalDSL(new Neo4jQueryExecutor(driver, registry), registry);
        dsl.executeScript(model);
        return dsl;
    }

    /** A fresh in-memory (oracle) DSL with {@code model} already defined. */
    private CausalDSL memDsl(String model) {
        CausalDSL dsl = new CausalDSL();
        dsl.executeScript(model);
        return dsl;
    }

    // ─── Chain: X --2--> M --3--> Y ─────────────────────────────────────────────

    @Test
    void chain_totalEffect_pluginMatchesOracleAndEquals6() {
        InterventionResult neo = (InterventionResult) neoDsl(CHAIN).execute("INTERVENE ON X=1 RETURN EFFECT ON Y");
        InterventionResult mem = (InterventionResult) memDsl(CHAIN).execute("INTERVENE ON X=1 RETURN EFFECT ON Y");

        assertNotNull(neo.totalEffect(), "plugin path should produce a numeric total effect");
        assertEquals(6.0, neo.totalEffect(), 1e-9);
        assertEquals(mem.totalEffect(), neo.totalEffect(), 1e-9, "plugin must agree with the in-memory oracle");
        assertTrue(neo.affectsTarget());
    }

    @Test
    void chain_expectation_doX4_pluginMatchesOracleAndEquals24() {
        InterventionResult neo = (InterventionResult) neoDsl(CHAIN).execute("INTERVENE ON X=4 RETURN EXPECTATION OF Y");
        InterventionResult mem = (InterventionResult) memDsl(CHAIN).execute("INTERVENE ON X=4 RETURN EXPECTATION OF Y");

        assertEquals(24.0, neo.expectation(), 1e-9);
        assertEquals(mem.expectation(), neo.expectation(), 1e-9);
    }

    @Test
    void chain_distribution_doX1_pluginMatchesOracleMean6Var10() {
        InterventionResult neo = (InterventionResult) neoDsl(CHAIN).execute("INTERVENE ON X=1 RETURN DISTRIBUTION OF Y");
        InterventionResult mem = (InterventionResult) memDsl(CHAIN).execute("INTERVENE ON X=1 RETURN DISTRIBUTION OF Y");

        assertEquals(6.0, neo.expectation(), 1e-9);
        assertEquals(10.0, neo.variance(), 1e-9);
        assertEquals(mem.expectation(), neo.expectation(), 1e-9);
        assertEquals(mem.variance(), neo.variance(), 1e-9);
    }

    @Test
    void chain_compareTo_pluginMatchesOracleContrast6() {
        InterventionResult neo = (InterventionResult)
                neoDsl(CHAIN).execute("INTERVENE ON X=1 COMPARE TO X=0 RETURN EFFECT ON Y");
        assertEquals(6.0, neo.compareDelta(), 1e-9);   // totalEffect·(1−0)
    }

    // ─── Confounded fork: Z-5->X, Z-7->Y, X-2->Y ────────────────────────────────

    @Test
    void fork_doCutsBackdoor_totalEffectIs2() {
        InterventionResult neo = (InterventionResult) neoDsl(FORK).execute("INTERVENE ON X=1 RETURN EFFECT ON Y");
        InterventionResult mem = (InterventionResult) memDsl(FORK).execute("INTERVENE ON X=1 RETURN EFFECT ON Y");

        assertEquals(2.0, neo.totalEffect(), 1e-9);   // do(X) cuts Z→X ⇒ only the direct edge remains
        assertEquals(mem.totalEffect(), neo.totalEffect(), 1e-9);
    }

    @Test
    void fork_distribution_doX3_pluginMatchesOracleMean6Var50() {
        InterventionResult neo = (InterventionResult) neoDsl(FORK).execute("INTERVENE ON X=3 RETURN DISTRIBUTION OF Y");
        InterventionResult mem = (InterventionResult) memDsl(FORK).execute("INTERVENE ON X=3 RETURN DISTRIBUTION OF Y");

        assertEquals(6.0, neo.expectation(), 1e-9);   // μ(Y)=7·0 + 2·3 = 6
        assertEquals(50.0, neo.variance(), 1e-9);      // Var(Y)=7²·1 + 1 = 50 (X fixed ⇒ no cross-term)
        assertEquals(mem.expectation(), neo.expectation(), 1e-9);
        assertEquals(mem.variance(), neo.variance(), 1e-9);
    }

    // ─── Persistence: the SEM params actually reach Neo4j ───────────────────────

    @Test
    void definingModel_persistsSemParametersToNeo4j() {
        neoDsl(CHAIN);
        try (var session = driver.session()) {
            double weight = session.run(
                    "MATCH (:Variable {id:'X'})-[r:CAUSES]->(:Variable {id:'M'}) RETURN r.weight AS w")
                .single().get("w").asDouble();
            assertEquals(2.0, weight, 1e-9, "edge WEIGHT must be persisted for the plugin to read");
        }
    }
}
