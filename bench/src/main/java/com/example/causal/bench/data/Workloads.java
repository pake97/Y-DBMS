package com.example.causal.bench.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** WP5a workloads (bench/workloads/<ds>) and reference answers (bench/reference/<ds>). */
public final class Workloads {

    public record Pair(String t, String y) {}
    public record Triple(String x, String y, List<String> z) {}

    private Workloads() {}

    public static List<Pair> pairs(String dataset) {
        List<Pair> out = new ArrayList<>();
        for (String[] r : csv(file(dataset, "pairs.csv"), 2)) out.add(new Pair(r[0], r[1]));
        return out;
    }

    public static List<Triple> triples(String dataset) {
        return triples(file(dataset, "triples.csv"));
    }

    /** triples_balanced.csv (50/50 separated/connected), added for tiers (b)/(c) in 5b-2. */
    public static List<Triple> balancedTriples(String dataset) {
        return triples(file(dataset, "triples_balanced.csv"));
    }

    /** dsep.json or dsep.json.gz. */
    public static JsonNode dsepReference(String dataset)       { return reference(dataset, "dsep"); }
    /** adjustment.json or adjustment.json.gz. */
    public static JsonNode adjustmentReference(String dataset) { return reference(dataset, "adjustment"); }

    private static List<Triple> triples(Path p) {
        List<Triple> out = new ArrayList<>();
        for (String[] r : csv(p, 3)) {
            List<String> z = r[2].isEmpty() ? List.of() : Arrays.asList(r[2].split(";"));
            out.add(new Triple(r[0], r[1], z));
        }
        return out;
    }

    private static Path file(String dataset, String name) {
        Path p = Datasets.workloadsDir().resolve(dataset).resolve(name);
        if (!Files.exists(p)) {
            throw new IllegalStateException(p + " is missing; regenerate it with: causal4j-bench workloads --only " + dataset);
        }
        return p;
    }

    private static JsonNode reference(String dataset, String base) {
        Path dir = Datasets.referenceDir().resolve(dataset);
        try {
            if (Files.exists(dir.resolve(base + ".json"))) return Datasets.JSON.readTree(dir.resolve(base + ".json").toFile());
            Path gz = dir.resolve(base + ".json.gz");
            if (!Files.exists(gz)) {
                throw new IllegalStateException(dir + " has no " + base + " reference; regenerate it with: causal4j-bench reference --only " + dataset);
            }
            try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
                return Datasets.JSON.readTree(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Header skipped; ids contain no commas (bnlearn, covid, v<i>), z uses ';'. */
    private static List<String[]> csv(Path p, int columns) {
        try {
            List<String> lines = Files.readAllLines(p);
            List<String[]> rows = new ArrayList<>(lines.size());
            for (String line : lines.subList(1, lines.size())) rows.add(line.split(",", columns));
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
