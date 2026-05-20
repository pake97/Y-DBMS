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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the plugin runtime using the COVID-19 seed DAG
 * ({@code dev/seed-dag.cypher}): Cypher ingestion → {@code causal.compile()}
 * → structural procedures.
 */
class CovidSeedDagE2ETest {

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
    }

    @AfterEach
    void tearDown() {
        neo4j.close();
        RuntimeRegistry.setInstance(null);
    }

    @Test
    void seedScriptCreatesExpectedGraph() {
        long[] counts = SeedDagSupport.graphCounts(db);
        assertEquals(SeedDagSupport.EXPECTED_VARIABLES, counts[0]);
        assertEquals(SeedDagSupport.EXPECTED_CAUSES_EDGES, counts[1]);
    }

    @Test
    void compileIngestsFullDagIntoSnapshot() {
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                "CALL causal.compile() YIELD variableCount, variables RETURN variableCount, variables");
            var row = result.next();
            assertEquals(SeedDagSupport.EXPECTED_VARIABLES, row.get("variableCount"));
            @SuppressWarnings("unchecked")
            List<String> vars = (List<String>) row.get("variables");
            assertEquals(SeedDagSupport.EXPECTED_VARIABLES, vars.size());
            assertTrue(vars.containsAll(List.of("interventions", "onset_covid19", "reported_cases")));
        }
    }

    @Test
    void parentsOfOnsetCovid19() {
        SeedDagSupport.compile(db);
        List<String> parents = collectIds(
            "CALL causal.parents(['onset_covid19']) YIELD variableId RETURN variableId ORDER BY variableId");
        assertEquals(
            List.of("access_tests", "age", "exposure_sarscov2", "gender", "susceptibility"),
            parents);
    }

    @Test
    void childrenOfInterventions() {
        SeedDagSupport.compile(db);
        List<String> children = collectIds(
            "CALL causal.children(['interventions']) YIELD variableId RETURN variableId ORDER BY variableId");
        assertEquals(List.of("exposure_sarscov2", "mobility", "searches_corona"), children);
    }

    @Test
    void ancestorsOfReportedCasesIncludeExposureChain() {
        SeedDagSupport.compile(db);
        Set<String> ancestors = Set.copyOf(collectIds(
            "CALL causal.ancestors(['reported_cases']) YIELD variableId RETURN variableId"));
        assertTrue(ancestors.contains("onset_covid19"));
        assertTrue(ancestors.contains("exposure_sarscov2"));
        assertTrue(ancestors.contains("interventions"));
    }

    @Test
    void hasDirectedPathFromInterventionsToReportedCases() {
        SeedDagSupport.compile(db);
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                "RETURN causal.hasDirectedPath('interventions', 'reported_cases') AS connected");
            assertEquals(Boolean.TRUE, result.next().get("connected"));
        }
    }

    private List<String> collectIds(String cypher) {
        List<String> result = new ArrayList<>();
        try (Transaction tx = db.beginTx()) {
            Result r = tx.execute(cypher);
            while (r.hasNext()) result.add((String) r.next().get("variableId"));
        }
        return result;
    }
}
