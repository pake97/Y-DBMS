package com.example.causal.dsl;

import com.example.causal.dsl.exec.IdentificationResult;
import com.example.causal.dsl.exec.InterventionResult;
import com.example.causal.dsl.exec.Neo4jQueryExecutor;
import com.example.causal.dsl.exec.VariableSetResult;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the <em>DSL → plugin</em> seam: real DSL statements are
 * run through {@link Neo4jQueryExecutor}, which translates them to Cypher and
 * executes them over Bolt against an embedded Neo4j with the {@code causal.*}
 * procedures registered.
 *
 * <p>Unlike {@code CausalDSLTest} (which uses the in-memory executor) and the
 * plugin's {@code CovidSeedDagE2ETest} (which calls procedures via raw Cypher),
 * this test exercises the generated Cypher in {@code CypherTranslator} — the
 * boundary that is otherwise untested and where contract drift (e.g. wrong
 * {@code YIELD} columns) silently breaks the integration.
 */
class DslPluginE2ETest {

    /** Mirrors the UserRetentionModel in {@code CausalDSLTest} so expected sets match. */
    private static final String DEFINE_DAG = """
        DEFINE CAUSAL DAG UserRetentionModel {
          Age        -> Treatment
          Age        -> Retention30
          PriorUsage -> Treatment
          PriorUsage -> Retention30
          Treatment  -> Engagement
          Treatment  -> Retention30
          Engagement -> Retention30
        }
        """;

    @TempDir
    Path tempDir;

    private Neo4j neo4j;
    private Driver driver;
    private CausalDSL dsl;

    @BeforeEach
    void setUp() {
        neo4j = Neo4jBuilders.newInProcessBuilder(tempDir)
                .withProcedure(CausalProcedures.class)
                .withFunction(CausalFunctions.class)
                .build();
        driver = GraphDatabase.driver(neo4j.boltURI());

        ModelRegistry registry = new ModelRegistry();
        dsl = new CausalDSL(new Neo4jQueryExecutor(driver, registry), registry);

        // DEFINE CAUSAL DAG writes the :Variable/:CAUSES graph and triggers
        // causal.compile() — this is the path that exercises the compile() YIELD
        // contract. If that Cypher is wrong, this line throws.
        dsl.executeScript(DEFINE_DAG);
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.close();
        if (neo4j != null) neo4j.close();
        // RuntimeRegistry is a process-scoped singleton — reset so tests don't leak.
        RuntimeRegistry.setInstance(null);
    }

    @Test
    void causesOf_returnsAncestorsViaPlugin() {
        VariableSetResult r = (VariableSetResult) dsl.execute("CAUSES OF Retention30");
        assertEquals(Set.of("Age", "PriorUsage", "Treatment", "Engagement"), r.variables());
    }

    @Test
    void effectsOf_returnsChildrenViaPlugin() {
        VariableSetResult r = (VariableSetResult) dsl.execute("EFFECTS OF Treatment");
        assertEquals(Set.of("Engagement", "Retention30"), r.variables());
    }

    @Test
    void mediatorsBetween_returnsPathVariablesViaPlugin() {
        VariableSetResult r = (VariableSetResult) dsl.execute("MEDIATORS BETWEEN Treatment AND Retention30");
        assertEquals(Set.of("Engagement"), r.variables());
    }

    @Test
    void confoundersOf_returnsCommonAncestorsViaPlugin() {
        VariableSetResult r = (VariableSetResult) dsl.execute("CONFOUNDERS OF Treatment AND Retention30");
        assertEquals(Set.of("Age", "PriorUsage"), r.variables());
    }

    // ─── Intervention over the plugin-backed graph ──────────────────────────────

    @Test
    void intervene_derivedWorldEvaluatedInMemory() {
        // Hydrates the live graph, applies do(Treatment), evaluates the RETURN
        // structurally in-memory — no new plugin procedure involved.
        InterventionResult r = (InterventionResult)
                dsl.execute("INTERVENE ON Treatment=1 RETURN EFFECT ON Retention30");
        assertTrue(r.affectsTarget());
        assertEquals(Set.of("Engagement"), r.pathway());
    }

    @Test
    void intervene_doesNotMutateNeo4j() {
        dsl.execute("INTERVENE ON Treatment=1 RETURN EFFECT ON Retention30");

        // The persisted graph is untouched: the two edges into Treatment still exist.
        try (var session = driver.session()) {
            long incoming = session.run(
                    "MATCH (:Variable)-[r:CAUSES]->(:Variable {id:'Treatment'}) " +
                    "WHERE r.active = true RETURN count(r) AS n")
                .single().get("n").asLong();
            assertEquals(2L, incoming, "do() must not delete persisted edges into Treatment");
        }

        // After RESET the active graph is the live plugin-backed graph again.
        dsl.execute("RESET");
        VariableSetResult causes = (VariableSetResult) dsl.execute("CAUSES OF Treatment");
        assertEquals(Set.of("Age", "PriorUsage"), causes.variables());
    }

    @Test
    void intervene_thenIdentifyOnDerivedWorld() {
        // IDENTIFY against the persisted graph is unimplemented plugin-side, but on
        // the derived world it runs in-memory: do(Treatment) removed the confounding,
        // so the effect is identifiable with no adjustment.
        dsl.execute("INTERVENE ON Treatment=1 RETURN EFFECT ON Retention30");
        IdentificationResult r = (IdentificationResult)
                dsl.execute("IDENTIFY EFFECT OF Treatment ON Retention30 RETURN ADJUSTMENT SETS");
        assertTrue(r.identifiable());
        assertTrue(r.method().contains("no backdoor"), "do() removes backdoor paths; got: " + r.method());
    }

    @Test
    void identify_returnsAdjustmentSetViaPlugin() {
        IdentificationResult r = (IdentificationResult)
                dsl.execute("IDENTIFY EFFECT OF Treatment ON Retention30 RETURN ADJUSTMENT SETS");
        assertTrue(r.identifiable(), "Treatment → Retention30 should be identifiable by backdoor adjustment");
        assertFalse(r.adjustmentSets().isEmpty(), "expected at least one adjustment set");
        // Every candidate must block the backdoor via the confounders {Age, PriorUsage}.
        assertTrue(
                r.adjustmentSets().stream().anyMatch(s -> s.containsAll(Set.of("Age", "PriorUsage"))),
                "an adjustment set should contain the confounders; got: " + r.adjustmentSets());
    }
}
