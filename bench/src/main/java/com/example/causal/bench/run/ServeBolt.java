package com.example.causal.bench.run;

import com.example.causal.bench.data.EdgeListLoader;
import com.example.causal.bench.db.EmbeddedNeo4j;

/**
 * Starts embedded Neo4j with one dataset, prints "BOLT <uri>" and runs the given
 * command with BOLT_URI set (the external round-trip script, Step 6), then stops.
 * usage: ServeBolt <dataset> <command...>
 */
public final class ServeBolt {
    public static void main(String[] args) throws Exception {
        try (EmbeddedNeo4j neo = EmbeddedNeo4j.start(EdgeListLoader.load(args[0]))) {
            System.out.println("BOLT " + neo.boltUri());
            ProcessBuilder pb = new ProcessBuilder(java.util.Arrays.copyOfRange(args, 1, args.length)).inheritIO();
            pb.environment().put("BOLT_URI", neo.boltUri().toString());
            int rc = pb.start().waitFor();
            if (rc != 0) throw new IllegalStateException("external command exited with " + rc);
        }
    }
}
