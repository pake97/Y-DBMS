package com.example.causal.procedures;

import com.example.causal.runtime.RuntimeRegistry;
import com.example.causal.testsupport.SeedDagSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for {@code causal.dsep()} and {@code causal.adjustmentCandidate()}
 * using the COVID-19 seed DAG ({@code dev/seed-dag.cypher}: 25 variables, 78 edges).
 *
 * <p>Exercises the full path — Cypher → procedure → runtime → compiled graph — against
 * a real epidemiological causal structure, complementing the pure-Java unit tests in
 * {@code DSeparationBayesBallTest} and {@code AdjustmentCandidateTest}.
 *
 * <h2>Key COVID-19 DAG fragments used by these tests</h2>
 * <pre>
 *   wind ──► temperature ◄── rainfall        (collider at temperature)
 *   wind ──► exposure_sarscov2 ◄── rainfall  (collider at exposure_sarscov2)
 *
 *   covid19_burden ──► interventions ──► exposure_sarscov2 ──► onset_covid19 ──► reported_cases
 *   covid19_burden ──────────────────────────────────────────► exposure_sarscov2
 *   (so covid19_burden confounds interventions → reported_cases)
 * </pre>
 */
class DsepAndAdjustmentE2ETest {

    @TempDir
    Path tempDir;

    private Neo4j neo4j;
    private GraphDatabaseService db;

    @BeforeEach
    void setUp() {
        neo4j = Neo4jBuilders.newInProcessBuilder(tempDir)
                .withProcedure(CausalProcedures.class)
                .withFunction(CausalFunctions.class)
                .build();
        db = neo4j.defaultDatabaseService();
        SeedDagSupport.load(db);
        SeedDagSupport.compile(db);
    }

    @AfterEach
    void tearDown() {
        neo4j.close();
        RuntimeRegistry.setInstance(null);
    }

    // =========================================================================
    // causal.dsep() — collider: wind → temperature ← rainfall
    // =========================================================================

    /**
     * {@code wind} and {@code rainfall} share no edge and no common cause.
     * They meet only at colliders ({@code temperature}, {@code exposure_sarscov2}).
     * Without conditioning on any collider or its descendant they must be d-separated.
     */
    @Test
    void dsep_windAndRainfall_separatedUnconditionally() {
        boolean separated = queryDsep("wind", "rainfall", List.of());
        assertTrue(separated,
                "wind and rainfall have no common cause and connect only via colliders; " +
                "they must be d-separated when no collider is conditioned on");
    }

    /**
     * {@code temperature} is a collider on the path wind → temperature ← rainfall.
     * Conditioning on it opens the path and d-connects {@code wind} and {@code rainfall}.
     */
    @Test
    void dsep_windAndRainfall_connectedWhenConditioningOnColliderTemperature() {
        boolean separated = queryDsep("wind", "rainfall", List.of("temperature"));
        assertFalse(separated,
                "Conditioning on the collider temperature opens wind → temperature ← rainfall; " +
                "wind and rainfall must be d-connected");
    }

    /**
     * {@code exposure_sarscov2} is also a collider: wind → exposure_sarscov2 ← rainfall.
     * Conditioning on it likewise opens a path between {@code wind} and {@code rainfall}.
     */
    @Test
    void dsep_windAndRainfall_connectedWhenConditioningOnColliderExposure() {
        boolean separated = queryDsep("wind", "rainfall", List.of("exposure_sarscov2"));
        assertFalse(separated,
                "Conditioning on the collider exposure_sarscov2 opens wind → exposure_sarscov2 ← rainfall; " +
                "wind and rainfall must be d-connected");
    }

    // =========================================================================
    // causal.dsep() — causal chain: interventions → … → reported_cases
    // =========================================================================

    /**
     * {@code interventions} has a direct causal chain to {@code reported_cases}
     * (via exposure_sarscov2 → onset_covid19 → reported_cases).
     * Without conditioning they are d-connected.
     */
    @Test
    void dsep_interventionsAndReportedCases_connectedUnconditionally() {
        boolean separated = queryDsep("interventions", "reported_cases", List.of());
        assertFalse(separated,
                "interventions and reported_cases are causally linked; they must NOT be d-separated");
    }

