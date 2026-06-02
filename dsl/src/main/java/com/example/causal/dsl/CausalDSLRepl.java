package com.example.causal.dsl;

import com.example.causal.dsl.exec.Neo4jQueryExecutor;
import com.example.causal.dsl.exec.QueryResult;
import com.example.causal.dsl.model.ModelRegistry;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for interactive and script-mode DSL execution.
 *
 * <pre>
 *   # Interactive REPL — blank line submits a statement, Ctrl-D exits
 *   mvn exec:java -pl dsl -Dexec.mainClass=com.example.causal.dsl.CausalDSLRepl
 *
 *   # Script file mode
 *   mvn exec:java -pl dsl -Dexec.mainClass=com.example.causal.dsl.CausalDSLRepl \
 *       -Dexec.args="dev/example.causal"
 * </pre>
 *
 * <h2>Live Neo4j mode</h2>
 * By default the REPL runs against an in-memory executor (no database). Set
 * {@code CAUSAL_DSL_NEO4J=1} (or {@code true}) to connect to a running Neo4j
 * server instead; the connection is read from these env vars (with defaults):
 * <pre>
 *   NEO4J_URI       bolt://localhost:7687
 *   NEO4J_USER      neo4j
 *   NEO4J_PASSWORD  neo4j
 *   NEO4J_DATABASE  neo4j
 * </pre>
 * In Neo4j mode the interactive REPL also accepts a raw-Cypher escape: a line
 * beginning with {@code :cypher } (alias {@code :c }) is executed verbatim
 * against the same database, bypassing the DSL grammar. Example:
 * <pre>
 *   causal&gt; :cypher MATCH (v:Variable) RETURN v.id ORDER BY v.id
 * </pre>
 */
public class CausalDSLRepl {

    public static void main(String[] args) throws Exception {
        boolean useNeo4j = envFlag("CAUSAL_DSL_NEO4J");

        Driver driver = null;
        CausalDSL dsl;
        Neo4jQueryExecutor neo4j = null;
        try {
            if (useNeo4j) {
                String uri  = env("NEO4J_URI", "bolt://localhost:7687");
                String user = env("NEO4J_USER", "neo4j");
                String pass = env("NEO4J_PASSWORD", "neo4j");
                String db   = env("NEO4J_DATABASE", "neo4j");
                driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pass));
                ModelRegistry registry = new ModelRegistry();
                neo4j = new Neo4jQueryExecutor(driver, db, registry);
                dsl = new CausalDSL(neo4j, registry);
            } else {
                dsl = new CausalDSL();
            }

            if (args.length == 1) {
                String script = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
                List<QueryResult> results = dsl.executeScript(script);
                results.forEach(r -> System.out.println("= " + r + "\n"));
                return;
            }

            System.out.println("""
                Causal DSL v1 — type one or more statements, blank line to execute.
                Ctrl-D (or Ctrl-Z on Windows) to exit.
                """);
            if (neo4j != null) {
                System.out.println("Connected to Neo4j — use ':cypher <query>' (alias ':c') to run raw Cypher.\n");
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            StringBuilder buf = new StringBuilder();
            System.out.print("causal> ");
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    String input = buf.toString().trim();
                    if (!input.isEmpty()) {
                        runInput(input, dsl, neo4j);
                        System.out.println();
                    }
                    buf.setLength(0);
                    System.out.print("causal> ");
                } else {
                    buf.append(line).append('\n');
                    System.out.print("      > ");
                }
            }
            System.out.println();
        } finally {
            if (driver != null) driver.close();
        }
    }

    /** Dispatch a submitted block: raw Cypher via {@code :cypher}, otherwise the DSL. */
    private static void runInput(String input, CausalDSL dsl, Neo4jQueryExecutor neo4j) {
        String cypher = stripCypherPrefix(input);
        if (cypher != null) {
            if (neo4j == null) {
                System.err.println("! :cypher requires Neo4j mode — start with CAUSAL_DSL_NEO4J=1");
                return;
            }
            try {
                neo4j.runRawCypher(cypher).forEach(System.out::println);
            } catch (Exception e) {
                System.err.println("! " + e.getMessage());
            }
            return;
        }
        try {
            for (QueryResult r : dsl.executeScript(input)) {
                System.out.println("= " + r);
            }
        } catch (Exception e) {
            System.err.println("! " + e.getMessage());
        }
    }

    /** Returns the Cypher body if {@code input} uses the {@code :cypher}/{@code :c} prefix, else null. */
    private static String stripCypherPrefix(String input) {
        for (String prefix : new String[] {":cypher", ":c"}) {
            if (input.equals(prefix)) return "";
            if (input.startsWith(prefix + " ") || input.startsWith(prefix + "\n")) {
                return input.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static boolean envFlag(String key) {
        String v = System.getenv(key);
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }
}
