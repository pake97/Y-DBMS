package com.example.causal.dsl;

import com.example.causal.dsl.ast.Statement;
import com.example.causal.dsl.exec.CypherTranslator;
import com.example.causal.dsl.exec.CypherTranslator.CypherQuery;
import com.example.causal.dsl.parser.AstBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dry-run REPL: parses DSL input and prints the Cypher that would be sent to
 * Neo4j — but does NOT execute it. Useful for inspecting the translator and
 * debugging without a running Neo4j server.
 *
 * <p>Interactive mode (blank line submits, Ctrl-D exits):
 * <pre>
 *   mvn exec:java -pl dsl -Dexec.mainClass=com.example.causal.dsl.CypherDryRunRepl
 * </pre>
 *
 * <p>File mode:
 * <pre>
 *   mvn exec:java -pl dsl -Dexec.mainClass=com.example.causal.dsl.CypherDryRunRepl \
 *       -Dexec.args="dev/example.causal"
 * </pre>
 */
public class CypherDryRunRepl {

    private static final String LINE = "─".repeat(72);
    private static final AstBuilder        PARSER     = new AstBuilder();
    private static final CypherTranslator  TRANSLATOR = new CypherTranslator();

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            runScript(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
            return;
        }

        System.out.println("""
            Causal DSL — Cypher dry-run
            Type DSL statements; blank line translates & prints the generated Cypher.
            Ctrl-D (or Ctrl-Z on Windows) exits. Nothing is executed.
            """);

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder buf = new StringBuilder();
        System.out.print("causal> ");
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                String input = buf.toString().trim();
                if (!input.isEmpty()) {
                    try {
                        runScript(input);
                    } catch (Exception e) {
                        System.err.println("! " + e.getMessage());
                    }
                }
                buf.setLength(0);
                System.out.print("\ncausal> ");
            } else {
                buf.append(line).append('\n');
                System.out.print("      > ");
            }
        }
        System.out.println();
    }

    private static void runScript(String input) {
        List<Statement> stmts = PARSER.buildStatements(input);
        for (Statement stmt : stmts) printTranslation(stmt);
    }

    private static void printTranslation(Statement stmt) {
        System.out.println();
        System.out.println(LINE);
        System.out.println(" AST: " + stmt.getClass().getSimpleName());
        System.out.println("      " + summarise(stmt));
        System.out.println(LINE);

        List<CypherQuery> queries = TRANSLATOR.translate(stmt);
        if (queries.isEmpty()) {
            System.out.println(" (DSL-only — no Cypher emitted for this statement)");
            return;
        }

        for (int i = 0; i < queries.size(); i++) {
            CypherQuery q = queries.get(i);
            String kind = q.isWrite() ? "WRITE" : "READ ";
            String yield = q.yieldColumn() != null ? "   (yield: " + q.yieldColumn() + ")" : "";
            System.out.println();
            System.out.printf(" [%d/%d] %s%s%n", i + 1, queries.size(), kind, yield);
            System.out.println("   Cypher:");
            for (String l : q.cypher().split("\n"))
                System.out.println("     " + l);
            System.out.println("   Params: " + formatParams(q.params()));
        }
    }

    private static String summarise(Statement s) {
        // Compact first-line description so you can see what produced each block.
        String str = s.toString();
        return str.length() > 140 ? str.substring(0, 137) + "..." : str;
    }

    private static String formatParams(Map<String, Object> params) {
        if (params.isEmpty()) return "(none)";
        return params.entrySet().stream()
            .map(e -> e.getKey() + " = " + formatValue(e.getValue()))
            .collect(Collectors.joining("\n           "));
    }

    @SuppressWarnings("unchecked")
    private static String formatValue(Object v) {
        if (v instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            if (list.get(0) instanceof Map) {
                // Pretty-print list of maps (edge list) one per line
                StringBuilder sb = new StringBuilder("[\n");
                for (Object item : list) {
                    sb.append("             ").append(item).append(",\n");
                }
                sb.setLength(sb.length() - 2);
                sb.append("\n           ]");
                return sb.toString();
            }
            return list.toString();
        }
        if (v instanceof String s) return "\"" + s + "\"";
        return String.valueOf(v);
    }
}