    /**
     * Conditioning on {@code exposure_sarscov2} actually OPENS the collider path
     * {@code interventions → exposure_sarscov2 ← age → onset_covid19 → reported_cases},
     * so {@code interventions} and {@code reported_cases} remain d-connected.
     * This demonstrates the collider-bias hazard of conditioning on a common effect.
     */
    @Test
    void dsep_interventionsAndReportedCases_stillConnectedWhenConditioningOnExposure() {
        boolean separated = queryDsep("interventions", "reported_cases", List.of("exposure_sarscov2"));
        assertFalse(separated,
                "Conditioning on exposure_sarscov2 opens the collider path " +
                "interventions → exposure_sarscov2 ← age → onset_covid19 → reported_cases; " +
                "they must remain d-connected");
    }

    /**
     * {@code reported_cases} has exactly one parent: {@code onset_covid19}.
     * Conditioning on it cuts every path into {@code reported_cases}, so
     * {@code interventions} and {@code reported_cases} become d-separated.
     */
    @Test
    void dsep_interventionsAndReportedCases_separatedWhenConditioningOnOnsetCovid19() {
        boolean separated = queryDsep("interventions", "reported_cases", List.of("onset_covid19"));
        assertTrue(separated,
                "onset_covid19 is the sole parent of reported_cases; conditioning on it " +
                "must block every path and d-separate interventions from reported_cases");
    }

    // =========================================================================
    // causal.dsep() — YIELD column contract
    // =========================================================================

