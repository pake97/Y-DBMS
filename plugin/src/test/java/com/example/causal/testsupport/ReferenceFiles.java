package com.example.causal.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WP5a's reference answers (dagitty and pgmpy) under {@code bench/reference/<dataset>/}, read
 * from the repository (the plugin tests run in {@code plugin/}). Format: {@code adjustment.json}
 * {@code {"pairs": [{"t","y","canonical": [..] | null, "minimal": [[..],..] | null,
 * "minimal_count", "identifiable"}]}}, {@code dsep.json} {@code {"triples": [{"x","y","z",
 * "pgmpy","dagitty"}]}}.
 */
public final class ReferenceFiles {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReferenceFiles() {}

    public static JsonNode adjustment(String dataset) { return read(dataset, "adjustment.json").get("pairs"); }
    public static JsonNode dsep(String dataset)       { return read(dataset, "dsep.json").get("triples"); }

    public static Set<String> ids(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        array.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static JsonNode read(String dataset, String file) {
        for (Path d = Path.of("").toAbsolutePath(); d != null; d = d.getParent()) {
            Path p = d.resolve("bench/reference").resolve(dataset).resolve(file);
            if (Files.exists(p)) {
                try {
                    return JSON.readTree(p.toFile());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException("bench/reference/" + dataset + "/" + file + " not found above " + Path.of("").toAbsolutePath());
    }
}
