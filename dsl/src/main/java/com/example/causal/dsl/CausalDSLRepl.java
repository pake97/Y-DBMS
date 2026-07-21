package com.example.causal.dsl;

import com.example.causal.dsl.exec.Neo4jQueryExecutor;
import com.example.causal.dsl.exec.QueryResult;
import com.example.causal.dsl.model.ModelRegistry;
import com.example.causal.dsl.repl.EmbeddedNeo4jBackend;
import com.example.causal.dsl.repl.LiveNeo4jBackend;
import com.example.causal.dsl.repl.Neo4jBackend;

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
 * <h2>Backends</h2>
 * The REPL runs against one of three backends, selected by {@code CAUSAL_DSL_MODE}:
 * <pre>
 *   memory     (default) in-memory executor, no database
 *   embedded   in-process Neo4j with the causal.* procedures registered — no server needed
 *   live       Bolt connection to a running Neo4j server (plugin JAR in its plugins/)
 * </pre>
 * The legacy flag {@code CAUSAL_DSL_NEO4J=1} (or {@code true}) is still honored
 * as an alias for {@code CAUSAL_DSL_MODE=live}. In {@code live} mode the
 * connection is read from these env vars (with defaults):
 * <pre>
 *   NEO4J_URI       bolt://localhost:7687
 *   NEO4J_USER      neo4j
 *   NEO4J_PASSWORD  neo4j
 *   NEO4J_DATABASE  neo4j
 * </pre>
 * In {@code embedded} and {@code live} modes the interactive REPL also accepts a
 * raw-Cypher escape: a line beginning with {@code :cypher } (alias {@code :c })
 * is executed verbatim against the same database, bypassing the DSL grammar:
 * <pre>
 *   causal&gt; :cypher MATCH (v:Variable) RETURN v.id ORDER BY v.id
 * </pre>
 */
public class CausalDSLRepl {

    private enum Mode { MEMORY, EMBEDDED, LIVE }

    public static void main(String[] args) throws Exception {
        Mode mode = resolveMode();

        Neo4jBackend backend = openBackend(mode);   // null in MEMORY mode
        Neo4jQueryExecutor neo4j = null;
        CausalDSL dsl;
        try {
            if (backend != null) {
                ModelRegistry registry = new ModelRegistry();
                neo4j = new Neo4jQueryExecutor(backend.driver(), backend.database(), registry);
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
            System.out.println("Backend: " + mode.name().toLowerCase());
            if (neo4j != null) {
                System.out.println("Use ':cypher <query>' (alias ':c') to run raw Cypher against the same database.");
            }
            System.out.println();

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
            if (backend != null) backend.close();
        }
    }

    /**
     * Resolve the backend mode from {@code CAUSAL_DSL_MODE}, falling back to the
     * legacy {@code CAUSAL_DSL_NEO4J} flag (→ {@code live}) and finally
     * {@code memory}.
     */
    private static Mode resolveMode() {
        String raw = System.getenv("CAUSAL_DSL_MODE");
        if (raw != null && !raw.isBlank()) {
            return switch (raw.trim().toLowerCase()) {
                case "memory"   -> Mode.MEMORY;
                case "embedded" -> Mode.EMBEDDED;
                case "live", "neo4j" -> Mode.LIVE;
                default -> throw new IllegalArgumentException(
                    "Unknown CAUSAL_DSL_MODE '" + raw + "' (expected: memory | embedded | live)");
            };
        }
        return envFlag("CAUSAL_DSL_NEO4J") ? Mode.LIVE : Mode.MEMORY;
    }

    /** Open the backend for {@code mode}, or {@code null} for the in-memory executor. */
    private static Neo4jBackend openBackend(Mode mode) {
        return switch (mode) {
            case MEMORY   -> null;
            case EMBEDDED -> new EmbeddedNeo4jBackend();
            case LIVE     -> new LiveNeo4jBackend(
                env("NEO4J_URI", "bolt://localhost:7687"),
                env("NEO4J_USER", "neo4j"),
                env("NEO4J_PASSWORD", "neo4j"),
                env("NEO4J_DATABASE", "neo4j"));
        };
    }

    /** Dispatch a submitted block: raw Cypher via {@code :cypher}, otherwise the DSL. */
    private static void runInput(String input, CausalDSL dsl, Neo4jQueryExecutor neo4j) {
        String cypher = stripCypherPrefix(input);
        if (cypher != null) {
            if (neo4j == null) {
                System.err.println("! :cypher requires a Neo4j backend — start with CAUSAL_DSL_MODE=embedded (or =live)");
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