    @Test
    void dsep_yieldColumnsHaveCorrectTypes() {
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                    "MATCH (w:Variable {id:'wind'}), (r:Variable {id:'rainfall'}) " +
                    "CALL causal.dsep([w], [r], []) YIELD separated, explanation " +
                    "RETURN separated, explanation");
            Map<String, Object> row = result.next();
            assertInstanceOf(Boolean.class, row.get("separated"),
                    "separated must be Boolean");
            assertInstanceOf(String.class, row.get("explanation"),
                    "explanation must be a String");
            assertFalse(((String) row.get("explanation")).isBlank(),
                    "explanation must be non-empty");
        }
    }

    // =========================================================================
    // causal.adjustmentCandidate() — interventions → reported_cases
    //
    // covid19_burden is the sole parent of interventions and also causes
    // reported_cases indirectly (covid19_burden → exposure_sarscov2 → … →
    // reported_cases), making it the confounder that must appear in a valid
    // adjustment set.
    // =========================================================================

    @Test
    void adjustment_interventionsToReportedCases_isIdentifiable() {
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                    "MATCH (t:Variable {id:'interventions'}), (o:Variable {id:'reported_cases'}) " +
                    "CALL causal.adjustmentCandidate(t, o) " +
                    "YIELD adjustmentSetExists " +
                    "RETURN adjustmentSetExists");
            assertTrue((Boolean) result.next().get("adjustmentSetExists"),
                    "The causal effect of interventions on reported_cases should be " +
                    "identifiable via backdoor adjustment");
        }
    }

    @Test
    void adjustment_interventionsToReportedCases_covid19BurdenInCandidateSet() {
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                    "MATCH (t:Variable {id:'interventions'}), (o:Variable {id:'reported_cases'}) " +
                    "CALL causal.adjustmentCandidate(t, o) " +
                    "YIELD adjustmentSetExists, candidateSets, explanation " +
                    "RETURN adjustmentSetExists, candidateSets, explanation");
            Map<String, Object> row = result.next();

            assertTrue((Boolean) row.get("adjustmentSetExists"));
            @SuppressWarnings("unchecked")
            List<List<org.neo4j.graphdb.Node>> candidateSets =
                    (List<List<org.neo4j.graphdb.Node>>) row.get("candidateSets");
            assertFalse(candidateSets.isEmpty(),
                    "at least one candidate adjustment set must be returned");

            // covid19_burden is the sole direct confounder of interventions → reported_cases
            boolean anySetContainsBurden = candidateSets.stream()
                    .anyMatch(set -> set.stream()
                            .anyMatch(n -> "covid19_burden".equals(n.getProperty("id"))));
            assertTrue(anySetContainsBurden,
                    "covid19_burden is the confounder; it must appear in at least one candidate set. " +
                    "Got sets: " + describeIds(candidateSets));
        }
    }

    @Test
    void adjustment_interventionsToReportedCases_descendantsOfInterventionsExcluded() {
        // Descendants of interventions (e.g. exposure_sarscov2, mobility,
        // searches_corona) must never appear in any candidate adjustment set.
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                    "MATCH (t:Variable {id:'interventions'}), (o:Variable {id:'reported_cases'}) " +
                    "CALL causal.adjustmentCandidate(t, o) " +
                    "YIELD candidateSets " +
                    "RETURN candidateSets");
            @SuppressWarnings("unchecked")
            List<List<org.neo4j.graphdb.Node>> candidateSets =
                    (List<List<org.neo4j.graphdb.Node>>) result.next().get("candidateSets");

            List<String> forbiddenDescendants =
                    List.of("exposure_sarscov2", "mobility", "searches_corona",
                            "onset_covid19", "reported_cases", "access_tests");
            for (List<org.neo4j.graphdb.Node> set : candidateSets) {
                List<String> ids = nodeIds(set);
                for (String forbidden : forbiddenDescendants) {
                    assertFalse(ids.contains(forbidden),
                            "Descendant '" + forbidden + "' of interventions must not appear " +
                            "in any candidate adjustment set; got: " + ids);
                }
            }
        }
    }

    @Test
    void adjustment_yieldColumnsHaveCorrectTypes() {
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                    "MATCH (t:Variable {id:'interventions'}), (o:Variable {id:'reported_cases'}) " +
                    "CALL causal.adjustmentCandidate(t, o) " +
                    "YIELD adjustmentSetExists, candidateSets, explanation " +
                    "RETURN adjustmentSetExists, candidateSets, explanation");
            Map<String, Object> row = result.next();
            assertInstanceOf(Boolean.class, row.get("adjustmentSetExists"),
                    "adjustmentSetExists must be Boolean");
            assertInstanceOf(List.class, row.get("candidateSets"),
                    "candidateSets must be a List");
            assertInstanceOf(String.class, row.get("explanation"),
                    "explanation must be a String");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Calls {@code causal.dsep([x], [y], conditionedOn)} and returns {@code separated}.
     * {@code conditionedOnIds} may be empty for unconditional d-separation queries.
     */
    private boolean queryDsep(String xId, String yId, List<String> conditionedOnIds) {
        String cypher = "MATCH (xNode:Variable {id: $xId}), (yNode:Variable {id: $yId}) " +
                "OPTIONAL MATCH (zNode:Variable) WHERE zNode.id IN $zIds " +
                "WITH xNode, yNode, collect(zNode) as zNodes " +
                "CALL causal.dsep([xNode], [yNode], zNodes) " +
                "YIELD separated RETURN separated";

        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(cypher, Map.of(
                    "xId", xId,
                    "yId", yId,
                    "zIds", conditionedOnIds
            ));
            return (Boolean) result.next().get("separated");
        }
    }

    private List<String> nodeIds(List<org.neo4j.graphdb.Node> nodes) {
        List<String> ids = new ArrayList<>();
        for (org.neo4j.graphdb.Node n : nodes) ids.add((String) n.getProperty("id"));
        return ids;
    }

    private String describeIds(List<List<org.neo4j.graphdb.Node>> sets) {
        List<List<String>> described = new ArrayList<>();
        for (List<org.neo4j.graphdb.Node> set : sets) described.add(nodeIds(set));
        return described.toString();
    }
}
