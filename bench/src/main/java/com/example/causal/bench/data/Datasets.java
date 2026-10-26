package com.example.causal.bench.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Enumerates WP5a datasets under bench/datasets; the repository root is found from the working directory. */
public final class Datasets {

    public static final ObjectMapper JSON = new ObjectMapper();

    /** One dataset directory with its meta.json. */
    public record Dataset(String id, String family, Path dir, JsonNode meta) {
        public int n()            { return meta.get("n").asInt(); }
        public String model()     { return meta.path("model").asText(null); }
        public int avgDegree()    { return meta.path("avg_degree").asInt(-1); }
        public int seed()         { return meta.path("seed").asInt(-1); }
    }

    private Datasets() {}

    /** Repository root: -Dydbms.root, else the nearest ancestor of the cwd holding bench/datasets. */
    public static Path root() {
        String p = System.getProperty("ydbms.root");
        if (p != null) return Path.of(p).toAbsolutePath();
        for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
            if (Files.isDirectory(d.resolve("bench/datasets"))) return d;
        }
        throw new IllegalStateException("cannot find bench/datasets above " + Path.of("").toAbsolutePath()
                + "; run from the repository or pass -Dydbms.root=<repo>");
    }

    public static Path datasetsDir()  { return root().resolve("bench/datasets"); }
    public static Path workloadsDir() { return root().resolve("bench/workloads"); }
    public static Path referenceDir() { return root().resolve("bench/reference"); }

    /** Hand-authored DAGs from dev/*.cypher, each its own family. */
    public static final List<String> SEEDS = List.of("covid", "policy");

    /** Every dataset with a meta.json, bnlearn, covid and policy first, then synthetic, in a stable order. */
    public static List<Dataset> all() {
        List<Dataset> out = new ArrayList<>();
        out.addAll(scan(datasetsDir().resolve("bnlearn"), "bnlearn"));
        for (String seed : SEEDS) {
            Path dir = datasetsDir().resolve(seed);
            if (Files.exists(dir.resolve("meta.json"))) out.add(load(dir, seed, seed));
        }
        out.addAll(scan(datasetsDir().resolve("synthetic"), "synthetic"));
        return out;
    }

    public static List<Dataset> where(Predicate<Dataset> filter) {
        return all().stream().filter(filter).toList();
    }

    /** The dataset with this id; fails with the regeneration command if edges.csv is missing. */
    public static Dataset byId(String id) {
        Dataset d = all().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() ->
                new IllegalArgumentException("unknown dataset " + id + " under " + datasetsDir()));
        if (!Files.exists(d.dir().resolve("edges.csv"))) {
            String cmd = switch (d.family()) {
                case "bnlearn" -> "ydbms-bench fetch --only " + id;
                case "synthetic" -> "ydbms-bench synth --only n=" + d.n();
                default -> "ydbms-bench " + d.family();
            };
            throw new IllegalStateException(d.dir().resolve("edges.csv") + " is missing; regenerate it with: " + cmd);
        }
        return d;
    }

    /** Path of the dataset's edges.csv, whether or not it exists (for test assumptions). */
    public static Path edgesPath(String id) {
        return all().stream().filter(x -> x.id().equals(id)).findFirst()
                .map(d -> d.dir().resolve("edges.csv")).orElse(datasetsDir().resolve(id).resolve("edges.csv"));
    }

    private static List<Dataset> scan(Path parent, String family) {
        if (!Files.isDirectory(parent)) return List.of();
        try (Stream<Path> dirs = Files.list(parent)) {
            return dirs.filter(d -> Files.exists(d.resolve("meta.json")))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(d -> load(d, family, d.getFileName().toString()))
                    .sorted(Comparator.comparingInt(Dataset::n).thenComparing(Dataset::id))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Dataset load(Path dir, String family, String id) {
        try {
            return new Dataset(id, family, dir, JSON.readTree(dir.resolve("meta.json").toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
