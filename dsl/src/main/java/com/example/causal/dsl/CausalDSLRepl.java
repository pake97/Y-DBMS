package com.example.causal.dsl;

import com.example.causal.dsl.exec.QueryResult;

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
 */
public class CausalDSLRepl {

    public static void main(String[] args) throws Exception {
        CausalDSL dsl = new CausalDSL();

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

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder buf = new StringBuilder();
        System.out.print("causal> ");
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                String input = buf.toString().trim();
                if (!input.isEmpty()) {
                    try {
                        for (QueryResult r : dsl.executeScript(input)) {
                            System.out.println("= " + r);
                        }
                    } catch (Exception e) {
                        System.err.println("! " + e.getMessage());
                    }
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
    }
}
