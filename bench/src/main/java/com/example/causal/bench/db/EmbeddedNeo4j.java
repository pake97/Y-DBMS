package com.example.causal.bench.db;

import com.example.causal.bench.data.EdgeList;
import com.example.causal.procedures.CausalFunctions;
import com.example.causal.procedures.CausalProcedures;
import org.neo4j.gds.catalog.GraphDropProc;
import org.neo4j.gds.catalog.GraphProjectProc;
import org.neo4j.gds.paths.traverse.BfsStreamProc;
import org.neo4j.gds.paths.traverse.DfsStreamProc;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-process Neo4j (neo4j-harness) with the Y-DBMS and GDS procedures, loaded
 * with one WP5a dataset and compiled. Same registration as CausalProceduresTest.
 */
public final class EmbeddedNeo4j implements AutoCloseable {

    private static final int BATCH = 10_000;

    private final Neo4j neo4j;
    private final GraphDatabaseService db;
    private final Path dir;

    private EmbeddedNeo4j(Neo4j neo4j, Path dir) {
        this.neo4j = neo4j;
        this.db = neo4j.defaultDatabaseService();
        this.dir = dir;
    }

    /** Start a fresh database, load {@code el} and run causal.compile(). */
    public static EmbeddedNeo4j start(EdgeList el) {
        try {
            Path dir = Files.createTempDirectory("ydbms-bench-");
            Neo4j neo4j = Neo4jBuilders.newInProcessBuilder(dir)
                    .withProcedure(CausalProcedures.class)
                    .withFunction(CausalFunctions.class)
                    .withProcedure(GraphProjectProc.class)
                    .withProcedure(GraphDropProc.class)
                    .withProcedure(DfsStreamProc.class)
                    .withProcedure(BfsStreamProc.class)
                    .build();
            EmbeddedNeo4j e = new EmbeddedNeo4j(neo4j, dir);
            e.load(el);
            e.run("CALL causal.compile()", Map.of());
            return e;
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    public GraphDatabaseService db() { return db; }
    public URI boltUri()             { return neo4j.boltURI(); }

    private void load(EdgeList el) {
        run("CREATE CONSTRAINT variable_id_unique IF NOT EXISTS FOR (v:Variable) REQUIRE v.id IS UNIQUE", Map.of());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : el.ids()) {
            boolean latent = el.latent().contains(id);
            rows.add(Map.of("id", id, "observed", !latent, "latent", latent));
            if (rows.size() == BATCH) rows = flush(rows,
                    "UNWIND $rows AS r CREATE (:Variable {id: r.id, name: r.id, observed: r.observed, latent: r.latent})");
        }
        flush(rows, "UNWIND $rows AS r CREATE (:Variable {id: r.id, name: r.id, observed: r.observed, latent: r.latent})");
        rows = new ArrayList<>();
        String edgeCypher = "UNWIND $rows AS r MATCH (a:Variable {id: r.f}), (b:Variable {id: r.t}) "
                + "CREATE (a)-[:CAUSES {active: true}]->(b)";
        for (int[] e : el.edges()) {
            rows.add(Map.of("f", el.ids().get(e[0]), "t", el.ids().get(e[1])));
            if (rows.size() == BATCH) rows = flush(rows, edgeCypher);
        }
        flush(rows, edgeCypher);
    }

    private List<Map<String, Object>> flush(List<Map<String, Object>> rows, String cypher) {
        if (!rows.isEmpty()) run(cypher, Map.of("rows", rows));
        return new ArrayList<>();
    }

    /** Run and fully consume one statement in its own transaction. */
    public void run(String cypher, Map<String, Object> params) {
        try (Transaction tx = db.beginTx()) {
            Result r = tx.execute(cypher, params);
            while (r.hasNext()) r.next();
            tx.commit();
        }
    }

    @Override
    public void close() {
        neo4j.close();
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (java.io.IOException ignored) {
            // best effort: temp directory
        }
    }
}
