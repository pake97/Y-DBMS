package com.example.causal.procedures;

import com.example.causal.runtime.RuntimeRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.*;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link CausalProcedures} and {@link CausalFunctions}.
 *
 * <p>Uses the Neo4j test harness to spin up an embedded database with the
 * procedures and functions registered. Tests call procedures through the
 * standard {@link Transaction#execute} API, exercising the full path from
 * Cypher → procedure → runtime → compiled graph.
 *
 * <h2>Sample DAG</h2>
 * <pre>
 *   A ──► B ──► C
 *   │           ▲
 *   └───────────┘
 * </pre>
 * So: parents(C) = {A, B}, ancestors(C) = {A, B}, hasDirectedPath(A, C) = true.
 */
class CausalProceduresTest {

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
        seedDag();
    }

    @AfterEach
    void tearDown() {
        neo4j.close();
        RuntimeRegistry.setInstance(null); // prevent snapshot from leaking between tests
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void compileShouldReturnVariableCount() {
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute("CALL causal.compile() YIELD variableCount RETURN variableCount");
            assertEquals(3L, result.next().get("variableCount"));
        }
    }

    @Test
    void compileShouldListAllVariableIds() {
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute("CALL causal.compile() YIELD variables RETURN variables");
            @SuppressWarnings("unchecked")
            List<String> vars = (List<String>) result.next().get("variables");
            assertEquals(3, vars.size());
            assertTrue(vars.containsAll(List.of("A", "B", "C")));
        }
    }

    @Test
    void parentsOfC() {
        compile();
        List<String> parents = collectVariableIds(
                "MATCH (c:Variable {id: 'C'}) " +
                "CALL causal.parents([c]) YIELD variable " +
                "RETURN variable.id AS variableId ORDER BY variableId");
        assertEquals(List.of("A", "B"), parents);
    }

    @Test
    void childrenOfA() {
        compile();
        List<String> children = collectVariableIds(
                "MATCH (a:Variable {id: 'A'}) " +
                "CALL causal.children([a]) YIELD variable " +
                "RETURN variable.id AS variableId ORDER BY variableId");
        assertEquals(List.of("B", "C"), children);
    }

    @Test
    void ancestorsOfC() {
        compile();
        List<String> ancestors = collectVariableIds(
                "MATCH (c:Variable {id: 'C'}) " +
                "CALL causal.ancestors([c]) YIELD variable " +
                "RETURN variable.id AS variableId ORDER BY variableId");
        assertEquals(List.of("A", "B"), ancestors);
    }

    @Test
    void descendantsOfA() {
        compile();
        List<String> descendants = collectVariableIds(
                "MATCH (a:Variable {id: 'A'}) " +
                "CALL causal.descendants([a]) YIELD variable " +
                "RETURN variable.id AS variableId ORDER BY variableId");
        assertEquals(List.of("B", "C"), descendants);
    }

    @Test
    void yieldsVariableNodesWithCorrectLabel() {
        compile();
        try (Transaction tx = db.beginTx()) {
            Result r = tx.execute(
                    "MATCH (c:Variable {id: 'C'}) " +
                    "CALL causal.parents([c]) YIELD variable RETURN labels(variable) AS labels");
            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) r.next().get("labels");
            assertTrue(labels.contains("Variable"));
        }
    }

    @Test
    void hasDirectedPathFromAtoC() {
        compile();
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute("MATCH (a:Variable {id: 'A'}), (c:Variable {id: 'C'}) RETURN causal.hasDirectedPath(a, c) AS connected");
            assertEquals(Boolean.TRUE, result.next().get("connected"));
        }
    }

    @Test
    void noDirectedPathFromCtoA() {
        compile();
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute("MATCH (a:Variable {id: 'A'}), (c:Variable {id: 'C'}) RETURN causal.hasDirectedPath(c, a) AS connected");
            assertEquals(Boolean.FALSE, result.next().get("connected"));
        }
    }

    @Test
    void invalidateClearsSnapshot() {
        compile();
        try (Transaction tx = db.beginTx()) {
            Result r = tx.execute("CALL causal.invalidate()");
            while (r.hasNext()) r.next();
            tx.commit();
        }
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute("CALL causal.inspectSnapshot() YIELD variableCount RETURN variableCount");
            assertEquals(0L, result.next().get("variableCount"));
        }
    }

    @Test
    void dsepReturnsResult() {
        compile();
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                    "MATCH (a:Variable {id: 'A'}), (c:Variable {id: 'C'}), (b:Variable {id: 'B'}) " +
                    "CALL causal.dsep([a], [c], [b]) YIELD separated RETURN separated");
            assertNotNull(result.next().get("separated"));
        }
    }

    @Test
    void linearGaussianInferenceReturnsResult() {
        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH (n) SET n.noise = 1.0");
            tx.execute("MATCH ()-[r:CAUSES]->() SET r.weight = 2.0");
            tx.commit();
        }
        compile();
        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                    "MATCH (a:Variable {id: 'A'}), (c:Variable {id: 'C'}) " +
                    "CALL causal.linearGaussianInference(a, c, {A: 1.0}) YIELD means, variances, totalEffect RETURN means, variances, totalEffect");
            Map<String, Object> row = result.next();

            assertEquals(6.0, (Double) row.get("totalEffect"), 1e-5);
            assertEquals(Map.of("A", 1.0, "B", 2.0, "C", 6.0), row.get("means"));
            assertEquals(Map.of("A", 0.0, "B", 1.0, "C", 5.0), row.get("variances"));
        }
    }

    @Test
    void counterfactualReturnsResult() {
        try (Transaction tx = db.beginTx()) {
            tx.execute("MATCH (n) SET n.noise = 1.0");
            tx.execute("MATCH (a {id: 'A'})-[r:CAUSES]->(b {id: 'B'}) SET r.weight = 2.0");
            tx.execute("MATCH (b {id: 'B'})-[r:CAUSES]->(c {id: 'C'}) SET r.weight = 3.0");
            // Set A->C weight to 0.0 to effectively make it a chain A->B->C
            tx.execute("MATCH (a {id: 'A'})-[r:CAUSES]->(c {id: 'C'}) SET r.weight = 0.0");
            tx.commit();
        }
        compile();
        try (Transaction tx = db.beginTx()) {
            // A=X, C=Y
            // chain (β = 2, 3), GIVEN X=1, Y=10, IF X=3 ⇒ Y_cf = 10 + 6·(3−1) = 22
            Result result = tx.execute(
                    "MATCH (a:Variable {id: 'A'}), (c:Variable {id: 'C'}) " +
                    "CALL causal.counterfactual(a, 3.0, c, {A: 1.0, C: 10.0}) " +
                    "YIELD factual, counterfactual, difference " +
                    "RETURN factual, counterfactual, difference");
            Map<String, Object> row = result.next();

            assertEquals(10.0, (Double) row.get("factual"), 1e-5);
            assertEquals(22.0, (Double) row.get("counterfactual"), 1e-5);
            assertEquals(12.0, (Double) row.get("difference"), 1e-5);
        }
    }

    @Test
    void rejectsNonVariableNode() {
        compile();
        try (Transaction tx = db.beginTx()) {
            assertThrows(Exception.class, () -> {
                tx.execute(
                        "CREATE (n:Other) " +
                        "WITH n " +
                        "CALL causal.parents([n]) YIELD variable RETURN variable");
            });
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void compile() {
        try (Transaction tx = db.beginTx()) {
            Result r = tx.execute("CALL causal.compile()");
            while (r.hasNext()) r.next();
            tx.commit();
        }
    }

    private List<String> collectVariableIds(String cypher) {
        List<String> result = new ArrayList<>();
        try (Transaction tx = db.beginTx()) {
            Result r = tx.execute(cypher);
            while (r.hasNext()) result.add((String) r.next().get("variableId"));
        }
        return result;
    }

    /**
     * Seed the sample DAG:  A → B → C,  A → C
     *
     * Node properties: id, name, observed=true, latent=false
     * Relationship properties: active=true
     */
    private void seedDag() {
        try (Transaction tx = db.beginTx()) {
            Node a = createVariable(tx, "A");
            Node b = createVariable(tx, "B");
            Node c = createVariable(tx, "C");

            createCauses(a, b);
            createCauses(b, c);
            createCauses(a, c);

            tx.commit();
        }
    }

    private Node createVariable(Transaction tx, String id) {
        Node n = tx.createNode(Label.label("Variable"));
        n.setProperty("id",       id);
        n.setProperty("name",     id);
        n.setProperty("observed", true);
        n.setProperty("latent",   false);
        return n;
    }

    private void createCauses(Node from, Node to) {
        Relationship r = from.createRelationshipTo(to, RelationshipType.withName("CAUSES"));
        r.setProperty("active", true);
    }
}
