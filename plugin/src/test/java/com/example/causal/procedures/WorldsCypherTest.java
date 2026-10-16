package com.example.causal.procedures;

import com.example.causal.runtime.RuntimeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** causal.intervene / use / models / drop and the model argument of the read procedures. */
class WorldsCypherTest {

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
        // Z -> X -> M -> Y, X -> Y, Z -> Y, plus eight unrelated variables (the cut stays a patch)
        run("UNWIND ['Z','X','M','Y','P1','P2','P3','P4','P5','P6','P7','P8'] AS id CREATE (:Variable {id: id, name: id})");
        run("UNWIND [['Z','X'],['X','M'],['M','Y'],['X','Y'],['Z','Y']] AS e "
                + "MATCH (a:Variable {id: e[0]}), (b:Variable {id: e[1]}) CREATE (a)-[:CAUSES {active: true}]->(b)");
        run("CALL causal.compile()");
    }

    @AfterEach
    void tearDown() {
        if (neo4j != null) neo4j.close();
        RuntimeRegistry.setInstance(null);
    }

    private List<Map<String, Object>> run(String cypher) {
        try (Transaction tx = db.beginTx()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            tx.execute(cypher).forEachRemaining(rows::add);
            tx.commit();
            return rows;
        }
    }

    private Set<String> ids(String procedure, String id, String model) {
        Set<String> out = new TreeSet<>();
        run("MATCH (v:Variable {id: '" + id + "'}) CALL " + procedure + "([v], '" + model + "') YIELD id RETURN id")
                .forEach(r -> out.add((String) r.get("id")));
        return out;
    }

    private Map<String, Object> intervene(String name, String vars, String from) {
        return run("MATCH (v:Variable) WHERE v.id IN " + vars + " WITH collect(v) AS vs "
                + "CALL causal.intervene('" + name + "', vs, '" + from + "') YIELD name, base, intervened, removedEdges, "
                + "coneSize, sharedRows, totalRows, millis RETURN *").get(0);
    }

    @Test
    void interveneChangesTheWorldsAnswerAndNotTheBase() {
        Object compiledAt = run("CALL causal.inspectSnapshot()").get(0).get("compiledAt");
        Map<String, Object> w = intervene("w", "['X']", "");
        assertEquals("base", w.get("base"));
        assertEquals(List.of("X"), w.get("intervened"));
        assertEquals(1L, w.get("removedEdges"));
        assertEquals(24L, w.get("totalRows"));
        assertEquals((Long) w.get("totalRows") - (Long) w.get("sharedRows"), w.get("coneSize"));
        assertTrue((Long) w.get("sharedRows") > 0, "untouched rows are shared with the base");

        assertEquals(Set.of("Z"), ids("causal.ancestorIds", "X", ""));
        assertEquals(Set.of(), ids("causal.ancestorIds", "X", "w"));
        assertEquals(Set.of("M", "X", "Z"), ids("causal.ancestorIds", "Y", "w"), "Z -> Y remains");
        assertEquals(Set.of("Z"), ids("causal.ancestorIds", "X", "base"));
        assertEquals(compiledAt, run("CALL causal.inspectSnapshot()").get(0).get("compiledAt"),
                "intervene does not recompile the base");
        assertEquals(compiledAt, run("CALL causal.inspectSnapshot('base')").get(0).get("compiledAt"));
    }

    @Test
    void anEditToTheStoredGraphReachesTheDerivedWorld() {
        intervene("w", "['X']", "");
        run("MATCH (p:Variable {id: 'P1'}), (m:Variable {id: 'M'}) CREATE (p)-[:CAUSES {active: true}]->(m)");
        assertEquals(Set.of("P1", "X"), ids("causal.ancestorIds", "M", "w"), "no new intervene needed");
        run("MATCH (p:Variable {id: 'P2'}), (x:Variable {id: 'X'}) CREATE (p)-[:CAUSES {active: true}]->(x)");
        assertEquals(Set.of(), ids("causal.ancestorIds", "X", "w"), "edges into X stay cut");
        assertEquals(Set.of("P2", "Z"), ids("causal.ancestorIds", "X", "base"));
    }

    @Test
    void modelsUseAndDrop() {
        intervene("w", "['X']", "");
        intervene("w2", "['M']", "w");
        List<Map<String, Object>> models = run("CALL causal.models()");
        assertEquals(List.of("base", "w", "w2"), models.stream().map(r -> r.get("name")).toList());
        assertEquals(List.of("M", "X"), models.get(2).get("intervened"));
        assertEquals("w", models.get(2).get("base"));
        assertEquals(Boolean.TRUE, models.get(0).get("active"));
        assertEquals(12L, models.get(1).get("nodes"));
        assertEquals(4L, models.get(1).get("edges"));

        run("CALL causal.use('w2')");
        assertEquals(Set.of(), ids("causal.ancestorIds", "M", ""), "blank reads the active world");
        Map<String, Object> rowW2 = run("CALL causal.models()").get(2);
        assertEquals(Boolean.TRUE, rowW2.get("active"));

        Exception e = assertThrows(Exception.class, () -> run("CALL causal.drop('w')"));
        assertTrue(rootMessage(e).contains("w2"), rootMessage(e));
        run("CALL causal.drop('w2')");
        assertEquals("base", run("CALL causal.models()").stream()
                .filter(r -> Boolean.TRUE.equals(r.get("active"))).findFirst().orElseThrow().get("name"));
        Exception unknown = assertThrows(Exception.class, () -> ids("causal.ancestorIds", "X", "w2"));
        assertTrue(rootMessage(unknown).contains("Unknown model"), rootMessage(unknown));
    }

    @Test
    void everyReadProcedureTakesTheModel() {
        intervene("w", "['X']", "");
        // adjustment: in do(X) nothing confounds X -> Y; in the base {Z} is needed
        Map<String, Object> base = run("MATCH (x:Variable {id: 'X'}), (y:Variable {id: 'Y'}) "
                + "CALL causal.adjustmentSets(x, y, 'all-minimal', 10) YIELD sets RETURN [s IN sets | [v IN s | v.id]] AS sets").get(0);
        Map<String, Object> w = run("MATCH (x:Variable {id: 'X'}), (y:Variable {id: 'Y'}) "
                + "CALL causal.adjustmentSets(x, y, 'all-minimal', 10, 'w') YIELD sets RETURN [s IN sets | [v IN s | v.id]] AS sets").get(0);
        assertEquals(List.of(List.of("Z")), base.get("sets"));
        assertEquals(List.of(List.of()), w.get("sets"));
        assertEquals(Boolean.TRUE, run("MATCH (x:Variable {id: 'X'}), (z:Variable {id: 'Z'}) "
                + "CALL causal.dsep([x], [z], [], 'w') YIELD separated RETURN separated").get(0).get("separated"));
        assertEquals(Boolean.FALSE, run("MATCH (x:Variable {id: 'X'}), (z:Variable {id: 'Z'}) "
                + "CALL causal.dsep([x], [z], []) YIELD separated RETURN separated").get(0).get("separated"));
        assertEquals(12L, run("CALL causal.inspectSnapshot('w')").get(0).get("variableCount"));
        assertEquals(List.of("Z"), run("MATCH (x:Variable {id: 'X'}), (y:Variable {id: 'Y'}) "
                + "CALL causal.confounders(x, y) YIELD variable RETURN collect(variable.id) AS c").get(0).get("c"));
        assertEquals(List.of(), run("MATCH (x:Variable {id: 'X'}), (y:Variable {id: 'Y'}) "
                + "CALL causal.confounders(x, y, 'w') YIELD variable RETURN collect(variable.id) AS c").get(0).get("c"));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
