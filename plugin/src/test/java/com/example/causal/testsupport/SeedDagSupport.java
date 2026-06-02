package com.example.causal.testsupport;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Loads {@code dev/seed-dag.cypher} (COVID-19 DAG: 25 variables, 76 edges) into
 * an embedded or harness Neo4j database for end-to-end tests.
 */
public final class SeedDagSupport {

    public static final int EXPECTED_VARIABLES = 25;
    public static final int EXPECTED_CAUSES_EDGES = 76;

    private SeedDagSupport() {}

    /** Load the seed script from the classpath (see {@code plugin/pom.xml} testResources). */
    public static void load(GraphDatabaseService db) {
        load(db, readClasspathScript());
    }

    /** Load the seed script from an explicit file (e.g. {@code dev/seed-dag.cypher}). */
    public static void load(GraphDatabaseService db, Path cypherFile) {
        try {
            load(db, java.nio.file.Files.readString(cypherFile));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + cypherFile, e);
        }
    }

    public static void load(GraphDatabaseService db, String script) {
        for (String statement : splitStatements(script)) {
            try (Transaction tx = db.beginTx()) {
                Result r = tx.execute(statement);
                while (r.hasNext()) r.next();
                tx.commit();
            }
        }
    }

    public static void compile(GraphDatabaseService db) {
        try (Transaction tx = db.beginTx()) {
            Result r = tx.execute("CALL causal.compile()");
            while (r.hasNext()) r.next();
            tx.commit();
        }
    }

    /** Returns {@code [variableCount, edgeCount]} after loading (runs the seed verify query). */
    public static long[] graphCounts(GraphDatabaseService db) {
        try (Transaction tx = db.beginTx()) {
            Result r = tx.execute(
                "MATCH (v:Variable) WITH count(v) AS nodes " +
                "MATCH ()-[r:CAUSES]->() WITH nodes, count(r) AS edges " +
                "RETURN nodes AS variables, edges AS causalEdges");
            var row = r.next();
            return new long[] {
                (long) row.get("variables"),
                (long) row.get("causalEdges")
            };
        }
    }

    static String readClasspathScript() {
        try (InputStream in = SeedDagSupport.class.getResourceAsStream("/seed-dag.cypher")) {
            if (in == null) {
                throw new IllegalStateException(
                    "seed-dag.cypher not on classpath — check plugin testResources in pom.xml");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read seed-dag.cypher", e);
        }
    }

    static String[] splitStatements(String script) {
        String cleaned = Arrays.stream(script.split("\n"))
            .filter(line -> !line.trim().startsWith("//"))
            .collect(Collectors.joining("\n"));
        return Arrays.stream(cleaned.split(";"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
    }
}
